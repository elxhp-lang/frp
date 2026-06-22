package com.proxypool.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader

class ProxyService : Service() {

    companion object {
        const val TAG = "ProxyPool"
        const val CHANNEL_ID = "proxy_channel"
        const val NOTIFICATION_ID = 1
        const val PROXY_PORT = 7890

        // Intent Actions
        const val ACTION_START_TINYPROXY = "com.proxypool.START_TINYPROXY"
        const val ACTION_STOP_TINYPROXY  = "com.proxypool.STOP_TINYPROXY"
        const val ACTION_START_FRPC     = "com.proxypool.START_FRPC"
        const val ACTION_STOP_FRPC      = "com.proxypool.STOP_FRPC"
        const val ACTION_STOP_ALL       = "com.proxypool.STOP_ALL"

        // Broadcast actions — 日志推送给 Activity
        const val BROADCAST_LOG     = "com.proxypool.LOG"
        const val BROADCAST_STATUS  = "com.proxypool.STATUS"
        const val EXTRA_TAG         = "tag"
        const val EXTRA_LINE        = "line"
        const val EXTRA_COMPONENT   = "component"  // "tinyproxy" | "frpc"
        const val EXTRA_RUNNING     = "running"

        private var instance: ProxyService? = null

        fun isRunning(): Boolean = instance != null
        fun isTinyproxyRunning(): Boolean = instance?.tinyproxyRunning ?: false
        fun isFrpcRunning(): Boolean = instance?.frpcRunning ?: false
        fun getProxyPort(): Int = PROXY_PORT

        fun start(context: Context, action: String) {
            val intent = Intent(context, ProxyService::class.java).apply { this.action = action }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopAll(context: Context) {
            val intent = Intent(context, ProxyService::class.java).apply { action = ACTION_STOP_ALL }
            context.stopService(intent)
        }
    }

    // 进程状态
    @Volatile var tinyproxyRunning = false
    @Volatile var frpcRunning = false

    private var tinyproxyProcess: Process? = null
    private var frpcProcess: Process? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        when (intent?.action) {
            ACTION_START_TINYPROXY -> {
                Thread { startTinyproxy() }.start()
            }
            ACTION_STOP_TINYPROXY -> {
                stopTinyproxy()
            }
            ACTION_START_FRPC -> {
                Thread { startFrpc() }.start()
            }
            ACTION_STOP_FRPC -> {
                stopFrpc()
            }
            ACTION_STOP_ALL -> {
                stopTinyproxy()
                stopFrpc()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                // 无 action 的 start，默认不启动任何进程，等待具体指令
                Log.i(TAG, "Service started, waiting for commands")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        stopTinyproxy()
        stopFrpc()
        super.onDestroy()
    }

    // ─── tinyproxy ──────────────────────────────────────────

    private fun startTinyproxy() {
        if (tinyproxyRunning) {
            log("tinyproxy", "已在运行，跳过")
            return
        }
        try {
            val proxyBin = extractBinary("tinyproxy", "tinyproxy")
            if (!proxyBin.setExecutable(true)) {
                log("tinyproxy", "⚠ setExecutable 失败，继续尝试…")
            }
            val proxyConfig = prepareProxyConfig()

            log("tinyproxy", "启动: ${proxyBin.absolutePath} -c ${proxyConfig.absolutePath}")
            tinyproxyProcess = Runtime.getRuntime().exec(
                arrayOf(proxyBin.absolutePath, "-c", proxyConfig.absolutePath),
                null,
                filesDir
            )

            tinyproxyRunning = true
            broadcastStatus("tinyproxy", true)

            // 读 stdout
            Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(tinyproxyProcess!!.inputStream))
                    reader.forEachLine { log("tinyproxy", it) }
                } catch (_: Exception) {}
            }.start()

            // 读 stderr
            Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(tinyproxyProcess!!.errorStream))
                    reader.forEachLine { log("tinyproxy", "[err] $it") }
                } catch (_: Exception) {}
            }.start()

            // 等待 1 秒后检测存活
            Thread {
                Thread.sleep(1000)
                if (tinyproxyProcess?.isAlive == true) {
                    log("tinyproxy", "✓ 进程已启动 (PID: ${getPid(tinyproxyProcess)})")
                } else {
                    val exitCode = tinyproxyProcess?.exitValue()
                    log("tinyproxy", "✗ 进程已退出 (exit=$exitCode)，请查看上方日志定位原因")
                    tinyproxyRunning = false
                    broadcastStatus("tinyproxy", false)
                }
            }.start()

        } catch (e: Exception) {
            log("tinyproxy", "启动失败: ${e.message}")
            Log.e(TAG, "tinyproxy start error", e)
            tinyproxyRunning = false
            broadcastStatus("tinyproxy", false)
        }
    }

    private fun stopTinyproxy() {
        if (!tinyproxyRunning) return
        log("tinyproxy", "正在停止…")
        tinyproxyProcess?.destroy()
        tinyproxyProcess = null
        tinyproxyRunning = false
        broadcastStatus("tinyproxy", false)
        log("tinyproxy", "已停止")
    }

    // ─── frpc ───────────────────────────────────────────────

    private fun startFrpc() {
        if (frpcRunning) {
            log("frpc", "已在运行，跳过")
            return
        }
        try {
            val frpcConfig = prepareFrpcConfig()
            if (frpcConfig == null) {
                log("frpc", "✗ 配置不完整 (Remote Port 为空)，请在界面填写后保存")
                broadcastStatus("frpc", false)
                return
            }

            val frpcBin = extractBinary("frpc", "frpc")
            if (!frpcBin.setExecutable(true)) {
                log("frpc", "⚠ setExecutable 失败，继续尝试…")
            }

            log("frpc", "启动: ${frpcBin.absolutePath} -c ${frpcConfig.absolutePath}")
            log("frpc", "配置预览:")
            frpcConfig.readLines().forEach { log("frpc", "  $it") }

            frpcProcess = Runtime.getRuntime().exec(
                arrayOf(frpcBin.absolutePath, "-c", frpcConfig.absolutePath),
                null,
                filesDir
            )

            frpcRunning = true
            broadcastStatus("frpc", true)

            // 读 stdout
            Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(frpcProcess!!.inputStream))
                    reader.forEachLine { log("frpc", it) }
                } catch (_: Exception) {}
            }.start()

            // 读 stderr
            Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(frpcProcess!!.errorStream))
                    reader.forEachLine { log("frpc", "[err] $it") }
                } catch (_: Exception) {}
            }.start()

            // 等待 2 秒后检测
            Thread {
                Thread.sleep(2000)
                if (frpcProcess?.isAlive == true) {
                    log("frpc", "✓ frpc 进程已启动 (PID: ${getPid(frpcProcess)})")
                } else {
                    val exitCode = frpcProcess?.exitValue()
                    log("frpc", "✗ frpc 进程已退出 (exit=$exitCode)")
                    frpcRunning = false
                    broadcastStatus("frpc", false)
                }
            }.start()

        } catch (e: Exception) {
            log("frpc", "启动失败: ${e.message}")
            Log.e(TAG, "frpc start error", e)
            frpcRunning = false
            broadcastStatus("frpc", false)
        }
    }

    private fun stopFrpc() {
        if (!frpcRunning) return
        log("frpc", "正在停止…")
        frpcProcess?.destroy()
        frpcProcess = null
        frpcRunning = false
        broadcastStatus("frpc", false)
        log("frpc", "已停止")
    }

    // ─── 工具方法 ───────────────────────────────────────────

    private fun log(component: String, line: String) {
        Log.d(TAG, "[$component] $line")
        val intent = Intent(BROADCAST_LOG).apply {
            putExtra(EXTRA_COMPONENT, component)
            putExtra(EXTRA_LINE, line)
        }
        sendBroadcast(intent)
    }

    private fun broadcastStatus(component: String, running: Boolean) {
        val intent = Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_COMPONENT, component)
            putExtra(EXTRA_RUNNING, running)
        }
        sendBroadcast(intent)
        updateNotification()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun getPid(process: Process?): String {
        return try {
            val field = Process::class.java.getDeclaredField("pid")
            field.isAccessible = true
            field.getInt(process).toString()
        } catch (_: Exception) {
            "?"
        }
    }

    // ─── 配置 & 二进制提取 ─────────────────────────────────

    private fun prepareProxyConfig(): File {
        val configFile = File(filesDir, "tinyproxy.conf")
        val logFile = File(filesDir, "tinyproxy-runtime.log")
        val config = buildString {
            appendLine("# ProxyPool tinyproxy config")
            appendLine("Port $PROXY_PORT")
            appendLine("Listen 127.0.0.1")
            appendLine("Bind 127.0.0.1")
            appendLine("Timeout 600")
            appendLine("MaxClients 100")
            appendLine("MinSpareServers 3")
            appendLine("MaxSpareServers 10")
            appendLine("StartServers 5")
            appendLine("MaxRequestsPerChild 0")
            appendLine("Allow 127.0.0.1")
            appendLine("DisableViaHeader Yes")
            appendLine("LogLevel Info")
            appendLine("LogFile \"${logFile.absolutePath}\"")
        }
        configFile.writeText(config)
        return configFile
    }

    private fun extractBinary(assetName: String, outputName: String): File {
        val arch = Build.SUPPORTED_ABIS[0]
        val outputFile = File(filesDir, outputName)

        if (outputFile.exists() && outputFile.length() > 0) {
            log("system", "$outputName 已存在 (${outputFile.length()} bytes)")
            return outputFile
        }

        try {
            assets.open("$arch/$assetName").use { input ->
                FileOutputStream(outputFile).use { output ->
                    input.copyTo(output)
                }
            }
            log("system", "提取 $assetName ($arch) → ${outputFile.length()} bytes")
        } catch (e: Exception) {
            try {
                assets.open(assetName).use { input ->
                    FileOutputStream(outputFile).use { output ->
                        input.copyTo(output)
                    }
                }
                log("system", "提取 $assetName (fallback) → ${outputFile.length()} bytes")
            } catch (e2: Exception) {
                log("system", "✗ 提取 $assetName 失败: ${e2.message}")
                throw e2
            }
        }
        return outputFile
    }

    private fun prepareFrpcConfig(): File? {
        val configFile = File(filesDir, "frpc.toml")
        val prefs = getSharedPreferences("proxy_config", Context.MODE_PRIVATE)

        val serverAddr = prefs.getString("server_addr", "")?.takeIf { it.isNotEmpty() } ?: "49.232.72.125"
        val serverPort = prefs.getString("server_port", "7000") ?: "7000"
        val remotePort = prefs.getString("remote_port", "") ?: ""
        val authToken = prefs.getString("auth_token", "") ?: ""

        if (remotePort.isEmpty()) {
            return null
        }

        val config = buildString {
            appendLine("serverAddr = \"$serverAddr\"")
            appendLine("serverPort = $serverPort")
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
        configFile.writeText(config)
        return configFile
    }

    // ─── 通知 ───────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val tiny = if (tinyproxyRunning) "tinyproxy ✅" else "tinyproxy ⬚"
        val frp  = if (frpcRunning) "frpc ✅" else "frpc ⬚"
        val summary = if (tinyproxyRunning || frpcRunning) "$tiny  $frp" else "空闲"

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ProxyPool")
            .setContentText(summary)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "代理服务", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "ProxyPool 代理服务运行状态" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
