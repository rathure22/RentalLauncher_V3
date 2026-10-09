package com.RentalLauncher.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Transparent, no-UI activity whose only job is to show the system
 * "Start recording or casting?" dialog and hand the result back to
 * MyService. MediaProjection permission can only be requested from an
 * Activity, so MyService launches this when it needs to start a share.
 */
class ScreenCaptureActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        MyService.deliverCaptureResult(result.resultCode, result.data)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mgr = getSystemService(MediaProjectionManager::class.java)
        try {
            launcher.launch(mgr.createScreenCaptureIntent())
        } catch (e: Exception) {
            MyService.deliverCaptureResult(Activity.RESULT_CANCELED, null)
            finish()
        }
    }
}