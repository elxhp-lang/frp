package com.proxypool.app

import kotlinx.coroutines.*
import java.io.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal HTTP/HTTPS forward proxy.
 * Accepts connections on 127.0.0.1:$port, forwards to the internet
 * using the phone's own network.
 *
 * Handles:
 *   CONNECT host:port  → HTTPS tunnel (TCP bridge)
 *   GET/POST/...       → plain HTTP relay
 */
class ProxyServer(
    private val port: Int = 7890,
    private val log: (String, String) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                serverSocket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
                log("proxy", "listening on 127.0.0.1:$port")
                while (running.get()) {
                    try {
                        val client = serverSocket!!.accept()
                        launch { handleClient(client) }
                    } catch (e: Exception) {
                        if (running.get()) log("proxy", "accept error: ${e.message}")
                        break
                    }
                }
            } catch (e: Exception) {
                if (running.get()) log("proxy", "start error: ${e.message}")
            }
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try { serverSocket?.close() } catch (_: Exception) {}
        scope.cancel()
        log("proxy", "stopped")
    }

    fun isRunning(): Boolean = running.get()

    private suspend fun handleClient(client: Socket) = withContext(Dispatchers.IO) {
        try {
            client.soTimeout = 30_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val firstLine = reader.readLine() ?: run { client.close(); return@withContext }

            if (firstLine.startsWith("CONNECT ")) {
                handleConnect(client, reader, firstLine)
            } else {
                handleHttp(client, reader, firstLine)
            }
        } catch (e: Exception) {
            // log("proxy", "client error: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    // ── CONNECT (HTTPS tunnel) ──

    private suspend fun handleConnect(client: Socket, reader: BufferedReader, firstLine: String) {
        val parts = firstLine.split(" ")
        if (parts.size < 2) return
        val hostPort = parts[1]
        val colon = hostPort.lastIndexOf(':')
        if (colon < 0) return
        val host = hostPort.substring(0, colon)
        val port = hostPort.substring(colon + 1).toIntOrNull() ?: 443

        // Skip remaining headers
        while (reader.readLine()?.isNotEmpty() == true) { /* skip */ }

        val remote = try {
            Socket(host, port)
        } catch (e: Exception) {
            client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
            return
        }

        client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
        client.getOutputStream().flush()

        // Bi-directional bridge
        coroutineScope {
            val up = launch(Dispatchers.IO) { client.getInputStream().copyTo(remote.getOutputStream()) }
            val down = launch(Dispatchers.IO) { remote.getInputStream().copyTo(client.getOutputStream()) }
            up.join()
            down.cancel()
        }
        try { remote.close() } catch (_: Exception) {}
    }

    // ── Plain HTTP relay ──

    private suspend fun handleHttp(client: Socket, reader: BufferedReader, firstLine: String) {
        val headers = mutableListOf<String>(firstLine)
        var host = ""
        var port = 80

        var line = reader.readLine()
        while (!line.isNullOrEmpty()) {
            headers.add(line)
            if (line.startsWith("Host:", ignoreCase = true)) {
                val h = line.substring(5).trim()
                val colon = h.lastIndexOf(':')
                if (colon > 0) {
                    host = h.substring(0, colon)
                    port = h.substring(colon + 1).toIntOrNull() ?: 80
                } else {
                    host = h
                }
            }
            line = reader.readLine()
        }

        if (host.isEmpty()) return

        val remote = try {
            Socket(host, port)
        } catch (e: Exception) {
            client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
            return
        }

        val remoteOut = BufferedOutputStream(remote.getOutputStream())
        // Write request line + headers
        for (h in headers) {
            remoteOut.write(h.toByteArray())
            remoteOut.write("\r\n".toByteArray())
        }
        remoteOut.write("\r\n".toByteArray())

        // Read & forward body (Content-Length based)
        val contentLength = headers.find { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()?.toIntOrNull() ?: 0
        if (contentLength > 0) {
            val body = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = reader.read(body, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            remoteOut.write(String(body, 0, read).toByteArray())
        }
        remoteOut.flush()

        // Relay response back
        withContext(Dispatchers.IO) {
            try {
                remote.getInputStream().copyTo(client.getOutputStream())
            } catch (_: Exception) {}
        }
        try { remote.close() } catch (_: Exception) {}
    }
}
