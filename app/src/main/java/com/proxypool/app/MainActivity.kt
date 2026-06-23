package com.proxypool.app

import android.content.*
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvPhoneId: TextView
    private lateinit var tvProxyAddr: TextView
    private lateinit var tvProxyStatus: TextView
    private lateinit var tvFrpcStatus: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var logView: TextView
    private lateinit var scrollLog: ScrollView

    private var proxyService: ProxyService? = null
    private var serviceBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val svc = (binder as ProxyService.LocalBinder).getService()
            proxyService = svc
            serviceBound = true

            svc.onLogLine = { line -> appendLog(line) }
            svc.onStatusChange = { status -> handleStatusChange(status) }

            // 恢复历史日志
            svc.getLogBuffer().forEach { appendLog(it) }

            // 修复：根据当前实际状态更新 UI，不重复启动
            when {
                svc.isConnected -> handleStatusChange("connected")
                svc.isStarting  -> handleStatusChange("connecting")
                else            -> handleStatusChange("stopped")
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            proxyService?.onLogLine = null
            proxyService?.onStatusChange = null
            proxyService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus      = findViewById(R.id.tv_status)
        tvPhoneId     = findViewById(R.id.tv_phone_id)
        tvProxyAddr   = findViewById(R.id.tv_proxy_addr)
        tvProxyStatus = findViewById(R.id.tv_proxy_status)
        tvFrpcStatus  = findViewById(R.id.tv_frpc_status)
        btnConnect    = findViewById(R.id.btn_connect)
        btnDisconnect = findViewById(R.id.btn_disconnect)
        logView       = findViewById(R.id.log_view)
        scrollLog     = findViewById(R.id.scroll_log)

        // 修复：初始 UI 设成"启动中"，等 bind 回调再更新
        setUiConnecting()

        btnConnect.setOnClickListener {
            ProxyService.start(this)
            setUiConnecting()
        }
        btnDisconnect.setOnClickListener {
            ProxyService.stop(this)
        }

        // 启动服务并 bind
        ProxyService.start(this)
        bindService(Intent(this, ProxyService::class.java), connection, BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        if (serviceBound) {
            proxyService?.onLogLine = null
            proxyService?.onStatusChange = null
            unbindService(connection)
            serviceBound = false
        }
        super.onDestroy()
    }

    private fun handleStatusChange(status: String) = runOnUiThread {
        when {
            status == "connected" -> {
                tvStatus.text = "✓ 已连接"
                tvStatus.setTextColor(0xFF4CAF50.toInt())
                tvPhoneId.text = proxyService?.phoneId ?: ""
                tvProxyAddr.text = proxyService?.proxyAddr ?: ""
                tvFrpcStatus.text = "● 隧道已建立"
                tvFrpcStatus.setTextColor(0xFF4CAF50.toInt())
                tvProxyStatus.text = "● 隧道转发中"
                tvProxyStatus.setTextColor(0xFF4CAF50.toInt())
                btnConnect.isEnabled = false
                btnDisconnect.isEnabled = true
            }
            status == "connecting" -> setUiConnecting()
            status == "stopped" -> {
                tvStatus.text = "已断开"
                tvStatus.setTextColor(0xFF757575.toInt())
                tvPhoneId.text = ""
                tvFrpcStatus.text = "○ 已停止"
                tvFrpcStatus.setTextColor(0xFF757575.toInt())
                tvProxyStatus.text = "○ 已停止"
                tvProxyStatus.setTextColor(0xFF757575.toInt())
                btnConnect.isEnabled = true
                btnDisconnect.isEnabled = false
            }
            status.startsWith("error:") -> {
                tvStatus.text = "启动失败，请重试"
                tvStatus.setTextColor(0xFFF44336.toInt())
                tvFrpcStatus.text = "○ 失败"
                tvFrpcStatus.setTextColor(0xFFF44336.toInt())
                btnConnect.isEnabled = true
                btnDisconnect.isEnabled = false
            }
        }
    }

    private fun setUiConnecting() {
        tvStatus.text = "连接中…"
        tvStatus.setTextColor(0xFFFFA726.toInt())
        tvFrpcStatus.text = "● 连接中…"
        tvFrpcStatus.setTextColor(0xFFFFA726.toInt())
        tvProxyStatus.text = "● 启动中…"
        tvProxyStatus.setTextColor(0xFFFFA726.toInt())
        btnConnect.isEnabled = false
        btnDisconnect.isEnabled = true
    }

    private fun appendLog(line: String) = runOnUiThread {
        logView.append("$line\n")
        scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
    }
}
