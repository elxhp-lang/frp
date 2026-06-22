package com.proxypool.app

import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // 开机自启：只启动 tinyproxy（本地代理），frpc 需用户手动确认配置
            ProxyService.start(context, ProxyService.ACTION_START_TINYPROXY)
        }
    }
}
