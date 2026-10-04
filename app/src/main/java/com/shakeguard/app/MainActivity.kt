package com.shakeguard.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.shakeguard.app.core.Controller
import com.shakeguard.app.ui.AppRoot
import com.shakeguard.app.ui.ShakeGuardTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Controller.init(applicationContext)
        setContent {
            ShakeGuardTheme {
                AppRoot(onRequestNotification = { ensureNotificationPermission() })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Controller.onResume()
    }

    /** Android 13+ 需要通知权限，前台服务的进度通知才显示得出来 */
    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
        }
    }
}
