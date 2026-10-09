package com.RentalLauncher.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED) return
        val fine = c.checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") == PackageManager.PERMISSION_GRANTED
        val coarse = c.checkSelfPermission("android.permission.ACCESS_COARSE_LOCATION") == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return // location service needs the permission first
        try {
            c.startForegroundService(Intent(c, MyService::class.java))
        } catch (e: Exception) {
            Log.e("RentalBoot", "Could not start service on boot", e)
        }
    }
}
