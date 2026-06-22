package com.proxypool.app

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ProxyPool"
        private const val PROXY_PORT = "7890"
    }

    // 配置
    private lateinit var serverAddrInput: EditText
    private lateinit var remotePortInput: EditText
    private lateinit var authTokenInput: EditText
    private lateinit var saveBtn: Button

    // goproxy
    private lateinit var goproxyStatus: TextView
    private lateinit var goproxyStartBtn: Button
    private lateinit var goproxyStopBtn: Button

    // frpc
    private lateinit var frpcStatus: TextView
    private lateinit var frpcStartBtn: Button
    private lateinit var frpcStopBtn: Button

    // 日志
    private lateinit var logView: TextView
    private lateinit var scrollLog: ScrollView

    // 进程
    private var goproxyProcess: Process? = null
    private var frpcProcess: Process? = null

    private val logBuilder = StringBuilder()

    // ── 生命周期 ───────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        loadConfig()
        setupButtons()
    }

    override fun onDestroy() {
        // 退出时关掉所有进程
        goproxyProcess?.destroy()
        frpcProcess?.destroy()
        super.onDestroy()
    }

    // ── 初始化 ─────────────────────────────────

    private fun initViews() {
        // 配置
        serverAddrInput = findViewById(R.id.server_addr)
        remotePortInput = findViewById(R.id.remote_port)
        authTokenInput = findViewById(R.id.auth_token)
        saveBtn = findViewById(R.id.save_btn)

        // goproxy
        goproxyStatus = findViewById(R.id.goproxy_status)
        goproxyStartBtn = findViewById(R.id.goproxy_start)
        goproxyStopBtn = findViewById(R.id.goproxy_stop)

        // frpc
        frpcStatus = findViewById(R.id.frpc_status)
        frpcStartBtn = findViewById(R.id.frpc_start)
        frpcStopBtn = findViewById(R.id.frpc_stop)

        // 日志
        logView = findViewById(R.id.log_view)
        scrollLog = findViewById(R.id.scroll_log)
    }

    private fun loadConfig() {
        val prefs = getSharedPreferences("proxy_config", Context.MODE_PRIVATE)
        serverAddrInput.setText(prefs.getString("server_addr", "49.232.72.125"))
        remotePortInput.setText(prefs.getString("remote_port", ""))
        authTokenInput.setText(prefs.getString("auth_token", ""))
    }

    // ── 按钮事件 ───────────────────────────────

    private fun setupButtons() {
        val prefs = getSharedPreferences("proxy_config", Context.MODE_PRIVATE)

        saveBtn.setOnClickListener {
            val server = serverAddrInput.text.toString().trim()
            val port = remotePortInput.text.toString().trim()
            val token = authTokenInput.text.toString().trim()

            if (port.isEmpty()) {
                addLog("config", "❌ Remote Port 不能为空")
                return@setOnClickListener
            }

            prefs.edit().apply {
                putString("server_addr", server)
                putString("remote_port", port)
                putString("auth_token", token)
                apply()
            }
            addLog("config", "✓ 配置已保存 — Server=$server Port=$port Token=${if (token.isNotEmpty()) token else "(空)"}")
        }

        // ── goproxy ──
        goproxyStartBtn.setOnClickListener { startGoproxy() }
        goproxyStopBtn.setOnClickListener { stopGoproxy() }

        // ── frpc ──
        frpcStartBtn.setOnClickListener { startFrpc() }
        frpcStopBtn.setOnClickListener { stopFrpc() }
    }

    // ── goproxy 启停 ───────────────────────────

    private fun startGoproxy() {
        lifecycleScope.launch(Dispatchers.IO) {
            addLog("goproxy", "正在启动…")

            // 1. 从 APK assets 提取二进制
            val bin = extractBinary("goproxy")
            if (bin == null) {
                addLog("goproxy", "❌ 二进制提取失败")
                withContext(Dispatchers.Main) { updateGoproxyStatus("○ 提取失败", 0xFFF44336.toInt()) }
                return@launch
            }

            // 2. 生成配置文件 (tinyproxy 兼容格式)
            val config = File(filesDir, "goproxy.conf")
            config.writeText("""
Port $PROXY_PORT
Listen 127.0.0.1
""".trimIndent())
            addLog("goproxy", "配置写入: ${config.absolutePath}")

            // 3. 启动进程
            try {
                val process = ProcessBuilder(bin.absolutePath, "-c", config.absolutePath)
                    .redirectErrorStream(false)
                    .start()

                goproxyProcess = process

                withContext(Dispatchers.Main) {
                    updateGoproxyStatus("● 运行中 — 127.0.0.1:$PROXY_PORT", 0xFF4CAF50.toInt())
                }

                addLog("goproxy", "✓ 进程已启动 PID=${if (process.isAlive) "?" else "已退出"}")

                // 4. 后台读 stdout
                readStream("goproxy", process.inputStream)

                // 5. 后台读 stderr
                readStream("goproxy ERR", process.errorStream)

                // 6. 等待退出
                val exitCode = process.waitFor()
                addLog("goproxy", "进程已退出，exit code=$exitCode")
                goproxyProcess = null
                withContext(Dispatchers.Main) {
                    updateGoproxyStatus("○ 已退出 (code=$exitCode)", 0xFFF44336.toInt())
                }

            } catch (e: Exception) {
                addLog("goproxy", "❌ 启动失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    updateGoproxyStatus("○ 启动失败", 0xFFF44336.toInt())
                }
            }
        }
    }

    private fun stopGoproxy() {
        goproxyProcess?.let {
            addLog("goproxy", "正在停止…")
            it.destroy()
            goproxyProcess = null
            updateGoproxyStatus("○ 已停止", 0xFFF44336.toInt())
        } ?: run {
            addLog("goproxy", "进程不存在，无需停止")
            updateGoproxyStatus("○ 未启动", 0xFFF44336.toInt())
        }
    }

    // ── frpc 启停 ──────────────────────────────

    private fun startFrpc() {
        lifecycleScope.launch(Dispatchers.IO) {
            addLog("frpc", "正在启动…")

            // 1. 检查配置
            val prefs = getSharedPreferences("proxy_config", Context.MODE_PRIVATE)
            val serverAddr = prefs.getString("server_addr", "49.232.72.125") ?: "49.232.72.125"
            val remotePort = prefs.getString("remote_port", "") ?: ""
            val authToken = prefs.getString("auth_token", "") ?: ""

            if (remotePort.isEmpty()) {
                addLog("frpc", "❌ Remote Port 未配置，请先保存配置")
                return@launch
            }

            // 2. 提取二进制
            val bin = extractBinary("frpc")
            if (bin == null) {
                addLog("frpc", "❌ 二进制提取失败")
                withContext(Dispatchers.Main) { updateFrpcStatus("○ 提取失败", 0xFFF44336.toInt()) }
                return@launch
            }

            // 3. 生成配置文件
            val config = File(filesDir, "frpc.toml")
            val conf = buildString {
                appendLine("serverAddr = \"$serverAddr\"")
                appendLine("serverPort = 7000")
                if (authToken.isNotEmpty()) {
                    appendLine("auth.token = \"$authToken\"")
                }
                appendLine()
                appendLine("[[proxies]]")
                appendLine("name = \"phone_proxy\"")
                appendLine("type = \"tcp\"")
                appendLine("localIP = \"127.0.0.1\"")
                appendLine("localPort = $PROXY_PORT")
                appendLine("remotePort = $remotePort")
            }
            config.writeText(conf)
            addLog("frpc", "配置写入: ${config.absolutePath}")
            addLog("frpc", "--- config ---\n$conf\n---")

            // 4. 启动进程
            try {
                val process = ProcessBuilder(bin.absolutePath, "-c", config.absolutePath)
                    .redirectErrorStream(false)
                    .start()

                frpcProcess = process

                withContext(Dispatchers.Main) {
                    updateFrpcStatus("⏳ 连接中…", 0xFFFF9800.toInt())
                }

                addLog("frpc", "✓ 进程已启动")

                readStream("frpc", process.inputStream)
                readStream("frpc ERR", process.errorStream)

                val exitCode = process.waitFor()
                addLog("frpc", "进程已退出，exit code=$exitCode")
                frpcProcess = null
                withContext(Dispatchers.Main) {
                    updateFrpcStatus("○ 已退出 (code=$exitCode)", 0xFFF44336.toInt())
                }

            } catch (e: Exception) {
                addLog("frpc", "❌ 启动失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    updateFrpcStatus("○ 启动失败", 0xFFF44336.toInt())
                }
            }
        }
    }

    private fun stopFrpc() {
        frpcProcess?.let {
            addLog("frpc", "正在停止…")
            it.destroy()
            frpcProcess = null
            updateFrpcStatus("○ 已停止", 0xFFF44336.toInt())
        } ?: run {
            addLog("frpc", "进程不存在，无需停止")
            updateFrpcStatus("○ 未启动", 0xFFF44336.toInt())
        }
    }

    // ── 二进制提取 ─────────────────────────────

    private fun extractBinary(name: String): File? {
        return try {
            // /data/local/tmp 是 Android 上允许执行的标准目录，所有设备通用
            val binDir = File("/data/local/tmp/com.proxypool.app")
            binDir.mkdirs()
            val outputFile = File(binDir, name)
            if (outputFile.exists() && outputFile.length() > 0) {
                addLog("system", "$name 已存在，跳过提取")
            } else {
                addLog("system", "正在提取 $name…")
                val inputStream: InputStream = assets.open("arm64-v8a/$name")
                outputFile.outputStream().use { out ->
                    inputStream.copyTo(out)
                }
                inputStream.close()
                addLog("system", "$name 提取完成 (${outputFile.length()} bytes)")
            }

            if (!outputFile.setExecutable(true)) {
                addLog("system", "⚠ 设置 $name 可执行权限失败，尝试执行看看…")
            }
            outputFile
        } catch (e: Exception) {
            addLog("system", "❌ 提取 $name 失败: ${e.message}")
            null
        }
    }

    // ── 日志 ───────────────────────────────────

    private fun addLog(tag: String, line: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val entry = "[$ts] $tag: $line"
        logBuilder.appendLine(entry)
        runOnUiThread {
            logView.text = logBuilder.toString()
            scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun readStream(tag: String, inputStream: InputStream) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                inputStream.bufferedReader().forEachLine { line ->
                    runOnUiThread { addLog(tag, line) }

                    // 检测 frpc 连接状态
                    if (tag == "frpc" && line.contains("login to server success", ignoreCase = true)) {
                        val prefs = getSharedPreferences("proxy_config", Context.MODE_PRIVATE)
                        val rp = prefs.getString("remote_port", "?") ?: "?"
                        runOnUiThread {
                            updateFrpcStatus("● 已连接 → :$rp", 0xFF4CAF50.toInt())
                        }
                    }
                }
            } catch (e: Exception) {
                // 进程被 destroy 时 read 会抛异常，忽略
            }
        }
    }

    // ── UI 更新 ────────────────────────────────

    private fun updateGoproxyStatus(text: String, color: Int) {
        runOnUiThread {
            goproxyStatus.text = text
            goproxyStatus.setTextColor(color)
            val running = goproxyProcess != null && goproxyProcess!!.isAlive
            goproxyStartBtn.isEnabled = !running
            goproxyStopBtn.isEnabled = running
        }
    }

    private fun updateFrpcStatus(text: String, color: Int) {
        runOnUiThread {
            frpcStatus.text = text
            frpcStatus.setTextColor(color)
            val running = frpcProcess != null && frpcProcess!!.isAlive
            frpcStartBtn.isEnabled = !running
            frpcStopBtn.isEnabled = running
        }
    }
}
