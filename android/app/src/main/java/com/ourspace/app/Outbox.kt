package com.ourspace.app

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * 上传队列。
 *
 * 设计要点（对齐 docs/API.md 第 4.3 / 9 节）：
 *  - 采集到的事件 / 统计桶 / 实时状态先写本地 outbox，再由这里攒批 POST /api/v1/push；
 *  - 每批最多 500 条 events、200 条 stats；
 *  - 上传失败保留在 outbox，指数退避重试，间隔上限 30 分钟；
 *  - 共享暂停时本类不会被调用（调用方 CollectorService 会直接跳过），
 *    因此暂停期间不会产生任何网络请求。
 */
class Outbox(ctx: Context) {

    private val db = LocalDb.get(ctx)
    private val prefs = Prefs.get()

    /** 最近一次上传结果，供设置页显示 */
    @Volatile
    var lastStatus: String = ""
        private set

    @Volatile
    var lastSuccessAt: Long = 0L
        private set

    /** 当前退避间隔：失败一次翻倍，上限 30 分钟 */
    @Volatile
    private var backoffMs: Long = 0L

    @Volatile
    private var lastFailureAt: Long = 0L

    /** 结果：本次是否真的发出了网络请求 */
    data class Result(val sent: Boolean, val rows: Int, val message: String)

    /**
     * 只推送「共享开关」标记，不碰任何业务数据。
     *
     * 暂停共享时正常上传/下行全部停掉，但「我暂停了」这件事必须让服务端和对方知道；
     * 否则对方永远看不到你关了开关，而且离线时开关变更会一直卡在 outbox 里发不出去。
     */
    @Synchronized
    fun flushShareOnly(api: ApiClient): Boolean {
        return try {
            val rows = db.peekOutbox(System.currentTimeMillis(), MAX_PEEK)
            if (rows.none { it.kind == OutboxKind.SHARE }) return true
            flushShareMarker(api, rows)
        } catch (t: Throwable) {
            Log.w(TAG, "推送共享标记失败", t)
            false
        }
    }

    /**
     * 尝试把 outbox 里攒下的数据上传完。
     * @param hasToken 没有 token 时直接跳过（还没登录）
     */
    @Synchronized
    fun flush(api: ApiClient, hasToken: Boolean): Result {
        if (!hasToken) {
            return Result(false, 0, "尚未登录")
        }
        if (prefs.serverUrl.isEmpty() || prefs.payloadSecret.isEmpty()) {
            return Result(false, 0, "缺少服务器地址或配对密钥")
        }
        val now = System.currentTimeMillis()
        if (backoffMs > 0 && lastSuccessAt > 0 && now - lastFailureAt < backoffMs) {
            // 仍在退避窗口内，本轮不发
            return Result(false, 0, "等待重试（${backoffMs / 1000}s 后）")
        }

        val rows = db.peekOutbox(now, MAX_PEEK)
        if (rows.isEmpty()) {
            lastStatus = "已同步"
            return Result(false, 0, "已同步")
        }

        // 先处理 share 标记（暂停状态变更），再批量推业务数据
        val shareSent = flushShareMarker(api, rows)

        val events = ArrayList<Event>()
        val stats = ArrayList<Stat>()
        val live = prefs.sharing && prefs.isLoggedIn
        var liveJson: JSONObject? = null

        val usableIds = ArrayList<Long>(rows.size)
        for (r in rows) {
            val obj = try {
                JSONObject(r.body)
            } catch (t: Throwable) {
                // 脏数据直接丢弃
                db.deleteOutbox(listOf(r.id))
                continue
            }
            when (r.kind) {
                OutboxKind.EVENT -> {
                    if (events.size < MAX_EVENTS) {
                        events.add(Event.fromJson(obj))
                        usableIds.add(r.id)
                    }
                }
                OutboxKind.STAT -> {
                    if (stats.size < MAX_STATS) {
                        stats.add(Stat.fromJson(obj))
                        usableIds.add(r.id)
                    }
                }
                OutboxKind.LIVE -> {
                    liveJson = obj
                    usableIds.add(r.id)
                }
                else -> db.deleteOutbox(listOf(r.id))
            }
        }

        if (events.isEmpty() && stats.isEmpty() && liveJson == null) {
            return Result(shareSent, 0, if (shareSent) "已同步共享状态" else lastStatus)
        }

        val payload = PushPayload()
        payload.events.addAll(events)
        payload.stats.addAll(stats)
        payload.live = if (live) Live.fromLocalJson(liveJson?.toString()) else null
        payload.sharing = prefs.sharing

        return try {
            val resp = api.push(payload)
            db.deleteOutbox(usableIds)
            onSuccess()
            // 后端顺手算的逆地理编码结果：写回本地 live，
            // 这样概览页「我的状态」也能显示地址（以前只有对方的地址会被算出来）
            if (!resp.address.isNullOrEmpty()) {
                val prev = db.loadLive(LocalDb.OWNER_ME)
                if (prev != null) {
                    db.saveLive(
                        LocalDb.OWNER_ME,
                        prev.copy(address = resp.address, addressAt = resp.addressAt)
                    )
                }
            }
            val n = usableIds.size
            if (n > 0) lastStatus = "已上传 $n 条"
            Result(true, n, lastStatus)
        } catch (t: Throwable) {
            val msg = (t as? ApiException)?.message ?: (t.message ?: "上传失败")
            onFailure()
            // 整批保留在 outbox，下一轮重试；退避时间写入每条记录
            db.markFailed(usableIds, System.currentTimeMillis() + backoffMs)
            lastStatus = "上传失败：$msg（${backoffMs / 1000}s 后重试）"
            Result(false, 0, lastStatus)
        }
    }

    /** 单独发送 POST /share 的标记；暂停状态下也允许发这一条（用户主动操作） */
    private fun flushShareMarker(api: ApiClient, rows: List<LocalDb.OutboxRow>): Boolean {
        var sent = false
        for (r in rows) {
            if (r.kind != OutboxKind.SHARE) continue
            try {
                val obj = JSONObject(r.body)
                api.setSharing(obj.optBoolean("sharing", true))
                db.deleteOutbox(listOf(r.id))
                sent = true
            } catch (t: Throwable) {
                Log.w(TAG, "共享状态上报失败", t)
                onFailure()
            }
        }
        return sent
    }

    private fun onSuccess() {
        backoffMs = 0L
        lastFailureAt = 0L
        lastSuccessAt = System.currentTimeMillis()
        db.dropExhausted()
    }

    private fun onFailure() {
        lastFailureAt = System.currentTimeMillis()
        backoffMs = if (backoffMs <= 0L) MIN_BACKOFF_MS else (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
    }

    companion object {
        private const val TAG = "Outbox"
        const val MAX_EVENTS = 500
        const val MAX_STATS = 200

        private const val MAX_PEEK = 2000
        private const val MIN_BACKOFF_MS = 60_000L
        private const val MAX_BACKOFF_MS = 30 * 60 * 1000L
    }
}

/** 一次同步的结果，供 UI 显示 */
data class SyncResult(val ok: Boolean, val message: String, val at: Long = System.currentTimeMillis())

/**
 * 下行同步：把服务端数据落到本地库（owner='me' / 'peer'），
 * 保证离线也能看 7 天历史。UI 只读本地库，不直接发网络请求。
 */
class SyncEngine(ctx: Context) {

    private val db = LocalDb.get(ctx)
    private val prefs = Prefs.get()

    /**
     * 拉取自己与对方最近 7 天的数据。
     * 只在共享开启时调用（暂停期间不发任何网络请求）。
     */
    fun sync(api: ApiClient, days: Int = LocalDb.RETENTION_DAYS): SyncResult {
        if (!prefs.sharing) return SyncResult(false, "共享已暂停，本次未发起网络请求")

        val dates = TimeUtil.lastDays(days)
        val today = TimeUtil.today()
        val errors = ArrayList<String>()

        // ===== 事件：按游标增量拉 =====
        // 封盘过的过去日期直接跳过，一个请求都不发；
        // 当天带 since 只拉新事件（没有新东西就返回空数组，代价极小）。
        for (d in dates) {
            for (owner in listOf(LocalDb.OWNER_ME, LocalDb.OWNER_PEER)) {
                val who = if (owner == LocalDb.OWNER_ME) "我的" else "对方"
                try {
                    syncEventsOfDay(api, owner, d, today)
                } catch (t: Throwable) {
                    errors.add("$who 瞬间 $d：${msg(t)}")
                }
            }
        }
        try {
            for (item in api.myDaily(days)) {
                // /me/daily 只有天粒度总和：写成当天起点的一条合成桶，供柱状图使用
                if (item.totalMs > 0L) {
                    db.upsertStat(
                        LocalDb.OWNER_ME,
                        Stat(TimeUtil.dayStart(item.date), LocalDb.DAY_TOTAL_PKG, "全天合计", item.totalMs)
                    )
                }
                // 今天那条同时落到 live 上，供概览显示
                if (item.date == TimeUtil.today() && item.totalMs > 0L) {
                    val prev = db.loadLive(LocalDb.OWNER_ME)
                    val merged = (prev ?: Live()).copy(
                        todayScreenMs = item.totalMs,
                        todayUnlocks = item.unlocks,
                        updatedAt = if ((prev?.updatedAt ?: 0L) > 0L) prev!!.updatedAt
                        else System.currentTimeMillis()
                    )
                    db.saveLive(LocalDb.OWNER_ME, merged)
                }
            }
        } catch (t: Throwable) {
            errors.add("我的每日汇总：${msg(t)}")
        }

        // ===== 对方 =====
        try {
            val peerLive = api.peerLive()
            val live = Live.fromPeerJson(peerLive)
            db.saveLive(LocalDb.OWNER_PEER, live)
            if (!live.sharing) {
                // 对方暂停共享：live 里 sharing=false，界面上会显示“已暂停共享”
                Log.i(TAG, "对方已暂停共享")
            }
        } catch (t: Throwable) {
            errors.add("对方状态：${msg(t)}")
        }

        // 对方实时状态

        // 对方每日汇总：写成合成桶供柱状图使用（应用明细以 /peer/stats 为准）
        val peerDaily = try {
            api.peerDaily(days)
        } catch (t: Throwable) {
            errors.add("对方每日汇总：${msg(t)}")
            emptyList()
        }
        for (item in peerDaily) {
            if (item.totalMs > 0L) {
                db.upsertStat(
                    LocalDb.OWNER_PEER,
                    Stat(TimeUtil.dayStart(item.date), LocalDb.DAY_TOTAL_PKG, "全天合计", item.totalMs)
                )
            }
        }

        prefs.lastSyncAt = System.currentTimeMillis()
        return if (errors.isEmpty()) {
            SyncResult(true, "已同步（增量，最近 ${dates.size} 天）")
        } else {
            SyncResult(false, errors.first() + if (errors.size > 1) " 等 ${errors.size} 项失败" else "")
        }
    }

    /**
     * 只同步「今天」的事件（自己和对方各一个很小的增量请求）。
     * 每 10 秒的节拍调的是这个，而不是完整的 sync()。
     */
    fun syncTodayEvents(api: ApiClient): Boolean {
        if (!prefs.sharing) return false
        val today = TimeUtil.today()
        var ok = false
        for (owner in listOf(LocalDb.OWNER_ME, LocalDb.OWNER_PEER)) {
            try {
                if (syncEventsOfDay(api, owner, today, today)) ok = true
            } catch (t: Throwable) {
                Log.w(TAG, "同步今天的事件失败：$owner", t)
            }
        }
        return ok
    }

    /**
     * 增量拉取某一天某个 owner 的事件。
     *
     * @return true 表示确实发了请求；false 表示这天已封盘、直接跳过。
     */
    private fun syncEventsOfDay(api: ApiClient, owner: String, date: String, today: String): Boolean {
        val (lastTs, finalized) = db.getCursor(owner, date)
        // 过去的日子只要封盘过就永不再拉：那天的数据不会再变了
        if (finalized && date != today) return false

        val list = if (owner == LocalDb.OWNER_ME) api.myEvents(date, lastTs)
        else api.peerEvents(date, lastTs)
        db.insertEvents(owner, list)
        val maxTs = list.maxOfOrNull { it.ts } ?: lastTs
        // 今天不封盘（还会继续产生新事件）；过去的日子拉完这一次就封盘
        db.setCursor(owner, date, maxOf(lastTs, maxTs), date != today)
        return true
    }

    /**
     * 只拉对方的实时状态（一个很小的请求）。
     *
     * 每 10 秒跑一次的是这个，而不是完整的 7 天历史同步：
     * 完整同步要循环 7 天 × (自己 + 对方) 的事件，一天按 10 秒跑一次是十几万请求、
     * 几百 MB 流量，手机电池和数据都扛不住，而历史数据本来也不需要那么实时。
     */
    fun syncLive(api: ApiClient): Boolean {
        if (!prefs.sharing) return false
        return try {
            val live = Live.fromPeerJson(api.peerLive())
            db.saveLive(LocalDb.OWNER_PEER, live)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "拉取对方状态失败", t)
            false
        }
    }

    /**
     * 拉取某一天某个 owner 的应用时长排行（服务端聚合结果）。
     * 服务端不可用时返回空列表，界面回落到本地库的桶。
     */
    fun statsFor(api: ApiClient, owner: String, date: String): StatsResponse? = try {
        if (owner == LocalDb.OWNER_PEER) api.peerStats(date) else api.myStats(date)
    } catch (t: Throwable) {
        Log.w(TAG, "statsFor 失败", t)
        null
    }

    private fun msg(t: Throwable): String = (t as? ApiException)?.message ?: (t.message ?: "失败")

    companion object {
        private const val TAG = "SyncEngine"
    }
}
