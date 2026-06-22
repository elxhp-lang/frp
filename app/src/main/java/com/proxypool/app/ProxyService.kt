package com.proxypool.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class ProxyService : Service() {

    companion object {
        const val TAG = "ProxyPool"
        const val CHANNEL_ID = "proxy_channel"
        const val NOTIFICATION_ID = 1
        const val PROXY_PORT = 7890

        private var instance: ProxyService? = null

        fun isRunning(): Boolean = instance != null

        fun getProxyPort(): Int = PROXY_PORT

        fun start(context: Context) {
            val intent = Intent(context, ProxyService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ProxyService::class.java)
            context.stopService(intent)
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var proxyProcess: Process? = null
    private var frpcProcess: Process? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        startProxies()
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        stopProxies()
        scope.cancel()
        super.onDestroy()
    }

    private fun startProxies() {
        scope.launch {
            try {
                // 1. 提取并启动 tinyproxy（本地 HTTP 代理 :7890）
                val proxyBin = extractBinary("tinyproxy", "tinyproxy")
                proxyBin.setExecutable(true)

                // 生成 tinyproxy 配置文件
                val proxyConfig = prepareProxyConfig()

                Log.i(TAG, "Starting tinyproxy on port $PROXY_PORT")
                proxyProcess = Runtime.getRuntime().exec(
                    arrayOf(proxyBin.absolutePath, "-c", proxyConfig.absolutePath),
                    null,
                    filesDir
                )
                // 等待代理就绪
                Thread.sleep(2000)

                // 2. 准备 frpc 配置
                val frpcConfig = prepareFrpcConfig()
                val frpcBin = extractBinary("frpc", "frpc")
                frpcBin.setExecutable(true)

                Log.i(TAG, "Starting frpc with config: $frpcConfig")
                frpcProcess = Runtime.getRuntime().exec(
                    arrayOf(frpcBin.absolutePath, "-c", frpcConfig.absolutePath),
                    null,
                    filesDir
                )

                Log.i(TAG, "Both proxies started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start proxies", e)
            }
        }
    }

    private fun stopProxies() {
        proxyProcess?.destroy()
        frpcProcess?.destroy()
        proxyProcess = null
        frpcProcess = null
        Log.i(TAG, "Proxies stopped")
    }

    /**
     * 生成 tinyproxy 配置文件
     */
    private fun prepareProxyConfig(): File {
        val configFile = File(filesDir, "tinyproxy.conf")
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
            // 日志
            appendLine("LogLevel Info")
            appendLine("LogFile \"${filesDir.absolutePath}/tinyproxy.log\"")
        }
        configFile.writeText(config)
        Log.d(TAG, "tinyproxy config written: ${configFile.absolutePath}")
        return configFile
    }

    /**
     * 从 Assets 中提取 ARM64 二进制到私有目录
     */
    private fun extractBinary(assetName: String, outputName: String): File {
        val arch = Build.SUPPORTED_ABIS[0] // 例如 arm64-v8a
        val assetPath = "$arch/$assetName"
        val outputFile = File(filesDir, outputName)

        if (outputFile.exists() && outputFile.length() > 0) {
            Log.d(TAG, "$assetName already extracted")
            return outputFile
        }

        try {
            assets.open(assetPath).use { input ->
                FileOutputStream(outputFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Extracted $assetName ($arch) -> ${outputFile.absolutePath}")
        } catch (e: Exception) {
            assets.open(assetName).use { input ->
                FileOutputStream(outputFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Extracted $assetName -> ${outputFile.absolutePath} (fallback)")
        }

        return outputFile
    }

    /**
     * 从 SharedPreferences 读取 frpc 配置并写入文件
     */
    private fun prepareFrpcConfig(): File {
        val configFile = File(filesDir, "frpc.toml")

        val prefs = getSharedPreferences("proxy_config", Context.MODE_PRIVATE)
        val serverAddr = prefs.getString("server_addr", "") ?: ""
        val serverPort = prefs.getString("server_port", "7000") ?: "7000"
        val remotePort = prefs.getString("remote_port", "") ?: ""
        val authToken = prefs.getString("auth_token", "") ?: ""

        val config = if (serverAddr.isNotEmpty() && remotePort.isNotEmpty()) {
            buildString {
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
        } else {
            buildString {
                appendLine("# TODO: 编辑此配置后重启服务")
                appendLine("serverAddr = \"49.232.72.125\"")
                appendLine("serverPort = 7000")
                appendLine()
                appendLine("[[proxies]]")
                appendLine("name = \"phone_proxy\"")
                appendLine("type = \"tcp\"")
                appendLine("localIP = \"127.0.0.1\"")
                appendLine("localPort = $PROXY_PORT")
                appendLine("remotePort = 17890")
            }
        }

        configFile.writeText(config)
        return configFile
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ProxyPool 运行中")
            .setContentText("tinyproxy :$PROXY_PORT — frp 隧道已连接")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "代理服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "ProxyPool 代理服务运行状态"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
