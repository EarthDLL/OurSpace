package com.ourspace.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONObject

/**
 * 本地缓存库（SQLite）。
 *
 * 表结构严格按 docs/API.md 第 8 节：
 *   events(id, owner, ts, type, pkg, label, UNIQUE(owner,ts,type,pkg))
 *   stats (id, owner, ts, pkg, label, ms,       UNIQUE(owner,ts,pkg))
 *   outbox(id, kind, body, created)
 *   live  (owner PK, json, updatedAt)
 *
 * 额外说明（不影响契约）：
 *  - events 多存一列 date（按本地时区切的 YYYY-MM-DD），用于按天查询；
 *  - outbox 多两列 nextRetry / attempts，用于指数退避重试；
 *  - labels 是应用名缓存表，PackageManager 查不到时兜底。
 *
 * UI 一律只读本库，所以离线也能看 7 天历史。每天修剪一次早于 7 天的数据。
 */
class LocalDb private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS events(" +
                "id INTEGER PRIMARY KEY, owner TEXT, ts INTEGER, type TEXT, pkg TEXT, label TEXT, " +
                "date TEXT, UNIQUE(owner,ts,type,pkg))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_owner_date ON events(owner, date, ts)")
        // 关键：SQLite 里 NULL 互不相等，所以 UNIQUE(owner, ts, type, pkg) 对 pkg 为 NULL 的事件
        // （解锁 / 亮屏 / 熄屏 / 开机）完全不起作用——每次增量同步都会把它们再插一遍，
        // 表现就是「瞬间」里每隔 10 秒冒出一条重复的亮屏/熄屏。
        // 用表达式唯一索引把 NULL 归一成空串，INSERT OR IGNORE 才能真正生效。
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_events_dedup " +
                "ON events(owner, ts, type, IFNULL(pkg, ''))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS stats(" +
                "id INTEGER PRIMARY KEY, owner TEXT, ts INTEGER, pkg TEXT, label TEXT, ms INTEGER, " +
                "UNIQUE(owner,ts,pkg))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_stats_owner_ts ON stats(owner, ts)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS outbox(" +
                "id INTEGER PRIMARY KEY, kind TEXT, body TEXT, created INTEGER, " +
                "nextRetry INTEGER, attempts INTEGER)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_outbox_ready ON outbox(nextRetry, id)")
        db.execSQL("CREATE TABLE IF NOT EXISTS live(owner TEXT PRIMARY KEY, json TEXT, updatedAt INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS labels(pkg TEXT PRIMARY KEY, label TEXT)")
        // 增量同步游标：记录「某一天某个 owner 的瞬间已经同步到哪一条、这天是否已封盘」。
        // 有它之后日常同步只拉当天的新事件，过去的天拉一次封盘后永不再拉，
        // 不需要每次都把 7 天历史全量重新下载。
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS sync_cursor(" +
                "owner TEXT, date TEXT, lastTs INTEGER, finalized INTEGER DEFAULT 0, " +
                "PRIMARY KEY(owner, date))"
        )
        // 服务端给的天粒度「应用 -> 时长」聚合（GET /me/stats、/peer/stats）。
        // 必须单独存表：以前是当成一条 ts=当天0点 的统计桶写进 stats，
        // 结果它会被 statsByDay 的 SUM 加上本地真实桶，同一天的时长凭空翻倍，
        // 表现就是「我这边看到的数字比对方看到的大一截」。
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS day_app(" +
                "owner TEXT, date TEXT, pkg TEXT, label TEXT, ms INTEGER, " +
                "PRIMARY KEY(owner, date, pkg))"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 本地库只是缓存，结构变化直接重建，服务端历史可由同步重新拉回
        db.execSQL("DROP TABLE IF EXISTS events")
        db.execSQL("DROP TABLE IF EXISTS stats")
        db.execSQL("DROP TABLE IF EXISTS outbox")
        db.execSQL("DROP TABLE IF EXISTS live")
        db.execSQL("DROP TABLE IF EXISTS labels")
        db.execSQL("DROP TABLE IF EXISTS sync_cursor")
        db.execSQL("DROP TABLE IF EXISTS day_app")
        onCreate(db)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        // 采集线程与 UI 线程都会读写，开启 WAL 降低锁冲突
        try {
            db.enableWriteAheadLogging()
        } catch (t: Throwable) {
            Log.w(TAG, "WAL 开启失败", t)
        }
    }

    // ===================== 事件 =====================

    /**
     * 插入事件。幂等：靠 UNIQUE(owner,ts,type,pkg) + OR IGNORE 去重。
     * @return true 表示确实插入了新行
     */
    @Synchronized
    fun insertEvent(owner: String, e: Event): Boolean {
        return try {
            val cv = ContentValues()
            cv.put("owner", owner)
            cv.put("ts", e.ts)
            cv.put("type", e.type)
            cv.put("pkg", e.pkg)
            cv.put("label", e.label)
            cv.put("date", TimeUtil.dateOf(e.ts))
            val row = writableDatabase.insertWithOnConflict(
                "events", null, cv, SQLiteDatabase.CONFLICT_IGNORE
            )
            row != -1L
        } catch (t: Throwable) {
            Log.w(TAG, "insertEvent 失败", t)
            false
        }
    }

    @Synchronized
    fun insertEvents(owner: String, list: List<Event>): Int {
        if (list.isEmpty()) return 0
        var n = 0
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                for (e in list) {
                    val cv = ContentValues()
                    cv.put("owner", owner)
                    cv.put("ts", e.ts)
                    cv.put("type", e.type)
                    cv.put("pkg", e.pkg)
                    cv.put("label", e.label)
                    cv.put("date", TimeUtil.dateOf(e.ts))
                    if (db.insertWithOnConflict("events", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L) n++
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "insertEvents 失败", t)
        }
        return n
    }

    /** 按 owner + 日期查询事件，ts 倒序 */
    @Synchronized
    fun queryEvents(owner: String, date: String, limit: Int = 2000): List<Event> {
        val out = ArrayList<Event>()
        try {
            readableDatabase.query(
                "events", arrayOf("ts", "type", "pkg", "label"),
                "owner=? AND date=?", arrayOf(owner, date),
                null, null, "ts DESC", limit.toString()
            ).useCursor { c ->
                while (c.moveToNext()) {
                    out.add(
                        Event(
                            ts = c.getLong(0),
                            type = c.getString(1) ?: "",
                            pkg = if (c.isNull(2)) null else c.getString(2),
                            label = if (c.isNull(3)) null else c.getString(3)
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "queryEvents 失败", t)
        }
        return out
    }

    // ===================== 增量同步游标 =====================

    /**
     * 读取增量同步游标。
     * @return (已经同步到的最大 ts, 这一天是否已封盘)
     *         封盘 = 这一天已经过去，内容不会再增加，之后永不重拉。
     */
    @Synchronized
    fun getCursor(owner: String, date: String): Pair<Long, Boolean> {
        try {
            readableDatabase.query(
                "sync_cursor", arrayOf("lastTs", "finalized"),
                "owner=? AND date=?", arrayOf(owner, date),
                null, null, null, "1"
            ).useCursor { c ->
                if (c.moveToFirst()) return Pair(c.getLong(0), c.getInt(1) != 0)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "getCursor 失败", t)
        }
        return Pair(0L, false)
    }

    /** 写入增量同步游标 */
    @Synchronized
    fun setCursor(owner: String, date: String, lastTs: Long, finalized: Boolean) {
        try {
            val cv = ContentValues()
            cv.put("owner", owner)
            cv.put("date", date)
            cv.put("lastTs", lastTs)
            cv.put("finalized", if (finalized) 1 else 0)
            writableDatabase.insertWithOnConflict(
                "sync_cursor", null, cv, SQLiteDatabase.CONFLICT_REPLACE
            )
        } catch (t: Throwable) {
            Log.w(TAG, "setCursor 失败", t)
        }
    }

    // ===================== 统计 =====================

    /** 插入统计桶。同 (owner,ts,pkg) 取较大值，与后端语义一致 */
    @Synchronized
    fun upsertStat(owner: String, s: Stat) {
        try {
            writableDatabase.execSQL(
                "INSERT INTO stats(owner,ts,pkg,label,ms) VALUES(?,?,?,?,?) " +
                    "ON CONFLICT(owner,ts,pkg) DO UPDATE SET ms=max(ms,excluded.ms), " +
                    "label=COALESCE(excluded.label,label)",
                arrayOf(owner, s.ts, s.pkg, s.label ?: s.pkg, s.ms)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "upsertStat 失败", t)
        }
    }

    @Synchronized
    fun upsertStats(owner: String, list: List<Stat>) {
        if (list.isEmpty()) return
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                for (s in list) {
                    db.execSQL(
                        "INSERT INTO stats(owner,ts,pkg,label,ms) VALUES(?,?,?,?,?) " +
                            "ON CONFLICT(owner,ts,pkg) DO UPDATE SET ms=max(ms,excluded.ms), " +
                            "label=COALESCE(excluded.label,label)",
                        arrayOf(owner, s.ts, s.pkg, s.label ?: s.pkg, s.ms)
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "upsertStats 失败", t)
        }
    }

    /**
     * 某天的应用时长排行（同一包跨多个桶求和），倒序。
     * 会过滤掉 DAY_TOTAL_PKG 这种「全天合计」的合成行，避免它出现在应用列表里。
     *
     * 同时并入服务端的天粒度聚合（day_app 表）：两边同一个包取**较大值**。
     *  - 本地桶更全更新 → 用本地；
     *  - 重装后本地是空的、或者那天是本机没采集到的 → 用服务端的；
     * 取 max 而不是相加，正是为了避免同一个包被算两遍。
     */
    @Synchronized
    fun statsByDay(owner: String, date: String, limit: Int = 50): List<Stat> {
        val start = TimeUtil.dayStart(date)
        val end = start + TimeUtil.DAY_MS
        // pkg -> (label, ms)
        val merged = LinkedHashMap<String, Pair<String?, Long>>()
        try {
            readableDatabase.rawQuery(
                "SELECT pkg, COALESCE(MAX(label), pkg) AS lb, SUM(ms) AS total FROM stats " +
                    "WHERE owner=? AND ts>=? AND ts<? AND pkg<>? " +
                    "GROUP BY pkg",
                arrayOf(owner, start.toString(), end.toString(), DAY_TOTAL_PKG)
            ).useCursor { c ->
                while (c.moveToNext()) {
                    val pkg = c.getString(0) ?: continue
                    merged[pkg] = Pair(c.getString(1), c.getLong(2))
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "statsByDay 失败", t)
        }
        // 并入服务端天聚合
        try {
            readableDatabase.rawQuery(
                "SELECT pkg, label, ms FROM day_app WHERE owner=? AND date=?",
                arrayOf(owner, date)
            ).useCursor { c ->
                while (c.moveToNext()) {
                    val pkg = c.getString(0) ?: continue
                    val label = c.getString(1)
                    val ms = c.getLong(2)
                    val cur = merged[pkg]
                    if (cur == null) merged[pkg] = Pair(label, ms)
                    else if (ms > cur.second) merged[pkg] = Pair(label ?: cur.first, ms)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "day_app 读取失败", t)
        }
        return merged.entries
            .sortedByDescending { it.value.second }
            .take(limit)
            .map { Stat(ts = start, pkg = it.key, label = it.value.first, ms = it.value.second) }
    }

    /** 写入服务端给的天粒度应用聚合（同一个包取较大值） */
    @Synchronized
    fun upsertDayApps(owner: String, date: String, list: List<Stat>) {
        if (list.isEmpty()) return
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                for (s in list) {
                    db.execSQL(
                        "INSERT INTO day_app(owner, date, pkg, label, ms) VALUES(?,?,?,?,?) " +
                            "ON CONFLICT(owner, date, pkg) DO UPDATE SET " +
                            "ms = max(day_app.ms, excluded.ms), label = excluded.label",
                        arrayOf(owner, date, s.pkg, s.label, s.ms)
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "upsertDayApps 失败", t)
        }
    }

    /**
     * 某个 10 分钟桶里已经存下的「包 -> 毫秒」。
     * 采集器用它做基数：统计桶写的是累计值，服务重启后要在此基础上继续累加，
     * 否则只写进一个片段，取较大值时会永远停在旧的小数字上。
     */
    @Synchronized
    fun statMsByBucket(owner: String, bucketTs: Long): Map<String, Long> {
        val out = HashMap<String, Long>()
        try {
            readableDatabase.rawQuery(
                "SELECT pkg, SUM(ms) FROM stats WHERE owner=? AND ts=? AND pkg<>? GROUP BY pkg",
                arrayOf(owner, bucketTs.toString(), DAY_TOTAL_PKG)
            ).useCursor { c ->
                while (c.moveToNext()) {
                    val pkg = c.getString(0) ?: continue
                    out[pkg] = c.getLong(1)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "statMsByBucket 失败", t)
        }
        return out
    }

    /**
     * 某天的总屏幕时长。
     * 本地的 10 分钟桶求和 vs 服务端给的全天合计（DAY_TOTAL_PKG 那一条），取较大值。
     * 关键是**不能相加**——那两个数说的是同一件事。
     */
    @Synchronized
    fun totalMsByDay(owner: String, date: String): Long {
        val start = TimeUtil.dayStart(date)
        val end = start + TimeUtil.DAY_MS
        var bucketSum = 0L
        var dayTotal = 0L
        try {
            readableDatabase.rawQuery(
                "SELECT pkg, SUM(ms) AS total FROM stats WHERE owner=? AND ts>=? AND ts<? GROUP BY pkg",
                arrayOf(owner, start.toString(), end.toString())
            ).useCursor { c ->
                while (c.moveToNext()) {
                    val total = c.getLong(1)
                    if (c.getString(0) == DAY_TOTAL_PKG) dayTotal = maxOf(dayTotal, total)
                    else bucketSum += total
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "totalMsByDay 失败", t)
        }
        // 服务端天聚合里也可能有本机没有的包（重装后），把它们也算进来
        var dayAppSum = 0L
        try {
            readableDatabase.rawQuery(
                "SELECT COALESCE(SUM(ms), 0) FROM day_app WHERE owner=? AND date=?",
                arrayOf(owner, date)
            ).useCursor { c -> if (c.moveToFirst()) dayAppSum = c.getLong(0) }
        } catch (t: Throwable) {
            Log.w(TAG, "day_app 求和失败", t)
        }
        val local = maxOf(bucketSum, dayAppSum)
        return maxOf(local, dayTotal)
    }

    /** 近 N 天每天的总时长，用于柱状图。返回按日期升序的 LinkedHashMap */
    @Synchronized
    fun dailyTotals(owner: String, days: Int): Map<String, Long> {
        // stats 表没有 date 列，按天区间逐个求和即可（最多 7 天，开销很小）
        val out = LinkedHashMap<String, Long>()
        for (d in TimeUtil.lastDays(days)) {
            out[d] = totalMsByDay(owner, d)
        }
        return out
    }

    /** 某天某事件类型的次数，例如解锁次数 */
    @Synchronized
    fun countEvents(owner: String, date: String, type: String): Int =
        queryLong(
            "SELECT COUNT(*) FROM events WHERE owner=? AND date=? AND type=?",
            arrayOf(owner, date, type)
        ).toInt()

    // ===================== live =====================

    @Synchronized
    fun saveLive(owner: String, live: Live) {
        try {
            val cv = ContentValues()
            cv.put("owner", owner)
            cv.put("json", live.toLocalJson().toString())
            cv.put("updatedAt", live.updatedAt)
            writableDatabase.insertWithOnConflict("live", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (t: Throwable) {
            Log.w(TAG, "saveLive 失败", t)
        }
    }

    @Synchronized
    fun loadLive(owner: String): Live? {
        var text: String? = null
        try {
            readableDatabase.query("live", arrayOf("json"), "owner=?", arrayOf(owner), null, null, null)
                .useCursor { c -> if (c.moveToFirst()) text = c.getString(0) }
        } catch (t: Throwable) {
            Log.w(TAG, "loadLive 失败", t)
        }
        return Live.fromLocalJson(text)
    }

    // ===================== outbox =====================

    /** 写入 outbox。kind ∈ event | stat | live | share */
    @Synchronized
    fun enqueue(kind: String, body: JSONObject, now: Long = System.currentTimeMillis()) {
        try {
            val cv = ContentValues()
            cv.put("kind", kind)
            cv.put("body", body.toString())
            cv.put("created", now)
            cv.put("nextRetry", 0L)
            cv.put("attempts", 0)
            writableDatabase.insert("outbox", null, cv)
        } catch (t: Throwable) {
            Log.w(TAG, "enqueue 失败", t)
        }
    }

    data class OutboxRow(val id: Long, val kind: String, val body: String)

    /** 取出到期的 outbox 记录（nextRetry<=now），按 id 升序 */
    @Synchronized
    fun peekOutbox(now: Long, limit: Int = 1000): List<OutboxRow> {
        val out = ArrayList<OutboxRow>()
        try {
            readableDatabase.rawQuery(
                "SELECT id, kind, body FROM outbox WHERE nextRetry<=? ORDER BY id ASC LIMIT ?",
                arrayOf(now.toString(), limit.toString())
            ).useCursor { c ->
                while (c.moveToNext()) {
                    out.add(OutboxRow(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: ""))
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "peekOutbox 失败", t)
        }
        return out
    }

    /** 删除已成功上传的记录 */
    @Synchronized
    fun deleteOutbox(ids: List<Long>) {
        if (ids.isEmpty()) return
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                for (id in ids) db.delete("outbox", "id=?", arrayOf(id.toString()))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "deleteOutbox 失败", t)
        }
    }

    /** 上传失败：累加 attempts 并按指数退避设置 nextRetry（由调用方算好） */
    @Synchronized
    fun markFailed(ids: List<Long>, nextRetry: Long, maxAttempts: Int = MAX_ATTEMPTS) {
        if (ids.isEmpty()) return
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                for (id in ids) {
                    db.execSQL(
                        "UPDATE outbox SET attempts=attempts+1, nextRetry=? WHERE id=? AND attempts<?",
                        arrayOf(nextRetry, id, maxAttempts)
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "markFailed 失败", t)
        }
    }

    /** 清掉重试次数已耗尽的老记录，避免 outbox 无限增长 */
    @Synchronized
    fun dropExhausted(maxAttempts: Int = MAX_ATTEMPTS) {
        try {
            writableDatabase.delete("outbox", "attempts>=?", arrayOf(maxAttempts.toString()))
        } catch (t: Throwable) {
            Log.w(TAG, "dropExhausted 失败", t)
        }
    }

    @Synchronized
    fun outboxCount(): Int =
        queryLong("SELECT COUNT(*) FROM outbox", emptyArray()).toInt()

    /**
     * 删除早于 7 天的数据。启动时与每天一次各调用一次。
     */
    @Synchronized
    fun trimOld(retentionDays: Int = RETENTION_DAYS): Int {
        val cutoff = TimeUtil.todayStart() - (retentionDays - 1).toLong() * TimeUtil.DAY_MS
        var deleted = 0
        try {
            deleted += writableDatabase.delete("events", "ts<?", arrayOf(cutoff.toString()))
            deleted += writableDatabase.delete("stats", "ts<?", arrayOf(cutoff.toString()))
            // outbox 里长期发不出去的旧数据也不再重试
            deleted += writableDatabase.delete(
                "outbox", "created<?", arrayOf((cutoff - TimeUtil.DAY_MS).toString())
            )
            Log.i(TAG, "trimOld: 删除 $deleted 行, cutoff=$cutoff")
        } catch (t: Throwable) {
            Log.w(TAG, "trimOld 失败", t)
        }
        return deleted
    }

    @Synchronized
    fun clearAll() {
        try {
            val db = writableDatabase
            db.execSQL("DELETE FROM events")
            db.execSQL("DELETE FROM stats")
            db.execSQL("DELETE FROM outbox")
            db.execSQL("DELETE FROM live")
        } catch (t: Throwable) {
            Log.w(TAG, "clearAll 失败", t)
        }
    }

    // ===================== 应用名缓存 =====================

    @Synchronized
    fun cacheLabel(pkg: String, label: String) {
        try {
            val cv = ContentValues()
            cv.put("pkg", pkg)
            cv.put("label", label)
            writableDatabase.insertWithOnConflict("labels", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (t: Throwable) {
            Log.w(TAG, "cacheLabel 失败", t)
        }
    }

    @Synchronized
    fun cachedLabel(pkg: String): String? {
        var label: String? = null
        try {
            readableDatabase.query("labels", arrayOf("label"), "pkg=?", arrayOf(pkg), null, null, null)
                .useCursor { c -> if (c.moveToFirst()) label = c.getString(0) }
        } catch (t: Throwable) {
            Log.w(TAG, "cachedLabel 失败", t)
        }
        return label
    }

    // ===================== 通用工具 =====================

    private fun queryLong(sql: String, args: Array<String>): Long {
        var v = 0L
        try {
            readableDatabase.rawQuery(sql, args).useCursor { c ->
                if (c.moveToFirst() && !c.isNull(0)) v = c.getLong(0)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "queryLong 失败: $sql", t)
        }
        return v
    }

    /**
     * Cursor 的关闭语法糖。
     * 刻意不叫 use()，避开 kotlin.io 里 Closeable.use 的重载歧义。
     */
    private inline fun <T> Cursor.useCursor(block: (Cursor) -> T): T {
        try {
            return block(this)
        } finally {
            close()
        }
    }

    companion object {
        private const val TAG = "LocalDb"
        private const val NAME = "ourspace.db"
        private const val VERSION = 4
        const val RETENTION_DAYS = 7
        private const val MAX_ATTEMPTS = 20

        const val OWNER_ME = "me"
        const val OWNER_PEER = "peer"

        /**
         * 合成行的伪包名：服务端 /me/daily、/peer/daily 只给天粒度总时长，
         * 落库时用这个 pkg 存一条当天的合计桶，供柱状图和「当天总时长」使用。
         * 它不会出现在应用排行里（statsByDay 会过滤掉）。
         */
        const val DAY_TOTAL_PKG = "__day_total__"

        @Volatile
        private var instance: LocalDb? = null

        fun get(ctx: Context): LocalDb {
            val cur = instance
            if (cur != null) return cur
            synchronized(LocalDb::class.java) {
                var db = instance
                if (db == null) {
                    db = LocalDb(ctx)
                    instance = db
                }
                return db
            }
        }
    }
}
