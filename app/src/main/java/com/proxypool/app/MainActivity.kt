package com.proxypool.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var logView: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var serverAddrInput: EditText
    private lateinit var remotePortInput: EditText
    private lateinit var authTokenInput: EditText
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button

    private val mainScope = CoroutineScope(Dispatchers.Main)
    private val logBuilder = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        logView = findViewById(R.id.log_view)
        scrollLog = findViewById(R.id.scroll_log)
        serverAddrInput = findViewById(R.id.server_addr)
        remotePortInput = findViewById(R.id.remote_port)
        authTokenInput = findViewById(R.id.auth_token)
        startBtn = findViewById(R.id.start_btn)
        stopBtn = findViewById(R.id.stop_btn)

        // 加载上次保存的配置
        val prefs = getSharedPreferences("proxy_config", Context.MODE_PRIVATE)
        serverAddrInput.setText(prefs.getString("server_addr", "49.232.72.125"))
        remotePortInput.setText(prefs.getString("remote_port", ""))
        authTokenInput.setText(prefs.getString("auth_token", ""))

        updateUI()

        startBtn.setOnClickListener {
            saveConfig()
            ProxyService.stop(this)
            ProxyService.start(this)
            updateUI()
            addLog("Proxy started — local port: ${ProxyService.getProxyPort()}")
        }

        stopBtn.setOnClickListener {
            ProxyService.stop(this)
            updateUI()
            addLog("Proxy stopped")
        }

        // 定时刷新状态
        mainScope.launch {
            while (true) {
                kotlinx.coroutines.delay(3000)
                withContext(Dispatchers.Main) {
                    updateUI()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
        loadLog()
    }

    private fun saveConfig() {
        val prefs = getSharedPreferences("proxy_config", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("server_addr", serverAddrInput.text.toString().trim())
            putString("remote_port", remotePortInput.text.toString().trim())
            putString("auth_token", authTokenInput.text.toString().trim())
            apply()
        }
        addLog("Config saved")
    }

    private fun updateUI() {
        val running = ProxyService.isRunning()
        statusText.text = if (running) {
            "● Running — proxy :${ProxyService.getProxyPort()}"
        } else {
            "○ Stopped"
        }

        startBtn.isEnabled = !running
        stopBtn.isEnabled = running

        // 运行中时用颜色标记
        if (running) {
            statusText.setTextColor(0xFF4CAF50.toInt())
        } else {
            statusText.setTextColor(0xFFF44336.toInt())
        }
    }

    private fun addLog(msg: String) {
        val line = "${System.currentTimeMillis() % 100000} $msg"
        logBuilder.appendLine(line)
        logView.text = logBuilder.toString()
        scrollLog.post {
            scrollLog.fullScroll(View.FOCUS_DOWN)
        }
        // 同时存入文件
        File(filesDir, "proxy.log").appendText("$line\n")
    }

    private fun loadLog() {
        try {
            val logFile = File(filesDir, "proxy.log")
            if (logFile.exists()) {
                logBuilder.clear()
                logBuilder.append(logFile.readText())
                logView.text = logBuilder.toString()
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        mainScope.cancel()
        super.onDestroy()
    }
}
