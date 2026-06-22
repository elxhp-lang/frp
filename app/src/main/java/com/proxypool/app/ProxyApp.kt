package com.proxypool.app

import android.app.Application
import android.util.Log

class ProxyApp : Application() {
    companion object {
        const val TAG = "ProxyPool"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ProxyApp initialized")
    }
}
