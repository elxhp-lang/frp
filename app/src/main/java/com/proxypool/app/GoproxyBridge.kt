package com.proxypool.app

/**
 * goproxy JNI 桥接 — 调用 libgoproxy.so
 * .so 放在 app/src/main/jniLibs/arm64-v8a/libgoproxy.so
 */
object GoproxyBridge {
    init {
        System.loadLibrary("goproxy")
    }

    /** 启动 HTTP 代理，传入 tinyproxy 兼容格式的配置 */
    external fun StartGoproxy(config: String): Int

    /** 停止代理 */
    external fun StopGoproxy(): Int

    /** 代理是否在运行，1=运行中 / 0=未运行 */
    external fun IsGoproxyRunning(): Int
}
