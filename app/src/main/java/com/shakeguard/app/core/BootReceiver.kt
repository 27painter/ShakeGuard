package com.shakeguard.app.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shakeguard.app.core.net.AdBlockVpnService

/** 开机后：上次开着「广告域名拦截」的话，自己把它拉起来 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        runCatching {
            if (Prefs(context).vpnDesired) AdBlockVpnService.start(context)
        }
    }
}
