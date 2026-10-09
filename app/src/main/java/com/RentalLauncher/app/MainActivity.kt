package com.RentalLauncher.app

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private var setupActive = false
    private val asked = mutableSetOf<String>()
    private var dialog: AlertDialog? = null

    // Results are handled in onResume() so each step runs exactly once.
    private val reqLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    private val reqBackground = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val reqNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        dialog = AlertDialog.Builder(this)
            .setTitle("Rental Device Secure Lock")
            .setMessage("This device is managed for an authorized rental. Setup will ask for location, notification and device-admin permissions.")
            .setPositiveButton("Continue") { _, _ -> setupActive = true; nextStep() }
            .setNegativeButton("Exit") { _, _ -> finishAffinity() }
            .setCancelable(false)
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (setupActive) nextStep()
    }

    private fun granted(p: String) =
        checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun nextStep() {
        dialog?.dismiss()

        // 1) Location (required)
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            if (asked.add("loc")) {
                reqLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            } else {
                showSettingsDialog("Location permission is required. Please allow it in App settings.")
            }
            return
        }
        // 2) Notifications (Android 13+, optional)
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS) && asked.add("notif")) {
            reqNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        // 3) Background location (Android 10+, optional but needed after reboot)
        if (Build.VERSION.SDK_INT >= 29 && !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) && asked.add("bg")) {
            reqBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            return
        }
        // 4) Device admin (needed for LOCK)
        val admin = ComponentName(this, AdminReceiver::class.java)
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isAdminActive(admin) && asked.add("admin")) {
            startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            })
            return
        }
        // Done: start the service
        setupActive = false
        startForegroundService(Intent(this, MyService::class.java))
        val adminOk = dpm.isAdminActive(admin)
        dialog = AlertDialog.Builder(this)
            .setTitle("Setup complete")
            .setMessage(if (adminOk) "Device service is running." else "Service is running, but device admin is OFF, so remote LOCK will not work.")
            .setPositiveButton("OK") { _, _ -> finishAffinity() }
            .setCancelable(false)
            .show()
    }

    private fun showSettingsDialog(msg: String) {
        dialog = AlertDialog.Builder(this)
            .setTitle("Permission needed")
            .setMessage(msg)
            .setPositiveButton("Open settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Exit") { _, _ -> finishAffinity() }
            .setCancelable(false)
            .show()
    }
}
