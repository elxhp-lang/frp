// goproxy — 纯 Go 静态编译 HTTP 正向代理
// 编译: CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -ldflags="-s -w" -o goproxy .
//
// 用法:
//   goproxy                            # 默认 127.0.0.1:7890
//   goproxy -port 8080                 # 指定端口
//   goproxy -bind 0.0.0.0 -port 7890   # 绑定地址+端口
//   goproxy -c /path/to/config         # 从配置文件读取 (tinyproxy 兼容格式)
//
// 配置文件格式 (tinyproxy 兼容):
//   Port 7890
//   Listen 127.0.0.1
//
// 支持:
//   - HTTP 正向代理 (GET/POST 等)
//   - CONNECT 隧道 (HTTPS)
//   - stdout 日志 (供 APK 实时捕获)

package main

import (
	"bufio"
	"crypto/tls"
	"flag"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"
)

func main() {
	port := flag.String("port", "7890", "listen port")
	bind := flag.String("bind", "127.0.0.1", "bind address")
	config := flag.String("c", "", "config file path")
	flag.Parse()

	// 配置文件读取 (tinyproxy 兼容格式)
	if *config != "" {
		parseConfig(*config, bind, port)
	}

	addr := net.JoinHostPort(*bind, *port)

	handler := &proxyHandler{
		dialer: &net.Dialer{
			Timeout:   30 * time.Second,
			KeepAlive: 30 * time.Second,
		},
	}

	srv := &http.Server{
		Addr:         addr,
		Handler:      handler,
		ReadTimeout:  30 * time.Second,
		WriteTimeout: 30 * time.Second,
		IdleTimeout:  120 * time.Second,
		// 禁用 HTTP/2，代理不需要
		TLSNextProto: make(map[string]func(*http.Server, *tls.Conn, http.Handler)),
	}

	// 捕获退出信号，优雅关闭
	go func() {
		sigCh := make(chan os.Signal, 1)
		signal.Notify(sigCh, syscall.SIGTERM, syscall.SIGINT)
		sig := <-sigCh
		log.Printf("[goproxy] received signal %v, shutting down...", sig)
		srv.Close()
	}()

	log.Printf("[goproxy] HTTP proxy listening on %s", addr)
	if err := srv.ListenAndServe(); err != http.ErrServerClosed {
		log.Fatalf("[goproxy] failed to start: %v", err)
	}
	log.Printf("[goproxy] shutdown complete")
}

type proxyHandler struct {
	dialer *net.Dialer
}

func (h *proxyHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodConnect {
		h.handleConnect(w, r)
	} else {
		h.handleHTTP(w, r)
	}
}

// handleConnect 处理 CONNECT 隧道 (HTTPS)
func (h *proxyHandler) handleConnect(w http.ResponseWriter, r *http.Request) {
	log.Printf("[goproxy] CONNECT %s", r.Host)

	// 连接目标服务器
	dst, err := h.dialer.Dial("tcp", r.Host)
	if err != nil {
		log.Printf("[goproxy] CONNECT %s FAILED: %v", r.Host, err)
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}

	// Hijack 客户端连接
	hijacker, ok := w.(http.Hijacker)
	if !ok {
		log.Printf("[goproxy] CONNECT %s: hijacking not supported", r.Host)
		http.Error(w, "hijacking not supported", http.StatusInternalServerError)
		dst.Close()
		return
	}

	clientConn, _, err := hijacker.Hijack()
	if err != nil {
		log.Printf("[goproxy] CONNECT %s: hijack failed: %v", r.Host, err)
		http.Error(w, err.Error(), http.StatusServiceUnavailable)
		dst.Close()
		return
	}

	// 通知客户端隧道已建立
	clientConn.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n"))
	log.Printf("[goproxy] CONNECT %s ESTABLISHED", r.Host)

	// 双向数据转发
	go func() {
		defer clientConn.Close()
		defer dst.Close()
		io.Copy(dst, clientConn)
	}()
	go func() {
		defer dst.Close()
		defer clientConn.Close()
		io.Copy(clientConn, dst)
	}()
}

// handleHTTP 处理普通 HTTP 代理请求
func (h *proxyHandler) handleHTTP(w http.ResponseWriter, r *http.Request) {
	log.Printf("[goproxy] %s %s", r.Method, r.RequestURI)

	// 清理请求头中的 hop-by-hop 头
	removeHopByHop(r.Header)

	// 构建出站请求
	outReq := &http.Request{
		Method:     r.Method,
		URL:        r.URL,
		Proto:      "HTTP/1.1",
		ProtoMajor: 1,
		ProtoMinor: 1,
		Header:     r.Header.Clone(),
		Body:       r.Body,
		Host:       r.Host,
	}

	// 发送请求
	transport := &http.Transport{
		DialContext:           h.dialer.DialContext,
		MaxIdleConns:          100,
		IdleConnTimeout:       90 * time.Second,
		TLSHandshakeTimeout:   10 * time.Second,
		ExpectContinueTimeout: 1 * time.Second,
	}

	resp, err := transport.RoundTrip(outReq)
	if err != nil {
		log.Printf("[goproxy] %s %s FAILED: %v", r.Method, r.RequestURI, err)
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

	// 清理响应头
	removeHopByHop(resp.Header)

	// 复制响应头
	for k, vv := range resp.Header {
		for _, v := range vv {
			w.Header().Add(k, v)
		}
	}

	// 写入状态码和 body
	w.WriteHeader(resp.StatusCode)
	written, _ := io.Copy(w, resp.Body)
	log.Printf("[goproxy] %s %s -> %d (%d bytes)", r.Method, r.RequestURI, resp.StatusCode, written)
}

// removeHopByHop 移除 HTTP hop-by-hop 头
func removeHopByHop(h http.Header) {
	hopByHop := []string{
		"Connection",
		"Keep-Alive",
		"Proxy-Authenticate",
		"Proxy-Authorization",
		"TE",
		"Trailers",
		"Transfer-Encoding",
		"Upgrade",
		"Proxy-Connection",
	}
	for _, k := range hopByHop {
		h.Del(k)
	}
}

// parseConfig 解析 tinyproxy 兼容格式的配置文件
func parseConfig(path string, bind, port *string) {
	f, err := os.Open(path)
	if err != nil {
		log.Printf("[goproxy] WARNING: cannot open config %s: %v, using defaults", path, err)
		return
	}
	defer f.Close()

	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		// 跳过注释和空行
		if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, ";") {
			continue
		}

		// 支持 "Key Value" 和 "Key=Value" 两种格式
		var key, val string
		if idx := strings.IndexByte(line, '='); idx != -1 {
			key = strings.TrimSpace(line[:idx])
			val = strings.TrimSpace(line[idx+1:])
		} else {
			parts := strings.Fields(line)
			if len(parts) < 2 {
				continue
			}
			key = parts[0]
			val = parts[1]
		}

		switch strings.ToLower(key) {
		case "port":
			*port = val
		case "listen", "bind":
			*bind = val
		}
	}

	log.Printf("[goproxy] config loaded from %s: %s:%s", path, *bind, *port)
}
