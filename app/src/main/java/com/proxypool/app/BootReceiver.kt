package com.proxypool.app

import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ProxyService.start(context)
        }
    }
}
