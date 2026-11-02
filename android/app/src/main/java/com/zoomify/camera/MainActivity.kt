package com.zoomify.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.zoomify.camera.ui.CameraScreen
import com.zoomify.camera.ui.PermissionScreen

class MainActivity : ComponentActivity() {
    private lateinit var controller: CameraController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        controller = CameraController(this)
        val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

        setContent {
            var cameraGranted by remember { mutableStateOf(isGranted(Manifest.permission.CAMERA)) }
            var asked by remember { mutableStateOf(false) }
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                cameraGranted = isGranted(Manifest.permission.CAMERA)
                asked = true
            }
            LaunchedEffect(Unit) {
                if (!cameraGranted || !isGranted(Manifest.permission.RECORD_AUDIO)) launcher.launch(permissions)
            }
            LaunchedEffect(cameraGranted) {
                if (cameraGranted) controller.start()
            }
            MaterialTheme(colorScheme = darkColorScheme()) {
                if (cameraGranted) {
                    CameraScreen(controller)
                } else {
                    PermissionScreen {
                        if (asked && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                            startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                            )
                        } else {
                            launcher.launch(permissions)
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        controller.stopRecordingIfNeeded()
    }

    override fun onDestroy() {
        super.onDestroy()
        controller.release()
    }

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
