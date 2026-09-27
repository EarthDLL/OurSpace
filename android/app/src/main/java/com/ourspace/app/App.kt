package com.ourspace.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log

/**
 * 应用入口。
 *  - 初始化 Prefs（全局单例）；
 *  - 创建通知渠道（前台服务常驻通知「正在共享」要用）；
 *  - 启动时修剪一次早于 7 天的本地数据（每天一次）。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        createNotificationChannels()

        // 本地库修剪：启动时做一次，之后由 CollectorService 每天一次
        try {
            val db = LocalDb.get(this)
            db.trimOld(LocalDb.RETENTION_DAYS)
            Prefs.get().lastTrimAt = System.currentTimeMillis()
        } catch (t: Throwable) {
            Log.w(TAG, "启动修剪失败", t)
        }
    }

    /**
     * Android 8.0+ 必须先建渠道，否则通知不显示。
     * 渠道重要性用 LOW：常驻通知不响铃、不打扰，只做「正在共享」提示。
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_SERVICE) == null) {
            val channel = NotificationChannel(
                CHANNEL_SERVICE,
                "共享服务",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "显示共享服务正在运行的常驻通知"
            channel.setShowBadge(false)
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "App"

        /** 前台服务常驻通知渠道 */
        const val CHANNEL_SERVICE = "ourspace_service"

        /** 前台服务通知 id */
        const val NOTIFY_ID_SERVICE = 1001
    }
}
