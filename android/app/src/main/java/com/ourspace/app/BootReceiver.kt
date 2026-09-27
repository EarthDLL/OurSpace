package com.ourspace.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * 开机 / 应用更新后重启采集服务。
 *
 * 关于「记 boot 事件」，这里踩过两个坑，所以逻辑看起来有点绕：
 *  1. 最早把 BOOT_COMPLETED / LOCKED_BOOT_COMPLETED / MY_PACKAGE_REPLACED 一视同仁地记成 boot，
 *     结果每装一次 APK 就凭空多一条「手机开机」；
 *  2. 把 MY_PACKAGE_REPLACED 排除掉之后仍然有多余记录——
 *     实测荣耀 MagicOS 会在应用被重新拉起时也发 BOOT_COMPLETED。
 *
 * 所以最终判定标准不看广播名，而是看**系统开机以来的时长**：
 * 只有 elapsedRealtime() 很小（刚开机）才认为真的开机，否则只重启服务、不记事件。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        val bootLike = action == Intent.ACTION_BOOT_COMPLETED ||
                action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        val isPackageReplaced = action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!bootLike && !isPackageReplaced) return

        val prefs = Prefs.peek() ?: return
        if (!prefs.isLoggedIn) {
            Log.i(TAG, "未登录，不自动启动采集服务")
            return
        }

        // 关键判定：距系统开机不足 BOOT_WINDOW_MS 才算真开机
        val sinceBootMs = SystemClock.elapsedRealtime()
        val realBoot = bootLike && sinceBootMs < BOOT_WINDOW_MS

        if (realBoot) {
            try {
                val db = LocalDb.get(context)
                val now = System.currentTimeMillis()
                val ev = Event(now, EventType.BOOT, null, null)
                db.insertEvent(LocalDb.OWNER_ME, ev)
                if (prefs.sharing) {
                    // 本地记录与上传记录用同一个时间戳，避免两边对不上
                    db.enqueue(OutboxKind.EVENT, ev.toJson())
                }
                Log.i(TAG, "记录开机事件（开机 ${sinceBootMs / 1000} 秒）")
            } catch (t: Throwable) {
                Log.w(TAG, "写 boot 事件失败", t)
            }
        } else {
            Log.i(
                TAG,
                "收到 $action 但系统已运行 ${sinceBootMs / 1000} 秒，判定不是真开机：只重启采集服务"
            )
        }

        try {
            CollectorService.start(context)
        } catch (t: Throwable) {
            // Android 12+ 在部分场景禁止后台启动前台服务，这里静默失败，
            // 用户下次打开 App 时 MainActivity 会再次拉起服务。
            Log.w(TAG, "启动采集服务失败", t)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"

        /** 距系统开机不到这个时间才算真开机（毫秒） */
        private const val BOOT_WINDOW_MS = 60_000L
    }
}
