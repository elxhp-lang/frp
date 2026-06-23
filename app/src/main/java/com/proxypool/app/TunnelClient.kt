package com.proxypool.app

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * 隧道客户端 — 替代 FrpcClient
 *
 * 单条 TCP 长连接承载所有 HTTP 请求。
 * 协议: 4字节大端长度前缀 + JSON
 *
 * VPS隧道服务器 IP:PORT 从注册服务获取
 */
class TunnelClient(
    private val vpsAddr: String,
    private val tunnelPort: Int,
    private val phoneId: String,
    private val token: String,
    private val log: (String, String) -> Unit
) {
    companion object {
        private const val TAG = "tunnel"
        private const val RECONNECT_MIN = 1L
        private const val RECONNECT_MAX = 30L
        private const val READ_TIMEOUT = 15  // 15s 无消息则心跳
    }

    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    private var input: DataInputStream? = null

    @Volatile private var running = false
    @Volatile var isConnected = false
    @Volatile var assignedPort: Int = 0
    private var job: Job? = null

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val writeLock = Any()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        if (running) return
        running = true
        job = scope.launch { runLoop() }
    }

    fun stop() {
        running = false
        job?.cancel()
        disconnect()
        log(TAG, "stopped")
    }

    // ─────── 主循环（含自动重连） ───────

    private suspend fun runLoop() {
        var waitSec = RECONNECT_MIN

        while (running && isActive) {
            try {
                connect()
                waitSec = RECONNECT_MIN
                handleMessages()
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                log(TAG, "连接断开: ${e.message}")
            } finally {
                disconnect()
            }

            if (!running) break

            val wait = minOf(waitSec, RECONNECT_MAX)
            log(TAG, "${wait}s 后重连…")
            isConnected = false
            delay(wait * 1000)
            waitSec = minOf(waitSec * 2, RECONNECT_MAX)
        }
    }

    // ─────── 连接与认证 ───────

    private fun connect() = withContext(Dispatchers.IO) {
        log(TAG, "连接 $vpsAddr:$tunnelPort")
        val sock = Socket()
        sock.tcpNoDelay = true
        sock.soTimeout = READ_TIMEOUT * 1000
        sock.connect(InetSocketAddress(vpsAddr, tunnelPort), 10_000)

        socket = sock
        output = DataOutputStream(sock.getOutputStream())
        input = DataInputStream(sock.getInputStream())

        // 认证
        val auth = JSONObject().apply {
            put("type", "AUTH")
            put("phone_id", phoneId)
            put("token", token)
        }
        sendMsg(auth)

        val resp = recvMsg() ?: throw Exception("认证无响应")
        if (resp.optString("type") != "HELLO") {
            throw Exception("认证失败: ${resp.optString("message", "unknown")}")
        }

        assignedPort = resp.optInt("port", 0)
        isConnected = true
        log(TAG, "✓ tunnel up  assigned :$assignedPort")
    }

    // ─────── 消息处理 ───────

    private suspend fun handleMessages() {
        while (running && isActive) {
            val msg = recvMsg()
            if (msg == null) {
                // 超时 → 发心跳
                sendMsg(JSONObject().apply { put("type", "HEARTBEAT") })
                continue
            }

            when (msg.optString("type")) {
                "REQUEST" -> handleRequest(msg)
            }
        }
    }

    private fun handleRequest(msg: JSONObject) {
        val reqId = msg.optString("request_id") ?: return
        val method = msg.optString("method", "GET")
        val url = msg.optString("url") ?: return
        val headers = msg.optJSONObject("headers")
        val bodyHex = msg.optString("body_base64", "")

        scope.launch {
            try {
                val request = buildRequest(method, url, headers, bodyHex)
                val response = okHttp.newCall(request).execute()

                val respMsg = JSONObject().apply {
                    put("type", "RESPONSE")
                    put("request_id", reqId)
                    put("status", response.code)

                    val h = JSONObject()
                    response.headers.toMultimap().forEach { (k, v) ->
                        h.put(k, v.joinToString(", "))
                    }
                    put("headers", h)

                    val body = response.body?.bytes() ?: ByteArray(0)
                    put("body_base64", body.joinToString("") { "%02x".format(it) })

                    response.close()
                }

                sendMsg(respMsg)

            } catch (e: Exception) {
                log(TAG, "HTTP failed: $url - ${e.message}")
                val err = JSONObject().apply {
                    put("type", "RESPONSE")
                    put("request_id", reqId)
                    put("status", 502)
                    put("headers", JSONObject())
                    put("body_base64", (e.message ?: "error").toByteArray()
                        .joinToString("") { "%02x".format(it) })
                }
                try { sendMsg(err) } catch (_: Exception) {}
            }
        }
    }

    private fun buildRequest(method: String, url: String, headers: JSONObject?, bodyHex: String): Request {
        val builder = Request.Builder().url(url)

        // 排除 hop-by-hop 头
        val skip = setOf("host", "connection", "proxy-connection",
                         "transfer-encoding", "keep-alive", "proxy-authorization")
        headers?.let { h ->
            h.keys().forEach { key ->
                if (key.lowercase() !in skip) {
                    builder.addHeader(key, h.getString(key))
                }
            }
        }

        if (bodyHex.isNotEmpty()) {
            val bytes = bodyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val ct = headers?.optString("content-type") ?: "application/octet-stream"
            builder.method(method, bytes.toRequestBody(ct.toMediaTypeOrNull()))
        } else if (method in listOf("POST", "PUT", "PATCH")) {
            builder.method(method, ByteArray(0).toRequestBody())
        } else {
            builder.method(method, null)
        }

        return builder.build()
    }

    // ─────── 协议序列化 ───────

    private fun sendMsg(json: JSONObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val header = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(bytes.size).array()
        synchronized(writeLock) {
            output?.write(header)
            output?.write(bytes)
            output?.flush()
        }
    }

    private fun recvMsg(): JSONObject? {
        return try {
            val header = ByteArray(4)
            input?.readFully(header) ?: return null
            val len = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).int
            if (len <= 0 || len > 10 * 1024 * 1024) return null
            val body = ByteArray(len)
            input?.readFully(body) ?: return null
            JSONObject(String(body, Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    private fun disconnect() {
        try { output?.close() } catch (_: Exception) {}
        try { input?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null; output = null; input = null
        isConnected = false
    }
}
