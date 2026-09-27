package com.ourspace.app

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.usage.UsageStatsManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log

/**
 * 权限与系统设置引导。
 *
 * 逐项对应设置页的引导列表：
 *   1. 使用情况访问权限 —— 用 UsageStatsManager.queryUsageStats 尝试查询 + AppOps 校验；
 *      跳转 Settings.ACTION_USAGE_ACCESS_SETTINGS
 *   2. 定位权限 —— 前台 ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION 运行时申请；
 *      后台 ACCESS_BACKGROUND_LOCATION 在 Android 11+ 只能引导到应用详情页手动选择「始终允许」
 *   3. 通知权限 —— Android 13+ POST_NOTIFICATIONS 运行时申请
 *   4. 忽略电池优化 —— ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS，
 *      用 PowerManager.isIgnoringBatteryOptimizations 校验
 */
object PermissionHelper {

    private const val TAG = "PermissionHelper"

    const val REQ_NOTIFICATION = 2001
    const val REQ_LOCATION = 2002

    // ===================== 使用情况访问权限 =====================

    /** 是否已授予「使用情况访问权限」 */
    fun hasUsageAccess(ctx: Context): Boolean {
        // 先查 AppOps（最权威）
        try {
            val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            if (appOps != null) {
                val mode: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appOps.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(),
                        ctx.packageName
                    )
                } else {
                    @Suppress("DEPRECATION")
                    appOps.checkOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(),
                        ctx.packageName
                    )
                }
                if (mode == AppOpsManager.MODE_ALLOWED) return true
                if (mode == AppOpsManager.MODE_DEFAULT) {
                    // 默认态再看清单里的权限是否被授予
                    return ctx.checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) ==
                        PackageManager.PERMISSION_GRANTED
                }
                if (mode == AppOpsManager.MODE_IGNORED || mode == AppOpsManager.MODE_ERRORED) return false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "检查使用情况权限失败", t)
        }
        // 兜底：真实查一次
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            val now = System.currentTimeMillis()
            val list = usm?.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60_000, now)
            !list.isNullOrEmpty()
        } catch (t: Throwable) {
            false
        }
    }

    /** 跳到「使用情况访问权限」设置页 */
    fun openUsageAccess(ctx: Context) {
        safeStart(ctx, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    // ===================== 定位权限 =====================

    /** 前台定位（精确或粗略任一即可）是否已授予 */
    fun hasForegroundLocation(ctx: Context): Boolean {
        val fine = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /** 后台定位是否已授予（Android 10 以下默认跟随前台） */
    fun hasBackgroundLocation(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return hasForegroundLocation(ctx)
        return ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** 需要运行时申请的前台定位权限数组 */
    fun foregroundLocationPermissions(): Array<String> = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    /** 跳应用详情页：Android 11+ 后台定位只能在系统页里选「始终允许」 */
    fun openAppDetails(ctx: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", ctx.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "打开应用详情页失败", t)
            safeStart(ctx, Intent(Settings.ACTION_SETTINGS))
        }
    }

    // ===================== 通知权限 =====================

    /** Android 13 以下默认允许 */
    fun hasNotificationPermission(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** 需要在 Activity 里申请（API 33+） */
    fun requestNotification(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
        } catch (t: Throwable) {
            Log.w(TAG, "申请通知权限失败", t)
        }
    }

    fun requestForegroundLocation(activity: Activity) {
        try {
            activity.requestPermissions(foregroundLocationPermissions(), REQ_LOCATION)
        } catch (t: Throwable) {
            Log.w(TAG, "申请定位权限失败", t)
        }
    }

    // ===================== 忽略电池优化 =====================

    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
        return try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true
        } catch (t: Throwable) {
            Log.w(TAG, "检查电池优化失败", t)
            false
        }
    }

    /**
     * 弹出「忽略电池优化」系统对话框。
     * Android 11+ 需要同时在清单里声明 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS。
     */
    fun requestIgnoreBatteryOptimizations(ctx: Context) {
        try {
            @Suppress("BatteryLife")
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.fromParts("package", ctx.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "请求忽略电池优化失败，回退到电池优化列表", t)
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (t2: Throwable) {
                Log.w(TAG, "打开电池优化列表也失败", t2)
            }
        }
    }

    // ===================== 自启动 / 后台运行 =====================

    /**
     * 尝试打开厂商的自启动管理页（各家 ROM 的 action 都不一样，失败就返回 false，
     * 由设置页展示纯文字说明兜底）。
     */
    fun openAutoStartSettings(ctx: Context): Boolean {
        val candidates = listOf(
            "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager/.appcontrol.activity.StartupAppControlActivity",
            "com.coloros.safecenter/.startupapp.StartupAppListActivity",
            "com.oppo.safe/.permission.startup.StartupAppListActivity",
            "com.vivo.permissionmanager/.activity.BgStartUpManagerActivity",
            "com.iqoo.secure/.safeguard.PurviewTabActivity",
            "com.samsung.android.lool/com.samsung.android.sm.ui.battery.BatteryActivity",
            "com.oneplus.security/.chainlaunch.view.ChainLaunchAppListActivity",
            "com.letv.android.letvsafe/.AutobootManageActivity",
            "com.huawei.systemmanager/.optimize.process.ProtectActivity"
        )
        for (cls in candidates) {
            try {
                val slash = cls.indexOf('/')
                if (slash <= 0) continue
                val pkg = cls.substring(0, slash)
                var name = cls.substring(slash + 1)
                if (name.startsWith(".")) name = pkg + name
                val intent = Intent().apply {
                    setClassName(pkg, name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (ctx.packageManager.resolveActivity(intent, 0) != null) {
                    ctx.startActivity(intent)
                    return true
                }
            } catch (t: Throwable) {
                // 换下一个候选
            }
        }
        // 兜底：应用详情页
        openAppDetails(ctx)
        return false
    }

    private fun safeStart(ctx: Context, intent: Intent) {
        try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            Log.w(TAG, "打开设置页失败: ${intent.action}", t)
        }
    }
}
