package com.proxypool.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启：手机重启后自动启动代理服务。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // 有缓存配置才自启，没注册过的新机不自启
            val prefs = context.getSharedPreferences("proxypool", Context.MODE_PRIVATE)
            if (prefs.contains("config")) {
                ProxyService.start(context)
            }
        }
    }
}
