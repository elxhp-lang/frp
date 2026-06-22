package com.proxypool.app

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.io.*
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure Kotlin frp client — implements frp wire protocol (golib msg/json).
 *
 * Wire format:
 *   [1 byte: type] [8 bytes: int64 big-endian jsonLen] [json bytes]
 *
 * Message types:
 *   'o' Login      '1' LoginResp   'p' NewProxy
 *   '2' NewProxyResp  'h' Ping    '4' Pong
 *   'w' NewWorkConn  'r' ReqWorkConn  's' StartWorkConn
 */
class FrpcClient(
    private val serverAddr: String,
    private val serverPort: Int,
    private val token: String,
    private val remotePort: Int,
    private val localPort: Int,   // goproxy listen port
    private val log: (String, String) -> Unit  // (tag, line) logger
) {
    // ── state ──
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val running = AtomicBoolean(false)
    private var runID: String = ""
    private var controlSocket: Socket? = null
    private var controlOutput: DataOutputStream? = null
    private var heartbeatJob: Job? = null
    private val workConnections = ConcurrentHashMap<String, Job>()

    // ── public API ──

    fun start() {
        if (!running.compareAndSet(false, true)) return
        log("frpc", "connecting to $serverAddr:$serverPort …")

        scope.launch {
            try {
                connectAndLogin()
            } catch (e: Exception) {
                log("frpc", "✗ fatal: ${e.message}")
                stop()
            }
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        log("frpc", "stopping…")
        heartbeatJob?.cancel()
        workConnections.values.forEach { it.cancel() }
        try { controlSocket?.close() } catch (_: Exception) {}
        scope.cancel()
        log("frpc", "stopped")
    }

    fun isRunning(): Boolean = running.get()

    // ── auth key (MD5(token + timestamp)) ──

    private fun getAuthKey(ts: Long): String {
        val md = MessageDigest.getInstance("MD5")
        val input = "$token$ts".toByteArray(Charsets.UTF_8)
        return md.digest(input).joinToString("") { "%02x".format(it) }
    }

    // ── V1 wire encoding (golib msg/json format) ──
    // Format: [1 byte type] [8 bytes int64 big-endian jsonLen] [json bytes]

    private fun writeMsg(type: Byte, json: String, out: DataOutputStream) {
        val body = json.toByteArray(Charsets.UTF_8)
        out.write(type.toInt())           // 1 byte: type
        out.writeLong(body.size.toLong())  // 8 bytes: int64 big-endian json length
        out.write(body)                    // json bytes
        out.flush()
    }

    private class ParsedMsg(val type: Byte, val json: String)

    private fun readMsg(input: DataInputStream): ParsedMsg? {
        val typeByte = input.read()        // 1 byte: type
        if (typeByte < 0) return null      // EOF
        val jsonLen = input.readLong()     // 8 bytes: int64 big-endian length
        if (jsonLen < 0 || jsonLen > 10_485_760) { // sanity: max 10MB
            throw IOException("readMsg: bad jsonLen=$jsonLen")
        }
        val payload = ByteArray(jsonLen.toInt())
        input.readFully(payload)
        val json = String(payload, Charsets.UTF_8)
        return ParsedMsg(typeByte.toByte(), json)
    }

    // ── login + proxy registration ──

    private suspend fun connectAndLogin() {
        val sock = Socket(serverAddr, serverPort)
        controlSocket = sock
        sock.soTimeout = 0  // no timeout for control
        val out = DataOutputStream(sock.getOutputStream().buffered())
        val input = DataInputStream(sock.getInputStream().buffered())
        controlOutput = out

        // Step 1: Login
        val ts = System.currentTimeMillis() / 1000
        val loginJson = JSONObject().apply {
            put("version", "0.61.0-kotlin")
            put("hostname", "android-proxy")
            put("os", "android")
            put("arch", "arm64")
            put("user", "")
            put("privilege_key", getAuthKey(ts))
            put("timestamp", ts)
            put("run_id", "")     // first login
            put("pool_count", 1)
        }.toString()
        log("frpc", "→ Login")
        writeMsg('o'.code.toByte(), loginJson, out)

        val loginResp = readMsg(input) ?: throw IOException("login: no response")
        if (loginResp.type != '1'.code.toByte()) throw IOException("login: unexpected type ${loginResp.type.toInt().toChar()}")
        val lr = JSONObject(loginResp.json)
        val error = lr.optString("error", "")
        if (error.isNotEmpty()) throw IOException("login rejected: $error")
        runID = lr.optString("run_id", "")
        log("frpc", "✓ logged in, runID=$runID")

        // Step 2: NewProxy
        val proxyJson = JSONObject().apply {
            put("proxy_name", "phone_pool")
            put("proxy_type", "tcp")
            put("remote_port", remotePort)
        }.toString()
        log("frpc", "→ NewProxy remotePort=$remotePort")
        writeMsg('p'.code.toByte(), proxyJson, out)

        val proxyResp = readMsg(input) ?: throw IOException("newproxy: no response")
        if (proxyResp.type != '2'.code.toByte()) throw IOException("newproxy: unexpected type ${proxyResp.type.toInt().toChar()}")
        val pr = JSONObject(proxyResp.json)
        val pError = pr.optString("error", "")
        if (pError.isNotEmpty()) throw IOException("proxy rejected: $pError")
        log("frpc", "✓ proxy registered, addr=${pr.optString("remote_addr")}")

        // Step 3: Start heartbeat & message loop
        heartbeatJob = scope.launch { heartbeatLoop(out) }
        messageLoop(input)
    }

    // ── heartbeat ──

    private suspend fun heartbeatLoop(out: DataOutputStream) {
        while (running.get()) {
            delay(30_000)  // every 30s
            try {
                val ts = System.currentTimeMillis() / 1000
                val pingJson = JSONObject().apply {
                    put("privilege_key", getAuthKey(ts))
                    put("timestamp", ts)
                }.toString()
                writeMsg('h'.code.toByte(), pingJson, out)
            } catch (e: Exception) {
                if (running.get()) {
                    log("frpc", "heartbeat error: ${e.message}")
                    stop()
                }
                break
            }
        }
    }

    // ── control message loop ──

    private suspend fun messageLoop(input: DataInputStream) {
        try {
            while (running.get()) {
                val msg = readMsg(input) ?: break
                when (msg.type) {
                    'r'.code.toByte() -> handleReqWorkConn()
                    '4'.code.toByte() -> { /* Pong — ok */ }
                    else -> log("frpc", "← unknown msg type: ${msg.type.toInt().toChar()}")
                }
            }
        } catch (e: Exception) {
            if (running.get()) {
                log("frpc", "control disconnected: ${e.message}")
                stop()
            }
        }
    }

    // ── work connection handling ──

    private fun handleReqWorkConn() {
        log("frpc", "← ReqWorkConn, opening tunnel…")
        scope.launch {
            try {
                val workSocket = Socket(serverAddr, serverPort)
                val workOut = DataOutputStream(workSocket.getOutputStream().buffered())
                val workIn = DataInputStream(workSocket.getInputStream().buffered())

                // Send NewWorkConn
                val ts = System.currentTimeMillis() / 1000
                val nwcJson = JSONObject().apply {
                    put("run_id", runID)
                    put("privilege_key", getAuthKey(ts))
                    put("timestamp", ts)
                }.toString()
                writeMsg('w'.code.toByte(), nwcJson, workOut)

                // Read StartWorkConn
                val startMsg = readMsg(workIn) ?: return@launch
                if (startMsg.type != 's'.code.toByte()) {
                    log("frpc", "✗ work conn: unexpected type ${startMsg.type.toInt().toChar()}")
                    workSocket.close()
                    return@launch
                }
                val sw = JSONObject(startMsg.json)
                val swError = sw.optString("error", "")
                if (swError.isNotEmpty()) {
                    log("frpc", "✗ StartWorkConn error: $swError")
                    workSocket.close()
                    return@launch
                }
                log("frpc", "✓ work conn established for ${sw.optString("proxy_name")}")

                // Bridge: frp work connection ↔ local goproxy
                bridgeWorkConn(workSocket, sw.optString("proxy_name", "unknown"))
            } catch (e: Exception) {
                log("frpc", "work conn error: ${e.message}")
            }
        }
    }

    // ── TCP bridge: work connection → 127.0.0.1:localPort ──

    private suspend fun bridgeWorkConn(workSocket: Socket, proxyName: String) = coroutineScope {
        try {
            val localSocket = Socket("127.0.0.1", localPort)
            log("frpc", "bridging $proxyName → :$localPort")

            val j1 = launch { workSocket.getInputStream().copyTo(localSocket.getOutputStream()) }
            val j2 = launch { localSocket.getInputStream().copyTo(workSocket.getOutputStream()) }

            j1.join()
            j2.cancel()
            localSocket.close()
        } catch (e: Exception) {
            log("frpc", "bridge broken: ${e.message}")
        } finally {
            try { workSocket.close() } catch (_: Exception) {}
        }
    }
}
