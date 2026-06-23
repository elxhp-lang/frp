package com.proxypool.app

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 隧道客户端 v2.0 — HTTP 请求 + CONNECT 中继
 *
 * 单条 TCP 长连接承载：
 * - HTTP REQUEST/RESPONSE（原有流程）
 * - CONNECT 中继（SOCKS5 / HTTP CONNECT → 手机做 TCP 连接 + 双向字节流转发）
 *
 * 协议: 4字节大端长度前缀 + JSON
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
        private const val READ_TIMEOUT = 15
        private const val RELAY_BUF_SIZE = 65536
    }

    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    private var input: DataInputStream? = null

    @Volatile private var running = false
    @Volatile var isConnected = false
    @Volatile var assignedPort: Int = 0
    private var job: Job? = null

    // CONNECT 中继状态: connect_id → RelaySession
    private val relays = ConcurrentHashMap<String, RelaySession>()

    private data class RelaySession(
        val target: Socket,
        val targetIn: InputStream,
        val targetOut: OutputStream,
        val job: Job
    )

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
        // 关闭所有中继
        relays.values.forEach { cleanupRelay(it) }
        relays.clear()
        log(TAG, "stopped")
    }

    // ─────── 主循环（含自动重连） ───────

    private suspend fun runLoop() {
        var waitSec = RECONNECT_MIN

        while (running && scope.isActive) {
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

    private suspend fun connect() = withContext(Dispatchers.IO) {
        log(TAG, "连接 $vpsAddr:$tunnelPort")
        val sock = Socket()
        sock.tcpNoDelay = true
        sock.soTimeout = READ_TIMEOUT * 1000
        sock.connect(InetSocketAddress(vpsAddr, tunnelPort), 10_000)

        socket = sock
        output = DataOutputStream(sock.getOutputStream())
        input = DataInputStream(sock.getInputStream())

        val auth = JSONObject().apply {
            put("type", "AUTH")
            put("phone_id", phoneId)
            put("token", token)
            put("version", "2.0")
        }
        sendMsg(auth)

        val resp = recvMsg() ?: throw Exception("认证无响应")
        if (resp.optString("type") != "HELLO") {
            throw Exception("认证失败: ${resp.optString("message", "unknown")}")
        }

        assignedPort = resp.optInt("port", 0)
        isConnected = true
        log(TAG, "✓ tunnel up  assigned :$assignedPort  v2.0")
    }

    // ─────── 消息处理 ───────

    private suspend fun handleMessages() {
        while (running && scope.isActive) {
            val msg = recvMsg()
            if (msg == null) {
                sendMsg(JSONObject().apply { put("type", "HEARTBEAT") })
                continue
            }

            when (msg.optString("type")) {
                "REQUEST"   -> handleRequest(msg)
                "CONNECT"   -> handleConnect(msg)
                "DATA"      -> handleData(msg)
                "CLOSE"     -> handleClose(msg)
            }
        }
    }

    // ─────── HTTP REQUEST ───────

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

    // ─────── CONNECT 中继 ───────

    private fun handleConnect(msg: JSONObject) {
        val connectId = msg.optString("connect_id") ?: return
        val host = msg.optString("host") ?: return
        val port = msg.optInt("port", 443)

        scope.launch(Dispatchers.IO) {
            try {
                log(TAG, "CONNECT $host:$port")

                val target = Socket()
                target.tcpNoDelay = true
                target.soTimeout = 30000
                target.connect(InetSocketAddress(host, port), 10_000)

                val targetIn = target.getInputStream()
                val targetOut = target.getOutputStream()

                // 回复 CONNECTED
                sendMsg(JSONObject().apply {
                    put("type", "CONNECTED")
                    put("connect_id", connectId)
                    put("status", "ok")
                })

                // 启动双向中继
                val relayJob = scope.launch {
                    // 读取目标服务器数据 → 发往 VPS
                    launch(Dispatchers.IO) {
                        relayFromTarget(connectId, targetIn)
                    }
                    // 读取 VPS 数据 → 写入目标（在 handleData 中处理）
                }

                relays[connectId] = RelaySession(target, targetIn, targetOut, relayJob)

            } catch (e: Exception) {
                log(TAG, "CONNECT failed: $host:$port - ${e.message}")
                sendMsg(JSONObject().apply {
                    put("type", "CONNECTED")
                    put("connect_id", connectId)
                    put("status", "error")
                    put("error", e.message ?: "connect failed")
                })
            }
        }
    }

    private suspend fun relayFromTarget(connectId: String, targetIn: InputStream) {
        val buf = ByteArray(RELAY_BUF_SIZE)
        try {
            while (running && scope.isActive && relays.containsKey(connectId)) {
                val n = withContext(Dispatchers.IO) { targetIn.read(buf) }
                if (n < 0) {
                    // EOF → 通知 VPS 关闭
                    sendMsg(JSONObject().apply {
                        put("type", "CLOSE")
                        put("connect_id", connectId)
                    })
                    break
                }
                if (n == 0) continue

                val chunk = buf.copyOf(n)
                sendMsg(JSONObject().apply {
                    put("type", "DATA")
                    put("connect_id", connectId)
                    put("data_hex", chunk.joinToString("") { "%02x".format(it) })
                })
            }
        } catch (e: Exception) {
            if (relays.containsKey(connectId)) {
                log(TAG, "relay target→vps error: ${e.message}")
                try {
                    sendMsg(JSONObject().apply {
                        put("type", "CLOSE")
                        put("connect_id", connectId)
                    })
                } catch (_: Exception) {}
            }
        } finally {
            relays.remove(connectId)?.let { cleanupRelay(it) }
        }
    }

    private fun handleData(msg: JSONObject) {
        val connectId = msg.optString("connect_id") ?: return
        val dataHex = msg.optString("data_hex", "")
        if (dataHex.isEmpty()) return

        val relay = relays[connectId] ?: return
        val data = hexToBytes(dataHex)

        scope.launch(Dispatchers.IO) {
            try {
                relay.targetOut.write(data)
                relay.targetOut.flush()
            } catch (e: Exception) {
                log(TAG, "relay vps→target write error: ${e.message}")
                relays.remove(connectId)?.let { cleanupRelay(it) }
            }
        }
    }

    private fun handleClose(msg: JSONObject) {
        val connectId = msg.optString("connect_id") ?: return
        relays.remove(connectId)?.let { cleanupRelay(it) }
    }

    private fun cleanupRelay(relay: RelaySession) {
        relay.job.cancel()
        try { relay.targetIn.close() } catch (_: Exception) {}
        try { relay.targetOut.close() } catch (_: Exception) {}
        try { relay.target.close() } catch (_: Exception) {}
    }

    // ─────── HTTP 请求构建 ───────

    private fun buildRequest(method: String, url: String, headers: JSONObject?, bodyHex: String): Request {
        val builder = Request.Builder().url(url)

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
            val bytes = hexToBytes(bodyHex)
            val ct = headers?.optString("content-type") ?: "application/octet-stream"
            builder.method(method, bytes.toRequestBody(ct.toMediaTypeOrNull()))
        } else if (method in listOf("POST", "PUT", "PATCH")) {
            builder.method(method, ByteArray(0).toRequestBody())
        } else {
            builder.method(method, null)
        }

        return builder.build()
    }

    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
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
