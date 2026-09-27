package com.ourspace.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

/**
 * 采集前台服务（常驻通知「正在共享」，START_STICKY）。
 *
 * 节拍（对应 API.md 第 9 节）：
 *   - 每 10 秒：采集实时状态（电量/WiFi/网络/经纬度/屏幕）→ 写 live 表 + 入 outbox；
 *               同时尝试上传 outbox、拉取服务端数据（仅在共享开启时）。
 *   - 每 600 秒：用 UsageStatsManager 统计每个包的前台毫秒 → 写 stats 表 + 入 outbox；
 *               顺带产出 app_open / app_close 事件。
 *   - 实时事件：动态注册 ACTION_USER_PRESENT（解锁，受“解锁记录”开关控制）、
 *               ACTION_SCREEN_ON / ACTION_SCREEN_OFF → 写 events 表 + 入 outbox。
 *   - 每天一次：修剪早于 7 天的本地数据。
 *
 * 省电策略：
 *   - 不使用 AlarmManager 高频唤醒，也不用定时广播；只用 Handler 的 10 秒消息循环，
 *     由前台服务常驻保活；
 *   - 不长期持有 wake lock；
 *   - UsageStatsManager 查询放在单线程池里跑；
 *   - **共享暂停时不产生任何网络请求**：只写本地库。
 *
 * 前台服务类型：清单里声明 location|dataSync，运行时按是否已授予定位权限动态选择，
 * 没有定位权限时只传 FOREGROUND_SERVICE_TYPE_DATA_SYNC，
 * 避免 Android 14+ 因缺少定位权限抛 SecurityException。
 */
class CollectorService : Service() {

    private lateinit var prefs: Prefs
    private lateinit var db: LocalDb
    private lateinit var statusCollector: StatusCollector
    private lateinit var usageCollector: UsageCollector
    private lateinit var outbox: Outbox
    private lateinit var syncEngine: SyncEngine

    private val main = Handler(Looper.getMainLooper())

    /** 统计查询专用单线程池：UsageStatsManager 查询绝不放在主线程 */
    private var statsExecutor: ScheduledExecutorService? = null

    /** 网络专用单线程池：避免阻塞采集节拍 */
    private var netExecutor: ScheduledExecutorService? = null

    /** 网络任务是否正在执行：防止一次同步超过一个节拍时请求堆积 */
    @Volatile
    private var netBusy = false

    /** 本轮同步的失败原因（仅网络线程内使用），用于写进 Prefs 供界面显示 */
    @Volatile
    private var lastErr = ""

    /** 下行连续失败次数 */
    @Volatile
    private var downlinkFailStreak = 0

    /** 下行退避：在这个时间点之前不再尝试拉取对方数据（0 表示不限制） */
    @Volatile
    private var downlinkNextTryAt = 0L

    @Volatile
    private var running = false

    private var receiver: BroadcastReceiver? = null
    private var receiverRegistered = false

    private var tickCount = 0L
    private var lastTrimAt = 0L

    /** 上次写进常驻通知的共享状态，用于判断是否需要刷新文案 */
    private var lastNotifiedSharing: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs.get()
        db = LocalDb.get(this)
        statusCollector = StatusCollector(this)
        usageCollector = UsageCollector(this, UsageCollector.DEFAULT_INTERVAL_MS)
        outbox = Outbox(this)
        syncEngine = SyncEngine(this)
        statsExecutor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ourspace-stats").apply { isDaemon = true }
        }
        netExecutor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ourspace-net").apply { isDaemon = true }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()

        if (intent?.action == ACTION_STOP) {
            stopSelfSafely()
            return START_NOT_STICKY
        }

        // 用户主动打开 App、切换共享开关时都会重新 start 服务，
        // 顺手清掉下行退避，让「刚恢复网络 / 刚打开 App」这种情况能立刻重试一次。
        downlinkFailStreak = 0
        downlinkNextTryAt = 0L

        if (!running) {
            running = true
            registerEventReceiver()
            // 启动时先修剪一次过期数据
            try {
                db.trimOld(LocalDb.RETENTION_DAYS)
                prefs.lastTrimAt = System.currentTimeMillis()
                lastTrimAt = System.currentTimeMillis()
            } catch (t: Throwable) {
                Log.w(TAG, "启动修剪失败", t)
            }
            scheduleTick(0L)
        }

        // START_STICKY：被系统杀掉后自动重建
        return START_STICKY
    }

    /**
     * 任务被划掉时返回 START_STICKY，让系统稍后重启服务。
     * 这里不 stopSelf，保证用户「最近任务」里划掉 App 后共享仍然继续。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "任务被移除，保持前台服务继续采集")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        unregisterEventReceiver()
        statsExecutor?.shutdownNow()
        netExecutor?.shutdownNow()
        statsExecutor = null
        netExecutor = null
        Log.i(TAG, "采集服务已停止")
        super.onDestroy()
    }

    // ===================== 前台通知 =====================

    private fun startForegroundCompat() {
        lastNotifiedSharing = prefs.sharing
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val type = foregroundServiceType()
                startForeground(App.NOTIFY_ID_SERVICE, notification, type)
            } else {
                startForeground(App.NOTIFY_ID_SERVICE, notification)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground 失败，退化为普通通知", t)
            try {
                val nm = getSystemService(NotificationManager::class.java)
                nm?.notify(App.NOTIFY_ID_SERVICE, notification)
            } catch (t2: Throwable) {
                Log.w(TAG, "发送通知也失败", t2)
            }
        }
    }

    /**
     * 动态选择前台服务类型：
     *  - 有定位权限 → location|dataSync
     *  - 没有定位权限 → 只报 dataSync（否则 Android 14+ 会抛 SecurityException）
     *
     * 注意：即使清单里写了 location，也只有在已授予定位权限时才允许带上这个类型，
     * 否则系统会直接拒绝 startForeground。
     */
    private fun foregroundServiceType(): Int {
        val dataSync = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        return if (PermissionHelper.hasForegroundLocation(this)) {
            dataSync or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            dataSync
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, App.CHANNEL_SERVICE)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder.setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("OurSpace 运行中")
            .setContentText(
                when {
                    !prefs.sharing -> "已暂停上传，仅在本机记录"
                    prefs.lastSyncOkAt <= 0L -> "正在共享 · 等待首次同步"
                    else -> "正在共享 · 上次同步 ${TimeUtil.timeOf(prefs.lastSyncOkAt)}"
                }
            )
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
        return builder.build()
    }

    private fun updateNotification() {
        try {
            // 通知刷新是线程安全的，从网络线程调用也可以
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(App.NOTIFY_ID_SERVICE, buildNotification())
        } catch (t: Throwable) {
            Log.w(TAG, "刷新通知失败", t)
        }
    }

    // ===================== 事件广播 =====================

    private fun registerEventReceiver() {
        if (receiverRegistered) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                val now = System.currentTimeMillis()
                when (action) {
                    Intent.ACTION_SCREEN_ON -> recordEvent(Event(now, EventType.SCREEN_ON, null, null))
                    Intent.ACTION_SCREEN_OFF -> recordEvent(Event(now, EventType.SCREEN_OFF, null, null))
                    Intent.ACTION_USER_PRESENT -> {
                        // 解锁记录默认关闭；关闭时既不写库也不上传
                        if (prefs.unlockRecord) {
                            recordEvent(Event(now, EventType.UNLOCK, null, null))
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            // 解锁需要 RECEIVE_BOOT_COMPLETED 之外的系统广播，属于受保护广播，静态注册无效，必须动态注册
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // 只接收系统广播，显式声明不对外导出（lint 也要求显式指定）
                registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(r, filter)
            }
            receiver = r
            receiverRegistered = true
        } catch (t: Throwable) {
            Log.w(TAG, "注册事件广播失败", t)
            // 某些 ROM 可能不支持带 flags 的注册方式，退回普通注册
            try {
                registerReceiver(r, filter)
                receiver = r
                receiverRegistered = true
            } catch (t2: Throwable) {
                Log.w(TAG, "退回普通注册也失败", t2)
            }
        }
    }

    private fun unregisterEventReceiver() {
        val r = receiver ?: return
        try {
            unregisterReceiver(r)
        } catch (t: Throwable) {
            Log.w(TAG, "注销事件广播失败", t)
        }
        receiver = null
        receiverRegistered = false
    }

    /** 写本地库；共享开启时同时入 outbox 等待上传 */
    private fun recordEvent(e: Event) {
        try {
            db.insertEvent(LocalDb.OWNER_ME, e)
            if (prefs.sharing) {
                db.enqueue(OutboxKind.EVENT, e.toJson())
            }
        } catch (t: Throwable) {
            Log.w(TAG, "记录事件失败", t)
        }
    }

    // ===================== 10 秒节拍 =====================

    private fun scheduleTick(delayMs: Long) {
        main.postDelayed({ tick() }, delayMs)
    }

    private fun tick() {
        if (!running) return
        tickCount++

        // 1) 实时状态：每个节拍都采集
        collectStatus()

        // 2) 应用使用统计：函数内部判断是否到 600 秒
        collectUsageIfDue()

        // 3) 共享开关状态变化时刷新常驻通知文案（即使处于暂停、不发网络请求）
        if (lastNotifiedSharing != prefs.sharing) {
            lastNotifiedSharing = prefs.sharing
            updateNotification()
        }

        // 4) 上传 / 下行同步
        //
        // 注意：tick() 是跑在主线程上的（scheduleTick 用的是 main.postDelayed），
        // 所以这里绝对不能直接发网络请求——否则一定抛 NetworkOnMainThreadException。
        // 之前就是直接调用的，结果上传和下行实际上从来没成功过一次。
        // 现在统一丢到 netExecutor 单线程池里执行，并用 netBusy 防止请求堆积
        // （一次同步超过 10 秒时，下一个节拍直接跳过而不是排队）。
        if (prefs.sharing && prefs.isLoggedIn) {
            val doFullSync = tickCount % FULL_SYNC_EVERY_TICKS == 0L
            val ex = netExecutor
            if (ex != null && !netBusy) {
                netBusy = true
                ex.execute {
                    try {
                        if (prefs.sharing && prefs.isLoggedIn) {
                            lastErr = ""
                            val ok = flushOutbox()
                            val nowMs = System.currentTimeMillis()
                            if (nowMs >= downlinkNextTryAt) {
                                // 到点了才真的拉对方数据
                                var dlOk = doSyncLive()
                                if (!doSyncTodayEvents()) dlOk = false
                                if (doFullSync) doSync()
                                noteDownlinkResult(dlOk)
                                noteSyncResult(ok && dlOk, lastErr)
                            } else {
                                // 退避窗口内：跳过下行，但保留上一次的错误提示，
                                // 不能因为「跳过」就把界面上的失败状态误报成正常
                                Log.d(
                                    TAG,
                                    "下行退避中，跳过本轮（${(downlinkNextTryAt - nowMs) / 1000} 秒后重试）"
                                )
                                if (!ok) noteSyncResult(false, lastErr)
                            }
                        }
                    } finally {
                        netBusy = false
                    }
                }
            }
        } else if (!prefs.sharing) {
            // 暂停共享：业务数据一个字节都不发，但仍要把「我暂停了」告诉服务端，
            // 否则对方永远不知道你关了开关（离线时开关变更会一直卡在 outbox 里发不出去）。
            val ex = netExecutor
            if (ex != null && !netBusy && prefs.isLoggedIn) {
                netBusy = true
                ex.execute {
                    try {
                        val api = apiOrNull()
                        if (api != null) outbox.flushShareOnly(api)
                    } catch (t: Throwable) {
                        Log.w(TAG, "推送共享状态失败", t)
                    } finally {
                        netBusy = false
                    }
                }
            }
        }

        // 4) 每天修剪一次
        if (System.currentTimeMillis() - lastTrimAt >= TRIM_INTERVAL_MS) {
            lastTrimAt = System.currentTimeMillis()
            try {
                db.trimOld(LocalDb.RETENTION_DAYS)
                prefs.lastTrimAt = lastTrimAt
            } catch (t: Throwable) {
                Log.w(TAG, "每日修剪失败", t)
            }
        }

        scheduleTick(TICK_MS)
    }

    private fun collectStatus() {
        try {
            val live = statusCollector.collect()
            db.saveLive(LocalDb.OWNER_ME, live)
            if (prefs.sharing) {
                db.enqueue(OutboxKind.LIVE, live.toPushJson())
            }
        } catch (t: Throwable) {
            Log.w(TAG, "实时状态采集失败", t)
        }
    }

    private fun collectUsageIfDue() {
        if (!usageCollector.due()) return
        val ex = statsExecutor ?: return
        ex.execute {
            try {
                val result = usageCollector.collect()
                for (s in result.stats) {
                    db.upsertStat(LocalDb.OWNER_ME, s)
                    if (prefs.sharing) db.enqueue(OutboxKind.STAT, s.toJson())
                }
                for (e in result.events) {
                    // 只有确实新插入的才进上传队列：
                    // 现在每 60 秒会回扫当前 10 分钟桶，重复事件会被唯一索引挡掉，
                    // 不能因此又把重复项塞进 outbox。
                    if (db.insertEvent(LocalDb.OWNER_ME, e) && prefs.sharing) {
                        db.enqueue(OutboxKind.EVENT, e.toJson())
                    }
                }
                if (result.stats.isNotEmpty() || result.events.isNotEmpty()) {
                    Log.i(
                        TAG,
                        "统计桶 ${result.bucketTs}: ${result.stats.size} 个包, ${result.events.size} 条事件"
                    )
                }
                // 攒够一批就立刻上传，不必等到下一个 10 秒节拍
                if (prefs.sharing && prefs.isLoggedIn) {
                    val ex2 = netExecutor
                    if (ex2 != null) ex2.execute { flushOutbox() }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "使用统计采集失败", t)
            }
        }
    }

    /** 上传攒下的数据。@return true 表示本轮没有发生网络异常 */
    private fun flushOutbox(): Boolean {
        val api = apiOrNull() ?: run { lastErr = "未配置服务器地址"; return false }
        return try {
            val r = outbox.flush(api, prefs.token.isNotEmpty())
            if (r.sent) {
                Log.d(TAG, r.message)
                updateNotification()
            }
            true
        } catch (t: Throwable) {
            lastErr = friendly(t)
            Log.w(TAG, "上传失败", t)
            false
        }
    }

    /** 完整历史下行同步（7 天 events/stats/daily），每 5 分钟一次 */
    private fun doSync() {
        val api = apiOrNull() ?: return
        try {
            val r = syncEngine.sync(api)
            if (r.ok) {
                Log.i(TAG, "下行同步完成：${r.message}")
            } else {
                Log.w(TAG, "下行同步失败：${r.message}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "下行同步异常", t)
        }
    }

    /** 只拉对方的实时状态，每 10 秒一次。@return true 表示没有网络异常 */
    private fun doSyncLive(): Boolean {
        val api = apiOrNull() ?: run { lastErr = "未配置服务器地址"; return false }
        return try {
            val ok = syncEngine.syncLive(api)
            if (!ok && lastErr.isEmpty()) lastErr = "拉取对方状态失败"
            ok
        } catch (t: Throwable) {
            lastErr = friendly(t)
            Log.w(TAG, "拉取对方状态异常", t)
            false
        }
    }

    /**
     * 增量同步双方「今天」的瞬间记录，每 10 秒一次。
     * 客户端带游标（我已经拿到哪一条了），服务端只回更新的；没有新记录就是空数组。
     * @return true 表示没有网络异常
     */
    private fun doSyncTodayEvents(): Boolean {
        val api = apiOrNull() ?: run { lastErr = "未配置服务器地址"; return false }
        return try {
            val ok = syncEngine.syncTodayEvents(api)
            if (!ok && lastErr.isEmpty()) lastErr = "同步今天的瞬间失败"
            ok
        } catch (t: Throwable) {
            lastErr = friendly(t)
            Log.w(TAG, "同步今天的瞬间异常", t)
            false
        }
    }

    /**
     * 下行失败退避。
     *
     * 断网时不算退避的话，每 10 秒就会发起一轮连接、每次 10 秒超时，
     * 既费电又毫无意义。这里连续失败按 30s → 60s → 3min → 5min 逐级拉长，
     * 成功一次立刻清零；用户重新打开 App 也会清零（见 onStartCommand）。
     */
    private fun noteDownlinkResult(ok: Boolean) {
        if (ok) {
            downlinkFailStreak = 0
            downlinkNextTryAt = 0L
            return
        }
        downlinkFailStreak++
        val idx = minOf(downlinkFailStreak - 1, DOWNLINK_BACKOFF_MS.size - 1)
        downlinkNextTryAt = System.currentTimeMillis() + DOWNLINK_BACKOFF_MS[idx]
        Log.w(TAG, "下行第 $downlinkFailStreak 次失败，${DOWNLINK_BACKOFF_MS[idx] / 1000} 秒后重试")
    }

    /** 记录最近一次同步结果，供概览页显示「同步正常 / 同步失败：原因」 */
    private fun noteSyncResult(ok: Boolean, err: String) {        val now = System.currentTimeMillis()
        if (ok) {
            prefs.lastSyncOkAt = now
            prefs.lastSyncError = ""
        } else {
            prefs.lastSyncErrorAt = now
            prefs.lastSyncError = err.ifEmpty { "未知错误" }
        }
    }

    /** 把底层异常翻译成用户能看懂的一句话 */
    private fun friendly(t: Throwable): String {
        val m = t.message ?: "未知错误"
        return when {
            m.contains("failed to connect") || m.contains("Connection refused") ||
                m.contains("UnknownHost") || m.contains("timed out") ||
                m.contains("timeout") || m.contains("No route to host") -> "连不上服务器"
            m.contains("unauthorized") || m.contains("登录已失效") -> "登录已失效"
            else -> m
        }
    }

    private fun apiOrNull(): ApiClient? {
        if (prefs.serverUrl.isEmpty()) return null
        return ApiClient(prefs.serverUrl, prefs.payloadSecret, prefs.token)
    }

    private fun stopSelfSafely() {
        try {
            main.removeCallbacksAndMessages(null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "停止前台失败", t)
        }
        stopSelf()
    }

    companion object {
        private const val TAG = "CollectorService"

        /** 实时状态采集 + 上传周期：10 秒 */
        const val TICK_MS = 10_000L

        /**
         * 完整历史同步间隔（按节拍数换算）：1 拍 = 10 秒，30 拍 = 5 分钟。
         * 只拉对方实时状态是每拍都做的，见 doSyncLive()。
         */
        private const val FULL_SYNC_EVERY_TICKS = 30L

        /** 下行断网退避序列（毫秒）：连续失败时逐级拉长，最长 5 分钟 */
        private val DOWNLINK_BACKOFF_MS = longArrayOf(30_000L, 60_000L, 180_000L, 300_000L)

        /** 本地库修剪间隔：每天一次 */
        private const val TRIM_INTERVAL_MS = 24L * 60 * 60 * 1000

        const val ACTION_STOP = "com.ourspace.app.action.STOP_COLLECTOR"

        /** 启动采集服务（幂等，重复调用不会重建） */
        fun start(ctx: Context) {
            try {
                val intent = Intent(ctx, CollectorService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            } catch (t: Throwable) {
                // Android 12+ 后台启动前台服务可能被拒，交给下次打开 App 时再启动
                Log.w(TAG, "启动采集服务失败", t)
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, CollectorService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "停止采集服务失败", t)
            }
        }
    }
}
