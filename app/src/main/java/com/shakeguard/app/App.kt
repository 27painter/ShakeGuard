package com.shakeguard.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.shakeguard.app.core.GreyList

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // 灰名单要在无障碍服务起来之前就加载好，否则服务重启后会漏判
        runCatching { GreyList.load(this) }
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, "批量处理进度", NotificationManager.IMPORTANCE_LOW)
            channel.description = "驱动系统权限界面时会显示进度"
            channel.setShowBadge(false)
            nm.createNotificationChannel(channel)
        }
        if (nm.getNotificationChannel(CHANNEL_ADBLOCK) == null) {
            val channel = NotificationChannel(CHANNEL_ADBLOCK, "广告拦截", NotificationManager.IMPORTANCE_LOW)
            channel.description = "本地 DNS 广告拦截运行状态"
            channel.setShowBadge(false)
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "shakeguard_batch"
        const val CHANNEL_ADBLOCK = "shakeguard_adblock"
    }
}
