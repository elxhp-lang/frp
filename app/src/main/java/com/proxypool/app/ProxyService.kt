package com.proxypool.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class ProxyService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): ProxyService = this@ProxyService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder = binder

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tunnelClient: TunnelClient? = null
    private val logBuffer = ArrayDeque<String>(500)
    private val timeFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())

    var onLogLine: ((String) -> Unit)? = null
    var onStatusChange: ((String) -> Unit)? = null

    var phoneId: String = ""
    var proxyAddr: String = ""
    var isConnected: Boolean = false
    var isStarting: Boolean = false

    private val REGISTRY_URL = "http://49.232.72.125:8080/register"
    private val TUNNEL_PORT = 2222
    private val PREFS = "proxypool"
    private val NOTIF_CHANNEL = "proxy_service"
    private val NOTIF_ID = 1

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_START -> {
                if (!isConnected && !isStarting) {
                    startForeground(NOTIF_ID, buildNotification("连接中…"))
                    scope.launch { startServices() }
                }
            }
            ACTION_STOP -> {
                doStopServices()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        doStopServices()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun startServices() {
        isStarting = true
        try {
            val cfg = getConfig()
            phoneId = cfg.phoneId
            proxyAddr = "隧道模式  VPS:$TUNNEL_PORT  →  ${cfg.phoneId}"

            tunnelClient?.stop()
            tunnelClient = TunnelClient(
                vpsAddr = cfg.vpsAddr,
                tunnelPort = TUNNEL_PORT,
                phoneId = cfg.phoneId,
                token = cfg.token,
                log = ::log
            ).also { it.start() }

            onStatusChange?.invoke("connecting")
            safeUpdateNotification("连接中…")

        } catch (e: Exception) {
            log("sys", "启动失败: ${e.message}")
            onStatusChange?.invoke("error:${e.message}")
            safeUpdateNotification("连接失败")
        } finally {
            isStarting = false
        }
    }

    fun doStopServices() {
        tunnelClient?.stop(); tunnelClient = null
        isConnected = false
        isStarting = false
        onStatusChange?.invoke("stopped")
        log("sys", "已断开所有连接")
    }

    fun restartServices() {
        scope.launch {
            doStopServices()
            delay(500)
            startServices()
        }
    }

    data class TunnelConfig(
        val phoneId: String,
        val token: String,
        val vpsAddr: String
    )

    private suspend fun getConfig(): TunnelConfig = withContext(Dispatchers.IO) {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cached = prefs.getString("tunnel_config", null)
        if (cached != null) {
            try { return@withContext parseConfig(JSONObject(cached)) } catch (_: Exception) {}
        }

        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        log("sys", "首次注册，deviceId=$deviceId")

        val conn = (URL(REGISTRY_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        OutputStreamWriter(conn.outputStream).use { it.write("""{"device_id":"$deviceId"}""") }
        if (conn.responseCode != 200) throw Exception("注册失败 HTTP ${conn.responseCode}")

        val body = conn.inputStream.bufferedReader().readText()
        prefs.edit().putString("tunnel_config", body).apply()
        log("sys", "注册成功")
        parseConfig(JSONObject(body))
    }

    private fun parseConfig(j: JSONObject) = TunnelConfig(
        phoneId = j.optString("phone_id", "phone_unknown"),
        token    = j.optString("frp_token", j.optString("token", "")),
        vpsAddr  = j.optString("frps_addr", "49.232.72.125")
    )

    fun log(tag: String, line: String) {
        val entry = "[${timeFmt.format(java.util.Date())}] $tag: $line"
        synchronized(logBuffer) {
            logBuffer.addLast(entry)
            if (logBuffer.size > 500) logBuffer.removeFirst()
        }
        onLogLine?.invoke(entry)

        if (tag == "tunnel" && line.startsWith("✓ tunnel up")) {
            isConnected = true
            safeUpdateNotification("✓ 已连接  $phoneId")
            onStatusChange?.invoke("connected")
        }
        if (tag == "tunnel" && line == "stopped" && !isStarting) {
            isConnected = false
            onStatusChange?.invoke("stopped")
        }
    }

    fun getLogBuffer(): List<String> = synchronized(logBuffer) { logBuffer.toList() }

    private fun safeUpdateNotification(status: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.notify(NOTIF_ID, buildNotification(status))
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                NOTIF_CHANNEL, "代理服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "保持隧道后台运行" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(status: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("ProxyPool Tunnel")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP  = "STOP"

        fun start(ctx: Context) {
            val i = Intent(ctx, ProxyService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ProxyService::class.java).apply { action = ACTION_STOP })
        }
    }
}
