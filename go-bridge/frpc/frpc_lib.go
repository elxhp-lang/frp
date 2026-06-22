// frpc_lib.go — frpc 编译为 Android .so (JNI 桥接)
// 编译: CC=$NDK/.../aarch64-linux-android21-clang \
//         CGO_ENABLED=1 GOOS=android GOARCH=arm64 \
//         go build -buildmode=c-shared -o ../app/src/main/jniLibs/arm64-v8a/libfrpc.so .

package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"os"
	"sync"

	"github.com/fatedier/frp/cmd/frpc/sub"
	"github.com/fatedier/frp/pkg/util/log"
)

var frpcMu sync.Mutex

//export StartFrpc
func StartFrpc(configPath *C.char) C.int {
	frpcMu.Lock()
	defer frpcMu.Unlock()

	path := C.GoString(configPath)
	log.Infof("JNI: StartFrpc called with config=%s", path)

	// StartClient 是非阻塞的（内部 go func）
	if err := sub.StartClient(path); err != nil {
		log.Errorf("JNI: StartFrpc failed: %v", err)
		return -1
	}
	return 0
}

//export StopFrpc
func StopFrpc() C.int {
	sub.StopClient()
	return 0
}

//export IsFrpcRunning
func IsFrpcRunning() C.int {
	return C.int(sub.IsRunning())
}

//export FrpcWriteConfig
func FrpcWriteConfig(pathC, contentC *C.char) C.int {
	path := C.GoString(pathC)
	content := C.GoString(contentC)
	if err := os.WriteFile(path, []byte(content), 0644); err != nil {
		return -1
	}
	return 0
}

func main() {}
