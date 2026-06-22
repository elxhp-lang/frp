package com.proxypool.app

/**
 * frpc JNI 桥接 — 调用 libfrpc.so
 * .so 放在 app/src/main/jniLibs/arm64-v8a/libfrpc.so
 */
object FrpcBridge {
    init {
        System.loadLibrary("frpc")
    }

    /** 启动 frpc，传入配置文件路径，返回 0 成功 / -1 失败 */
    external fun StartFrpc(configPath: String): Int

    /** 停止 frpc */
    external fun StopFrpc(): Int

    /** frpc 是否在运行，1=运行中 / 0=未运行 */
    external fun IsFrpcRunning(): Int

    /** 将配置内容写入文件，返回 0 成功 / -1 失败 */
    external fun FrpcWriteConfig(path: String, content: String): Int
}
