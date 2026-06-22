package com.proxypool.app

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader

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

    // 日志管道 — Go 侧写，Kotlin 侧读
    private var goproxyLogPipe: ParcelFileDescriptor? = null  // read end
    private var frpcLogPipe: ParcelFileDescriptor? = null      // read end
    private var goproxyLogJob: Job? = null
    private var frpcLogJob: Job? = null

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
        // 先停止日志读取
        goproxyLogJob?.cancel()
        frpcLogJob?.cancel()
        goproxyLogPipe?.close()
        frpcLogPipe?.close()

        scope.cancel()
        stopGoproxy()
        stopFrpc()
        super.onDestroy()
    }

    // ── 日志管道设置 ────────────────────────────

    /** 为 goproxy 创建日志管道，Go 侧写 → Kotlin 侧读到 UI */
    private fun setupGoproxyLogPipe() {
        if (goproxyLogJob?.isActive == true) return

        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        goproxyLogPipe = readEnd

        // 把 write-end fd 传给 Go
        GoproxyBridge.SetLogPipe(writeEnd.fd)
        writeEnd.close() // Go 持有 write end，我们这边可以关了

        // 起协程读 read-end → UI
        goproxyLogJob = scope.launch(Dispatchers.IO) {
            try {
                val reader = BufferedReader(InputStreamReader(FileInputStream(readEnd.fileDescriptor)))
                var line: String? = reader.readLine()
                while (line != null) {
                    addLog("goproxy", line!!)
                    line = reader.readLine()
                }
            } catch (e: Exception) {
                addLog("goproxy", "log pipe closed: ${e.message}")
            }
        }
    }

    /** 为 frpc 创建日志管道 */
    private fun setupFrpcLogPipe() {
        if (frpcLogJob?.isActive == true) return

        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        frpcLogPipe = readEnd

        // 把 write-end fd 传给 Go
        FrpcBridge.SetLogPipe(writeEnd.fd)
        writeEnd.close()

        // 起协程读 read-end → UI
        frpcLogJob = scope.launch(Dispatchers.IO) {
            try {
                val reader = BufferedReader(InputStreamReader(FileInputStream(readEnd.fileDescriptor)))
                var line: String? = reader.readLine()
                while (line != null) {
                    addLog("frpc", line!!)
                    line = reader.readLine()
                }
            } catch (e: Exception) {
                addLog("frpc", "log pipe closed: ${e.message}")
            }
        }
    }

    // ── goproxy ────────────────────────────────

    private fun startGoproxy() {
        scope.launch(Dispatchers.IO) {
            setupGoproxyLogPipe()
            addLog("goproxy", "starting...")
            val config = "Port 7890\nListen 127.0.0.1\n"
            val result = GoproxyBridge.StartGoproxy(config)
            withContext(Dispatchers.Main) {
                if (result == 0) {
                    goproxyStatus.text = "● running — 127.0.0.1:7890"
                    goproxyStatus.setTextColor(0xFF4CAF50.toInt())
                } else {
                    addLog("goproxy", "start failed (code=$result)")
                    goproxyStatus.text = "○ start failed"
                    goproxyStatus.setTextColor(0xFFF44336.toInt())
                }
                updateGoproxyButtons()
            }
        }
    }

    private fun stopGoproxy() {
        scope.launch(Dispatchers.IO) {
            addLog("goproxy", "stopping...")
            GoproxyBridge.StopGoproxy()
            withContext(Dispatchers.Main) {
                goproxyStatus.text = "○ stopped"
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
            addLog("frpc", "✗ fill in Server / Port / Token")
            return
        }

        scope.launch(Dispatchers.IO) {
            setupFrpcLogPipe()
            addLog("frpc", "writing config...")

            // 生成 toml 配置
            val config = """
                serverAddr = "$server"
                serverPort = 17891
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
                addLog("frpc", "✗ config write failed")
                withContext(Dispatchers.Main) {
                    frpcStatus.text = "○ config write failed"
                    frpcStatus.setTextColor(0xFFF44336.toInt())
                }
                return@launch
            }

            // 启动 frpc
            val result = FrpcBridge.StartFrpc(configPath)
            withContext(Dispatchers.Main) {
                if (result == 0) {
                    frpcStatus.text = "● running — tunnel $remotePort"
                    frpcStatus.setTextColor(0xFF4CAF50.toInt())
                } else {
                    addLog("frpc", "start failed (code=$result)")
                    frpcStatus.text = "○ start failed"
                    frpcStatus.setTextColor(0xFFF44336.toInt())
                }
                updateFrpcButtons()
            }
        }
    }

    private fun stopFrpc() {
        scope.launch(Dispatchers.IO) {
            FrpcBridge.StopFrpc()
            withContext(Dispatchers.Main) {
                frpcStatus.text = "○ stopped"
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
                    goproxyStatus.text = "● running — 127.0.0.1:7890"
                    goproxyStatus.setTextColor(0xFF4CAF50.toInt())
                }
                if (frpcRunning) {
                    frpcStatus.text = "● running"
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
