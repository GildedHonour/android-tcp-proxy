package com.example.simpleproxy6

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NotificationBroadcastReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_STOP_SERVICE = "stop_service"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                TcpProxyService.getInstance()?.stopServerAndReleaseResources()

                val stopServiceIntent = Intent(context, TcpProxyService::class.java)
                context?.stopService(stopServiceIntent)
            }
        }
    }
}
