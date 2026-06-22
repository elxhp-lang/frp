package com.proxypool.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_NOTIFICATION = 1001
    }

    // 配置
    private lateinit var serverAddrInput: EditText
    private lateinit var remotePortInput: EditText
    private lateinit var authTokenInput: EditText
    private lateinit var saveBtn: Button

    // tinyproxy
    private lateinit var tinyStatus: TextView
    private lateinit var tinyStart: Button
    private lateinit var tinyStop: Button

    // frpc
    private lateinit var frpcStatus: TextView
    private lateinit var frpcStart: Button
    private lateinit var frpcStop: Button

    // 日志
    private lateinit var logView: TextView
    private lateinit var scrollLog: ScrollView

    private val logBuilder = StringBuilder()
    private val logReceiver = LogReceiver()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Android 13+ 请求通知权限
        requestNotificationPermission()

        // 配置
        serverAddrInput = findViewById(R.id.server_addr)
        remotePortInput = findViewById(R.id.remote_port)
        authTokenInput = findViewById(R.id.auth_token)
        saveBtn = findViewById(R.id.save_btn)

        // tinyproxy
        tinyStatus = findViewById(R.id.tinyproxy_status)
        tinyStart = findViewById(R.id.tinyproxy_start)
        tinyStop = findViewById(R.id.tinyproxy_stop)

        // frpc
        frpcStatus = findViewById(R.id.frpc_status)
        frpcStart = findViewById(R.id.frpc_start)
        frpcStop = findViewById(R.id.frpc_stop)

        // 日志
        logView = findViewById(R.id.log_view)
        scrollLog = findViewById(R.id.scroll_log)

        // 加载已保存配置
        val prefs = getSharedPreferences("proxy_config", Context.MODE_PRIVATE)
        serverAddrInput.setText(prefs.getString("server_addr", "49.232.72.125"))
        remotePortInput.setText(prefs.getString("remote_port", ""))
        authTokenInput.setText(prefs.getString("auth_token", ""))

        // ── 保存配置 ──
        saveBtn.setOnClickListener {
            prefs.edit().apply {
                putString("server_addr", serverAddrInput.text.toString().trim())
                putString("remote_port", remotePortInput.text.toString().trim())
                putString("auth_token", authTokenInput.text.toString().trim())
                apply()
            }
            addLog("[config]", "配置已保存")
        }

        // ── tinyproxy ──
        tinyStart.setOnClickListener {
            ensureService()
            ProxyService.start(this, ProxyService.ACTION_START_TINYPROXY)
            tinyStatus.text = "⏳ 启动中…"
            tinyStatus.setTextColor(0xFFFF9800.toInt())
        }
        tinyStop.setOnClickListener {
            ProxyService.start(this, ProxyService.ACTION_STOP_TINYPROXY)
        }

        // ── frpc ──
        frpcStart.setOnClickListener {
            ensureService()
            ProxyService.start(this, ProxyService.ACTION_START_FRPC)
            frpcStatus.text = "⏳ 启动中…"
            frpcStatus.setTextColor(0xFFFF9800.toInt())
        }
        frpcStop.setOnClickListener {
            ProxyService.start(this, ProxyService.ACTION_STOP_FRPC)
        }

        // 注册广播
        val filter = IntentFilter().apply {
            addAction(ProxyService.BROADCAST_LOG)
            addAction(ProxyService.BROADCAST_STATUS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(logReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    override fun onDestroy() {
        unregisterReceiver(logReceiver)
        super.onDestroy()
    }

    private fun ensureService() {
        // 确保 Service 在前台跑着
        val intent = Intent(this, ProxyService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION
                )
            }
        }
    }

    private fun updateUI() {
        // tinyproxy
        if (ProxyService.isTinyproxyRunning()) {
            tinyStatus.text = "● 运行中 — 127.0.0.1:${ProxyService.getProxyPort()}"
            tinyStatus.setTextColor(0xFF4CAF50.toInt())
            tinyStart.isEnabled = false
            tinyStop.isEnabled = true
        } else {
            tinyStatus.text = "○ 未启动"
            tinyStatus.setTextColor(0xFFF44336.toInt())
            tinyStart.isEnabled = true
            tinyStop.isEnabled = false
        }

        // frpc
        if (ProxyService.isFrpcRunning()) {
            val prefs = getSharedPreferences("proxy_config", Context.MODE_PRIVATE)
            val rp = prefs.getString("remote_port", "?") ?: "?"
            frpcStatus.text = "● 已连接 → :$rp"
            frpcStatus.setTextColor(0xFF4CAF50.toInt())
            frpcStart.isEnabled = false
            frpcStop.isEnabled = true
        } else {
            frpcStatus.text = "○ 未启动"
            frpcStatus.setTextColor(0xFFF44336.toInt())
            frpcStart.isEnabled = true
            frpcStop.isEnabled = false
        }
    }

    private fun addLog(tag: String, line: String) {
        val ts = System.currentTimeMillis() % 100000
        val entry = "[$ts] $tag: $line"
        logBuilder.appendLine(entry)
        runOnUiThread {
            logView.text = logBuilder.toString()
            scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // ── 广播接收器 ─────────────────────────────────────

    inner class LogReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ProxyService.BROADCAST_LOG -> {
                    val component = intent.getStringExtra(ProxyService.EXTRA_COMPONENT) ?: "?"
                    val line = intent.getStringExtra(ProxyService.EXTRA_LINE) ?: ""
                    addLog(component, line)
                }
                ProxyService.BROADCAST_STATUS -> {
                    runOnUiThread { updateUI() }
                }
            }
        }
    }
}
