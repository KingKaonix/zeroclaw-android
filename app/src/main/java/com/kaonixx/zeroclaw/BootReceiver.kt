package com.kaonixx.zeroclaw

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // On API 33+, startForegroundService requires POST_NOTIFICATIONS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    android.util.Log.w("BootReceiver", "POST_NOTIFICATIONS not granted, deferring")
                    return
                }
            }
            val serviceIntent = Intent(context, ZeroClawService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}
