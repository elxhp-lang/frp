// goproxy-lib — Go HTTP forward proxy 编译为 Android .so
// 编译: CGO_ENABLED=1 GOOS=android GOARCH=arm64 \
//         CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang \
//         go build -buildmode=c-shared -o ../app/src/main/jniLibs/arm64-v8a/libgoproxy.so .

package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"
)

var (
	proxyMu    sync.Mutex
	proxySrv   *http.Server
	proxyDone  chan struct{}
	logWriter  io.WriteCloser // Android 侧可读的日志管道
)

func init() {
	// 默认日志输出到 stderr（logcat 可见），App 可通过 SetLogPipe 接管
	logPipe = os.Stderr
}

//export StartGoproxy
func StartGoproxy(configC *C.char) C.int {
	config := C.GoString(configC)

	proxyMu.Lock()
	if proxySrv != nil {
		proxyMu.Unlock()
		fmt.Fprintf(logPipe, "[goproxy] already running\n")
		return 0
	}

	bind := "127.0.0.1"
	port := "7890"

	// 解析 tinyproxy 兼容配置
	for _, line := range strings.Split(config, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, ";") {
			continue
		}
		parts := strings.Fields(line)
		if len(parts) < 2 {
			continue
		}
		switch strings.ToLower(parts[0]) {
		case "port":
			port = parts[1]
		case "listen", "bind":
			bind = parts[1]
		}
	}

	addr := net.JoinHostPort(bind, port)
	handler := &proxyHandler{
		dialer: &net.Dialer{
			Timeout:   30 * time.Second,
			KeepAlive: 30 * time.Second,
		},
	}

	proxySrv = &http.Server{
		Addr:         addr,
		Handler:      handler,
		ReadTimeout:  30 * time.Second,
		WriteTimeout: 30 * time.Second,
		IdleTimeout:  120 * time.Second,
		TLSNextProto: make(map[string]func(*http.Server, *tls.Conn, http.Handler)),
	}
	proxyDone = make(chan struct{})
	proxyMu.Unlock()

	go func() {
		fmt.Fprintf(logPipe, "[goproxy] HTTP proxy listening on %s\n", addr)
		if err := proxySrv.ListenAndServe(); err != http.ErrServerClosed {
			fmt.Fprintf(logPipe, "[goproxy] listen error: %v\n", err)
		}
		close(proxyDone)
	}()

	return 0
}

//export StopGoproxy
func StopGoproxy() C.int {
	proxyMu.Lock()
	srv := proxySrv
	done := proxyDone
	proxySrv = nil
	proxyDone = nil
	proxyMu.Unlock()

	if srv == nil {
		fmt.Fprintf(logPipe, "[goproxy] not running\n")
		return 0
	}

	fmt.Fprintf(logPipe, "[goproxy] shutting down...\n")
	srv.Close()
	if done != nil {
		<-done
	}
	fmt.Fprintf(logPipe, "[goproxy] shutdown complete\n")
	return 0
}

//export SetGoproxyLogPipe
func SetGoproxyLogPipe(fd C.int) {
	file := os.NewFile(uintptr(fd), "goproxy_log")
	if file != nil {
		logPipe = file
	}
}

//export IsGoproxyRunning
func IsGoproxyRunning() C.int {
	proxyMu.Lock()
	defer proxyMu.Unlock()
	if proxySrv != nil {
		return 1
	}
	return 0
}

// ── 代理逻辑（与 main.go 一致） ──

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

func (h *proxyHandler) handleConnect(w http.ResponseWriter, r *http.Request) {
	fmt.Fprintf(logPipe, "[goproxy] CONNECT %s\n", r.Host)

	dst, err := h.dialer.Dial("tcp", r.Host)
	if err != nil {
		fmt.Fprintf(logPipe, "[goproxy] CONNECT %s FAILED: %v\n", r.Host, err)
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}

	hijacker, ok := w.(http.Hijacker)
	if !ok {
		fmt.Fprintf(logPipe, "[goproxy] CONNECT %s: hijacking not supported\n", r.Host)
		http.Error(w, "hijacking not supported", http.StatusInternalServerError)
		dst.Close()
		return
	}

	clientConn, _, err := hijacker.Hijack()
	if err != nil {
		fmt.Fprintf(logPipe, "[goproxy] CONNECT %s: hijack failed: %v\n", r.Host, err)
		http.Error(w, err.Error(), http.StatusServiceUnavailable)
		dst.Close()
		return
	}

	clientConn.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n"))
	fmt.Fprintf(logPipe, "[goproxy] CONNECT %s ESTABLISHED\n", r.Host)

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

func (h *proxyHandler) handleHTTP(w http.ResponseWriter, r *http.Request) {
	removeHopByHop(r.Header)

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

	transport := &http.Transport{
		DialContext:           h.dialer.DialContext,
		MaxIdleConns:          100,
		IdleConnTimeout:       90 * time.Second,
		TLSHandshakeTimeout:   10 * time.Second,
		ExpectContinueTimeout: 1 * time.Second,
	}

	resp, err := transport.RoundTrip(outReq)
	if err != nil {
		fmt.Fprintf(logPipe, "[goproxy] %s %s FAILED: %v\n", r.Method, r.RequestURI, err)
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

	removeHopByHop(resp.Header)
	for k, vv := range resp.Header {
		for _, v := range vv {
			w.Header().Add(k, v)
		}
	}

	w.WriteHeader(resp.StatusCode)
	written, _ := io.Copy(w, resp.Body)
	fmt.Fprintf(logPipe, "[goproxy] %s %s -> %d (%d bytes)\n", r.Method, r.RequestURI, resp.StatusCode, written)
}

func removeHopByHop(h http.Header) {
	for _, k := range []string{
		"Connection", "Keep-Alive", "Proxy-Authenticate",
		"Proxy-Authorization", "TE", "Trailers",
		"Transfer-Encoding", "Upgrade", "Proxy-Connection",
	} {
		h.Del(k)
	}
}

var logPipe io.Writer

func main() {}
