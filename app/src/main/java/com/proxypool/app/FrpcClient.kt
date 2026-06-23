package com.proxypool.app

import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class FrpcClient(
    private val serverAddr: String,
    private val serverPort: Int,
    private val token: String,
    private val remotePort: Int,
    private val localPort: Int,
    private val log: (String, String) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val running = AtomicBoolean(false)
    private var runID: String = ""
    private var controlSocket: Socket? = null
    private var heartbeatJob: Job? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        log("frpc", "connecting to $serverAddr:$serverPort …")
        scope.launch {
            try { connectAndLogin() }
            catch (e: Exception) { log("frpc", "✗ fatal: ${e.message}"); stop() }
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        log("frpc", "stopping…")
        heartbeatJob?.cancel()
        try { controlSocket?.close() } catch (_: Exception) {}
        scope.cancel()
        log("frpc", "stopped")
    }

    fun isRunning(): Boolean = running.get()

    private fun authKey(ts: Long): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest("$token$ts".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun writeMsg(type: Byte, json: String, out: DataOutputStream) {
        val body = json.toByteArray(Charsets.UTF_8)
        out.write(type.toInt())
        out.writeLong(body.size.toLong())
        out.write(body)
        out.flush()
    }

    private class Msg(val type: Byte, val json: String)

    private fun readMsg(input: DataInputStream): Msg? {
        val t = input.read()
        if (t < 0) { log("frpc", "connection closed"); return null }
        val len = input.readLong()
        if (len < 0 || len > 10_485_760) throw IOException("bad msg len=$len")
        val payload = ByteArray(len.toInt())
        input.readFully(payload)
        return Msg(t.toByte(), String(payload, Charsets.UTF_8))
    }

    private suspend fun connectAndLogin() {
        val sock = Socket(serverAddr, serverPort)
        controlSocket = sock
        sock.soTimeout = 0
        log("frpc", "✓ TCP connected")

        val out = DataOutputStream(sock.getOutputStream().buffered())
        val input = DataInputStream(sock.getInputStream().buffered())

        val ts = System.currentTimeMillis() / 1000
        writeMsg('o'.code.toByte(), JSONObject().apply {
            put("version", "0.61.0")
            put("hostname", "android-proxy")
            put("os", "linux")
            put("arch", "arm64")
            put("user", "")
            put("privilege_key", authKey(ts))
            put("timestamp", ts)
            put("run_id", "")
            put("pool_count", 1)
        }.toString(), out)

        val lr = readMsg(input) ?: throw IOException("login: no response")
        if (lr.type != '1'.code.toByte()) throw IOException("login: unexpected type ${lr.type.toInt().toChar()}")
        val lrj = JSONObject(lr.json)
        val err = lrj.optString("error", "")
        if (err.isNotEmpty()) throw IOException("login rejected: $err")
        runID = lrj.optString("run_id", "")
        log("frpc", "✓ logged in, runID=$runID")

        writeMsg('p'.code.toByte(), JSONObject().apply {
            put("proxy_name", "phone_proxy")
            put("proxy_type", "tcp")
            put("remote_port", remotePort)
            put("use_encryption", false)
            put("use_compression", false)
        }.toString(), out)

        val pr = readMsg(input) ?: throw IOException("newproxy: no response")
        if (pr.type != '2'.code.toByte()) throw IOException("newproxy: unexpected type ${pr.type.toInt().toChar()}")
        val prj = JSONObject(pr.json)
        val pErr = prj.optString("error", "")
        if (pErr.isNotEmpty()) throw IOException("proxy rejected: $pErr")
        log("frpc", "✓ tunnel up on VPS:$remotePort → local:$localPort")

        heartbeatJob = scope.launch { heartbeatLoop(out) }
        messageLoop(input)
    }

    private suspend fun heartbeatLoop(out: DataOutputStream) {
        while (running.get()) {
            delay(30_000)
            try {
                val ts = System.currentTimeMillis() / 1000
                writeMsg('h'.code.toByte(), JSONObject().apply {
                    put("privilege_key", authKey(ts))
                    put("timestamp", ts)
                }.toString(), out)
            } catch (e: Exception) {
                if (running.get()) { log("frpc", "heartbeat error: ${e.message}"); stop() }
                break
            }
        }
    }

    private suspend fun messageLoop(input: DataInputStream) {
        try {
            while (running.get()) {
                val msg = readMsg(input) ?: break
                when (msg.type) {
                    'r'.code.toByte() -> handleReqWorkConn()
                    '4'.code.toByte() -> { /* Pong */ }
                    else -> log("frpc", "← msg type: ${msg.type.toInt().toChar()}")
                }
            }
        } catch (e: Exception) {
            if (running.get()) { log("frpc", "control disconnected: ${e.message}"); stop() }
        }
    }

    private fun handleReqWorkConn() {
        scope.launch {
            try {
                val workSock = Socket(serverAddr, serverPort)
                val wOut = DataOutputStream(workSock.getOutputStream().buffered())
                val wIn = DataInputStream(workSock.getInputStream().buffered())

                val ts = System.currentTimeMillis() / 1000
                writeMsg('w'.code.toByte(), JSONObject().apply {
                    put("run_id", runID)
                    put("privilege_key", authKey(ts))
                    put("timestamp", ts)
                }.toString(), wOut)

                val sm = readMsg(wIn) ?: return@launch
                if (sm.type != 's'.code.toByte()) { workSock.close(); return@launch }
                val smj = JSONObject(sm.json)
                if (smj.optString("error", "").isNotEmpty()) { workSock.close(); return@launch }

                log("frpc", "✓ work conn → bridging to :$localPort")
                bridgeWorkConn(workSock)
            } catch (e: Exception) {
                log("frpc", "work conn error: ${e.message}")
            }
        }
    }

    // 修复：两个方向都 join，任意一方断开就关闭双方
    private suspend fun bridgeWorkConn(workSock: Socket) {
        try {
            val localSock = Socket("127.0.0.1", localPort)
            try {
                coroutineScope {
                    val j1 = launch(Dispatchers.IO) {
                        try { workSock.getInputStream().copyTo(localSock.getOutputStream()) } catch (_: Exception) {}
                        finally { try { localSock.close() } catch (_: Exception) {} }
                    }
                    val j2 = launch(Dispatchers.IO) {
                        try { localSock.getInputStream().copyTo(workSock.getOutputStream()) } catch (_: Exception) {}
                        finally { try { workSock.close() } catch (_: Exception) {} }
                    }
                    // 任意一方结束，cancel 整个 coroutineScope
                    j1.invokeOnCompletion { j2.cancel() }
                    j2.invokeOnCompletion { j1.cancel() }
                }
            } finally {
                try { localSock.close() } catch (_: Exception) {}
            }
        } finally {
            try { workSock.close() } catch (_: Exception) {}
        }
    }
}
