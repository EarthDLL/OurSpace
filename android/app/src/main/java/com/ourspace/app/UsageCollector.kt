package com.ourspace.app

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * 应用使用时长统计（600 秒一个采集周期，见 API.md 第 9 节）。
 *
 * 做法：用 UsageStatsManager.queryEvents 取出事件流，把
 *   ACTIVITY_RESUMED / MOVE_TO_FOREGROUND  -> ACTIVITY_PAUSED / MOVE_TO_BACKGROUND
 * 配对累加得到每个包的前台毫秒数，并对跨桶的情况做截断：
 *   - 从上次运行时间起算，累加时长记到「当前所属的 10 分钟桶」；
 *   - 上一次还在前台、这次运行仍未见关闭的包，时长会带到本轮继续累计。
 *
 * 统计桶 ts 一律对齐 floor(now/600000)*600000；同 (owner,ts,pkg) 本地库与
 * 服务端都取较大值，所以同一桶内多次运行不会重复计数，只会取最大值。
 *
 * 顺带产出 app_open / app_close 事件（API.md 的 type 定义），写进 events 表。
 */
class UsageCollector(
    private val ctx: Context,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS
) {

    /** 每个包最近一次进入前台的时间（会话起点） */
    private val openSince = HashMap<String, Long>()

    /** 每个包最近一次进入前台时用的应用名 */
    private val openLabels = HashMap<String, String>()

    /** 每个包最近一次收到 RESUMED 的时间：用来识别应用内部的 Activity 抖动 */
    private val lastOpenAt = HashMap<String, Long>()

    /** 每个包「已经结算到哪个时刻」：保证同一段前台时间只累加一次 */
    private val lastAccountedAt = HashMap<String, Long>()

    /** 当前前台的应用包名 */
    private var fgPkg: String? = null

    /** 已经发过 app_open 的包（延迟发送用，见 OPEN 分支说明） */
    private val openEmitted = HashSet<String>()

    /** 应用名缓存：包名 -> 显示名 */
    private val labelCache = HashMap<String, String>()

    @Volatile
    private var lastRunAt: Long = 0L

    /** 是否需要运行本轮采集（由服务每 10 秒调用一次，内部再判断有没有到 10 分钟的统计周期） */
    fun due(now: Long = System.currentTimeMillis()): Boolean =
        lastRunAt == 0L || now - lastRunAt >= intervalMs

    /**
     * 采集从「上次采集截止时间」到现在的使用情况。
     *
     * 断点续采：`lastRunAt` 存在 SharedPreferences 里，服务被杀掉几小时后重启，
     * 会从断点开始把空档按 10 分钟桶逐格补回来（最多回看保留期 7 天），
     * 而不是像以前那样只看最近 10 分钟、把停机期间的时长整段丢掉。
     *
     * 必须在单线程池里调用（内部有 HashMap 状态）。
     */
    fun collect(now: Long = System.currentTimeMillis()): Result {
        val prefs = Prefs.peek()
        val persisted = prefs?.usageLastRunAt ?: 0L
        val wantFrom = if (persisted > 0L) persisted else now - intervalMs
        // 只往前走，不回头重扫：每个事件在整个流程里只处理一次，
        // 会话状态机才可能正确（之前为了凑桶的累计值去回扫当前桶，会把同一段时长反复累计）。
        val from = maxOf(wantFrom, now - MAX_BACKFILL_MS)
        val to = now
        val bucketTs = Stat.alignBucket(now - 1)
        lastRunAt = now
        prefs?.usageLastRunAt = now

        val events = queryEvents(from, to)

        // bucketTs -> (pkg -> 前台毫秒)
        val byBucket = HashMap<Long, HashMap<String, Long>>()
        val labels = HashMap<String, String>()
        val outEvents = ArrayList<Event>()

        // 把 [a, b) 这段前台时长按 10 分钟桶拆开分别累加。
        // 第一次碰到某个桶时，先用本地库里已经存下的累计值做基数——
        // 桶是按「累计值 + 取较大值」写的，服务重启后必须接着累加，
        // 否则只会发出一个片段，取较大值时会永远停在旧的小数字上。
        fun addSpan(pkg: String, label: String, a: Long, b: Long) {
            if (b <= a) return
            labels[pkg] = label
            var cur = Stat.alignBucket(a)
            while (cur < b) {
                val end = cur + intervalMs
                val s = maxOf(a, cur)
                val e = minOf(b, end)
                if (e > s) {
                    val m = byBucket.getOrPut(cur) {
                        HashMap(LocalDb.get(ctx).statMsByBucket(LocalDb.OWNER_ME, cur))
                    }
                    m[pkg] = (m[pkg] ?: 0L) + (e - s)
                }
                cur = end
            }
        }

        // 结束一个会话：结算时长 + （够长才）发 open/close 事件
        fun closeSession(pkg: String, endTs: Long) {
            val st = openSince.remove(pkg) ?: return
            val lbl = openLabels.remove(pkg) ?: resolveLabel(pkg)
            lastOpenAt.remove(pkg)
            val emitted = openEmitted.remove(pkg)
            val start = lastAccountedAt.remove(pkg) ?: st
            // 时长照样计入统计（统计要的是总前台时间），但不够长就不记「瞬间」事件
            if (endTs > start) addSpan(pkg, lbl, start, endTs)
            if (endTs - st < MIN_SESSION_MS) return
            if (!emitted) outEvents.add(Event(st, EventType.APP_OPEN, pkg, lbl))
            outEvents.add(Event(endTs, EventType.APP_CLOSE, pkg, lbl))
        }

        for (ev in events) {
            val pkg = ev.pkg ?: continue
            val ts = ev.ts
            val label = resolveLabel(pkg)
            when (ev.kind) {
                Normalized.OPEN -> {
                    lastOpenAt[pkg] = ts
                    if (openSince[pkg] == null) {
                        // 从别的应用切过来：先把上一个前台的会话收尾
                        val prev = fgPkg
                        if (prev != null && prev != pkg) closeSession(prev, ts)
                        openSince[pkg] = ts
                        openLabels[pkg] = label
                        labels[pkg] = label
                        fgPkg = pkg
                        // 这里**故意先不发 app_open**：
                        // 有些包（系统界面、桌面）会在同一毫秒里 open+close，
                        // 先记下来就会留下一堆「使用了 0 秒」。
                        // 等会话确认够长（或结束时）再补发。
                    }
                    // 同一个包再收到 OPEN，说明只是应用内部换了 Activity
                    // （登录页 → 主页 → 设置页都会各发一次 RESUMED）。
                    // 这**不是**重新打开应用，绝不能重开会话。
                }

                Normalized.CLOSE -> {
                    if (openSince[pkg] == null) continue // 孤儿 close：重复的 STOPPED，或窗口前就打开了
                    val lastOpen = lastOpenAt[pkg] ?: 0L
                    // 紧跟在同一包 OPEN 之后的 CLOSE 也是 Activity 切换（PAUSE/STOPPED），不算会话结束
                    if (ts - lastOpen <= BRIDGE_MS) continue
                    closeSession(pkg, ts)
                    if (fgPkg == pkg) fgPkg = null
                }
            }
        }

        // 仍然停在前台的包：结算「上次结算之后到 now」这一段。
        // 用 lastAccountedAt 当游标，保证同一段时间只算一次。
        for (pkg in openSince.keys.toList()) {
            val st = openSince[pkg] ?: continue
            val lbl = openLabels[pkg] ?: resolveLabel(pkg)
            labels[pkg] = lbl
            val start = lastAccountedAt[pkg] ?: st
            if (to > start) addSpan(pkg, lbl, start, to)
            lastAccountedAt[pkg] = to
            // 会话已经持续够久，补发那条「打开」（见 OPEN 分支的说明）
            if (!openEmitted.contains(pkg) && to - st >= MIN_SESSION_MS) {
                outEvents.add(Event(st, EventType.APP_OPEN, pkg, lbl))
                openEmitted.add(pkg)
            }
        }

        // 本轮既没有事件、也没有仍在前台的包，就没什么可写的
        if (outEvents.isEmpty() && openSince.isEmpty()) {
            return Result(bucketTs, emptyList(), emptyList())
        }

        val stats = ArrayList<Stat>()
        for ((bucket, m) in byBucket) {
            for ((pkg, total) in m) {
                if (total <= 0L) continue
                stats.add(
                    Stat(
                        ts = bucket,
                        pkg = pkg,
                        label = labels[pkg] ?: resolveLabel(pkg),
                        ms = total
                    )
                )
            }
        }

        return Result(bucketTs, stats, outEvents)
    }

    data class Result(
        val bucketTs: Long,
        val stats: List<Stat>,
        val events: List<Event>
    )

    // ===================== 内部 =====================

    /** 采集时从 UsageEvents 里抠出来的最小信息（原始 Event 对象会被复用，必须立刻拷出来） */
    private data class RawEvent(val ts: Long, val pkg: String?, val kind: Int)

    private fun queryEvents(from: Long, to: Long): List<RawEvent> {
        val out = ArrayList<RawEvent>()
        try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return out
            val ue = usm.queryEvents(from, to) ?: return out
            val e = UsageEvents.Event()
            while (ue.hasNextEvent()) {
                ue.getNextEvent(e)
                val kind = normalize(e.eventType)
                if (kind == Normalized.OTHER) continue
                out.add(RawEvent(e.timeStamp, e.packageName, kind))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "queryEvents 失败", t)
        }
        return out
    }

    /** 把不同 API 等级的两套常量归一到 OPEN / CLOSE */
    private fun normalize(type: Int): Int = when (type) {
        UsageEvents.Event.MOVE_TO_FOREGROUND,
        UsageEvents.Event.ACTIVITY_RESUMED -> Normalized.OPEN

        UsageEvents.Event.MOVE_TO_BACKGROUND,
        UsageEvents.Event.ACTIVITY_PAUSED,
        UsageEvents.Event.ACTIVITY_STOPPED -> Normalized.CLOSE

        else -> Normalized.OTHER
    }

    private object Normalized {
        const val OPEN = 1
        const val CLOSE = 2
        const val OTHER = 0
    }

    /** 用 PackageManager 查应用显示名；先查内存缓存，再查本地库缓存 */
    fun resolveLabel(pkg: String): String {
        labelCache[pkg]?.let { return it }
        LocalDb.get(ctx).cachedLabel(pkg)?.let {
            labelCache[pkg] = it
            return it
        }
        val label = try {
            val pm = ctx.packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (t: Throwable) {
            pkg
        }
        labelCache[pkg] = label
        if (label != pkg) LocalDb.get(ctx).cacheLabel(pkg, label)
        return label
    }

    companion object {
        private const val TAG = "UsageCollector"

        /**
         * 采集周期：60 秒。
         *
         * 注意「统计桶」仍然是 10 分钟对齐的（见 API.md），这里只是采集/落库更勤快：
         *  - 应用开关事件（瞬间）能在 1 分钟内出现在列表里，而不是等 10 分钟；
         *  - 统计桶写的是「该桶到目前为止的累计值」，取较大值写入，同一个桶反复写不会重复计数。
         */
        const val DEFAULT_INTERVAL_MS = 60_000L

        /** 服务被杀后最多往回补多久（与保留期一致，7 天） */
        private const val MAX_BACKFILL_MS = 7L * 24 * 60 * 60 * 1000

        /**
         * Activity 抖动的桥接窗口：CLOSE 距离同一个包最近一次 OPEN 不超过这个时间，
         * 就认为只是应用内部换 Activity，不算会话结束。
         * 否则「登录页→主页→设置页」这种切换会切出一堆「使用了 0 秒」。
         */
        private const val BRIDGE_MS = 2_000L

        /**
         * 低于这个时长的会话不记为「瞬间」事件（统计时长照算）。
         * 系统界面、桌面这类包经常在同一毫秒里 open+close，记下来就是一堆「使用了 0 秒」。
         */
        private const val MIN_SESSION_MS = 1_000L
    }
}
