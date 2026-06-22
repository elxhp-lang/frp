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

    private lateinit var etServer: EditText
    private lateinit var etRemotePort: EditText
    private lateinit var etToken: EditText
    private lateinit var logView: TextView
    private lateinit var scrollLog: ScrollView

    private lateinit var goproxyStartBtn: Button
    private lateinit var goproxyStopBtn: Button
    private lateinit var goproxyStatus: TextView

    private lateinit var frpcStartBtn: Button
    private lateinit var frpcStopBtn: Button
    private lateinit var frpcStatus: TextView

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val dateFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etServer = findViewById(R.id.server_addr)
        etRemotePort = findViewById(R.id.remote_port)
        etToken = findViewById(R.id.auth_token)
        logView = findViewById(R.id.log_view)
        scrollLog = findViewById(R.id.scroll_log)

        goproxyStatus = findViewById(R.id.goproxy_status)
        goproxyStartBtn = findViewById(R.id.goproxy_start)
        goproxyStopBtn = findViewById(R.id.goproxy_stop)

        frpcStatus = findViewById(R.id.frpc_status)
        frpcStartBtn = findViewById(R.id.frpc_start)
        frpcStopBtn = findViewById(R.id.frpc_stop)

        goproxyStartBtn.setOnClickListener { startGoproxy() }
        goproxyStopBtn.setOnClickListener { stopGoproxy() }
        frpcStartBtn.setOnClickListener { startFrpc() }
        frpcStopBtn.setOnClickListener { stopFrpc() }

        // 恢复 UI 状态
        refreshStatus()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
        stopGoproxy()
        stopFrpc()
    }

    // ── goproxy ────────────────────────────────

    private fun startGoproxy() {
        scope.launch(Dispatchers.IO) {
            addLog("goproxy", "正在启动…")
            val config = "Port 7890\nListen 127.0.0.1\n"
            val result = GoproxyBridge.StartGoproxy(config)
            withContext(Dispatchers.Main) {
                if (result == 0) {
                    addLog("goproxy", "✓ 已启动 — 127.0.0.1:7890")
                    goproxyStatus.text = "● 运行中 — 127.0.0.1:7890"
                    goproxyStatus.setTextColor(0xFF4CAF50.toInt())
                } else {
                    addLog("goproxy", "✗ 启动失败 (code=$result)")
                    goproxyStatus.text = "○ 启动失败"
                    goproxyStatus.setTextColor(0xFFF44336.toInt())
                }
                updateGoproxyButtons()
            }
        }
    }

    private fun stopGoproxy() {
        scope.launch(Dispatchers.IO) {
            addLog("goproxy", "正在停止…")
            GoproxyBridge.StopGoproxy()
            withContext(Dispatchers.Main) {
                addLog("goproxy", "✓ 已停止")
                goproxyStatus.text = "○ 已停止"
                goproxyStatus.setTextColor(0xFF757575.toInt())
                updateGoproxyButtons()
            }
        }
    }

    // ── frpc ────────────────────────────────────

    private fun startFrpc() {
        val server = etServer.text.toString().trim()
        val remotePort = etRemotePort.text.toString().trim()
        val token = etToken.text.toString().trim()

        if (server.isEmpty() || remotePort.isEmpty() || token.isEmpty()) {
            addLog("frpc", "✗ 请填写 Server / Remote Port / Token")
            return
        }

        scope.launch(Dispatchers.IO) {
            addLog("frpc", "正在生成配置…")

            // 生成 toml 配置
            val config = """
                serverAddr = "$server"
                serverPort = 7000
                auth.token = "$token"
                
                [[proxies]]
                name = "phone_proxy"
                type = "tcp"
                localIP = "127.0.0.1"
                localPort = 7890
                remotePort = $remotePort
            """.trimIndent()

            // 写入配置到 app 私有目录
            val configPath = "$filesDir/frpc.toml"
            val writeResult = FrpcBridge.FrpcWriteConfig(configPath, config)
            if (writeResult != 0) {
                addLog("frpc", "✗ 配置写入失败")
                withContext(Dispatchers.Main) {
                    frpcStatus.text = "○ 配置写入失败"
                    frpcStatus.setTextColor(0xFFF44336.toInt())
                }
                return@launch
            }
            addLog("frpc", "配置已写入 $configPath")

            // 启动 frpc
            addLog("frpc", "正在连接 frps…")
            val result = FrpcBridge.StartFrpc(configPath)
            withContext(Dispatchers.Main) {
                if (result == 0) {
                    addLog("frpc", "✓ 已启动 — 隧道 $remotePort → 127.0.0.1:7890")
                    frpcStatus.text = "● 运行中 — 隧道 $remotePort"
                    frpcStatus.setTextColor(0xFF4CAF50.toInt())
                } else {
                    addLog("frpc", "✗ 启动失败 (code=$result)")
                    frpcStatus.text = "○ 启动失败"
                    frpcStatus.setTextColor(0xFFF44336.toInt())
                }
                updateFrpcButtons()
            }
        }
    }

    private fun stopFrpc() {
        scope.launch(Dispatchers.IO) {
            addLog("frpc", "正在停止…")
            FrpcBridge.StopFrpc()
            withContext(Dispatchers.Main) {
                addLog("frpc", "✓ 已停止")
                frpcStatus.text = "○ 已停止"
                frpcStatus.setTextColor(0xFF757575.toInt())
                updateFrpcButtons()
            }
        }
    }

    // ── 状态刷新 ───────────────────────────────

    private fun refreshStatus() {
        scope.launch(Dispatchers.IO) {
            val proxyRunning = GoproxyBridge.IsGoproxyRunning() == 1
            val frpcRunning = FrpcBridge.IsFrpcRunning() == 1
            withContext(Dispatchers.Main) {
                if (proxyRunning) {
                    goproxyStatus.text = "● 运行中 — 127.0.0.1:7890"
                    goproxyStatus.setTextColor(0xFF4CAF50.toInt())
                }
                if (frpcRunning) {
                    frpcStatus.text = "● 运行中"
                    frpcStatus.setTextColor(0xFF4CAF50.toInt())
                }
                updateGoproxyButtons()
                updateFrpcButtons()
            }
        }
    }

    private fun updateGoproxyButtons() {
        val running = GoproxyBridge.IsGoproxyRunning() == 1
        goproxyStartBtn.isEnabled = !running
        goproxyStopBtn.isEnabled = running
    }

    private fun updateFrpcButtons() {
        val running = FrpcBridge.IsFrpcRunning() == 1
        frpcStartBtn.isEnabled = !running
        frpcStopBtn.isEnabled = running
    }

    // ── 日志 ────────────────────────────────────

    private fun addLog(tag: String, line: String) {
        val ts = dateFormat.format(java.util.Date())
        val entry = "[$ts] $tag: $line"
        runOnUiThread {
            logView.append("$entry\n")
            scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
