// frpc-lib — JNI wrapper that compiles frpc as a shared library (.so)
//
// Build:
//   cd go-bridge/frpc
//   CGO_ENABLED=1 GOOS=android GOARCH=arm64 \
//     CC=$NDK_TOOLCHAIN/bin/aarch64-linux-android21-clang \
//     go build -buildmode=c-shared -o libfrpc.so .

package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"fmt"
	"io"
	"os"

	"github.com/fatedier/frp/cmd/frpc/sub"
	"github.com/fatedier/frp/pkg/util/log"
)

var frpcLogWriter io.Writer = os.Stderr

//export Java_com_proxypool_app_FrpcBridge_SetLogPipe
func Java_com_proxypool_app_FrpcBridge_SetLogPipe(fd C.int) {
	file := os.NewFile(uintptr(fd), "frpc_log")
	if file != nil {
		frpcLogWriter = file
		sub.SetLogWriter(file)
		fmt.Fprintf(file, "[frpc] log pipe connected\n")
	}
}

//export Java_com_proxypool_app_FrpcBridge_StartFrpc
func Java_com_proxypool_app_FrpcBridge_StartFrpc(configPath *C.char) C.int {
	path := C.GoString(configPath)
	fmt.Fprintf(frpcLogWriter, "[frpc] starting with config: %s\n", path)

	if err := sub.StartClient(path); err != nil {
		fmt.Fprintf(frpcLogWriter, "[frpc] start failed: %v\n", err)
		log.Errorf("StartFrpc: %v", err)
		return -1
	}
	return 0
}

//export Java_com_proxypool_app_FrpcBridge_StopFrpc
func Java_com_proxypool_app_FrpcBridge_StopFrpc() C.int {
	fmt.Fprintf(frpcLogWriter, "[frpc] stopping...\n")
	sub.StopClient()
	fmt.Fprintf(frpcLogWriter, "[frpc] stopped\n")
	return 0
}

//export Java_com_proxypool_app_FrpcBridge_IsFrpcRunning
func Java_com_proxypool_app_FrpcBridge_IsFrpcRunning() C.int {
	return C.int(sub.IsRunning())
}

//export Java_com_proxypool_app_FrpcBridge_FrpcWriteConfig
func Java_com_proxypool_app_FrpcBridge_FrpcWriteConfig(path *C.char, content *C.char) C.int {
	if err := os.WriteFile(C.GoString(path), []byte(C.GoString(content)), 0644); err != nil {
		fmt.Fprintf(frpcLogWriter, "[frpc] write config failed: %v\n", err)
		return -1
	}
	fmt.Fprintf(frpcLogWriter, "[frpc] config written: %s\n", C.GoString(path))
	return 0
}

func main() {}
