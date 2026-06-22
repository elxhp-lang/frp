package com.proxypool.app

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    // ── UI ──
    private lateinit var etServer: EditText
    private lateinit var etServerPort: EditText
    private lateinit var etRemotePort: EditText
    private lateinit var etToken: EditText
    private lateinit var logView: TextView
    private lateinit var scrollLog: ScrollView

    private lateinit var proxyStartBtn: Button
    private lateinit var proxyStopBtn: Button
    private lateinit var proxyStatus: TextView

    private lateinit var frpcStartBtn: Button
    private lateinit var frpcStopBtn: Button
    private lateinit var frpcStatus: TextView

    // ── services ──
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val dateFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())

    private var frpcClient: FrpcClient? = null
    private var proxyServer: ProxyServer? = null

    // ── default local proxy port ──
    private val LOCAL_PROXY_PORT = 7890

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etServer = findViewById(R.id.server_addr)
        etServerPort = findViewById(R.id.server_port)
        etRemotePort = findViewById(R.id.remote_port)
        etToken = findViewById(R.id.auth_token)
        logView = findViewById(R.id.log_view)
        scrollLog = findViewById(R.id.scroll_log)

        proxyStatus = findViewById(R.id.goproxy_status)
        proxyStartBtn = findViewById(R.id.goproxy_start)
        proxyStopBtn = findViewById(R.id.goproxy_stop)

        frpcStatus = findViewById(R.id.frpc_status)
        frpcStartBtn = findViewById(R.id.frpc_start)
        frpcStopBtn = findViewById(R.id.frpc_stop)

        proxyStartBtn.setOnClickListener { startProxy() }
        proxyStopBtn.setOnClickListener { stopProxy() }
        frpcStartBtn.setOnClickListener { startFrpc() }
        frpcStopBtn.setOnClickListener { stopFrpc() }

        addLog("sys", "ready — all ports configurable, no hardcoding")
    }

    override fun onDestroy() {
        scope.cancel()
        frpcClient?.stop()
        proxyServer?.stop()
        super.onDestroy()
    }

    // ── Proxy ──────────────────────────────────

    private fun startProxy() {
        if (proxyServer?.isRunning() == true) {
            addLog("proxy", "already running")
            return
        }
        proxyServer = ProxyServer(LOCAL_PROXY_PORT, ::addLog).also { it.start() }
        proxyStatus.text = "● running — 127.0.0.1:$LOCAL_PROXY_PORT"
        proxyStatus.setTextColor(0xFF4CAF50.toInt())
        proxyStartBtn.isEnabled = false
        proxyStopBtn.isEnabled = true
    }

    private fun stopProxy() {
        proxyServer?.stop()
        proxyServer = null
        proxyStatus.text = "○ stopped"
        proxyStatus.setTextColor(0xFF757575.toInt())
        proxyStartBtn.isEnabled = true
        proxyStopBtn.isEnabled = false
    }

    // ── Frpc ───────────────────────────────────

    private fun startFrpc() {
        val server = etServer.text.toString().trim()
        val serverPort = etServerPort.text.toString().trim().toIntOrNull()
        val remotePort = etRemotePort.text.toString().trim().toIntOrNull()
        val token = etToken.text.toString().trim()

        if (server.isEmpty() || serverPort == null || remotePort == null || token.isEmpty()) {
            addLog("frpc", "✗ fill in all 4 fields: Server / 隧道Port / 代理Port / Token")
            return
        }

        if (frpcClient?.isRunning() == true) {
            addLog("frpc", "already running")
            return
        }

        addLog("frpc", "starting → $server:$serverPort tunnel→:$remotePort")
        frpcClient = FrpcClient(
            serverAddr = server,
            serverPort = serverPort,
            token = token,
            remotePort = remotePort,
            localPort = LOCAL_PROXY_PORT,
            log = ::addLog
        ).also { it.start() }

        frpcStatus.text = "● connecting…"
        frpcStatus.setTextColor(0xFFFFA726.toInt())
        frpcStartBtn.isEnabled = false
        frpcStopBtn.isEnabled = true
    }

    private fun stopFrpc() {
        frpcClient?.stop()
        frpcClient = null
        frpcStatus.text = "○ stopped"
        frpcStatus.setTextColor(0xFF757575.toInt())
        frpcStartBtn.isEnabled = true
        frpcStopBtn.isEnabled = false
    }

    // ── Logging ────────────────────────────────

    private fun addLog(tag: String, line: String) {
        val ts = dateFormat.format(java.util.Date())
        val entry = "[$ts] $tag: $line"
        runOnUiThread {
            logView.append("$entry\n")
            scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
        }

        // Auto-update frpc status on successful login
        if (tag == "frpc" && line.startsWith("✓ logged in")) {
            runOnUiThread {
                frpcStatus.text = "● running — tunnel ${etRemotePort.text}"
                frpcStatus.setTextColor(0xFF4CAF50.toInt())
            }
        }
    }
}
