package com.ourspace.app

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 时间工具。日期字符串一律 YYYY-MM-DD，按本机时区切分
 * （服务端也按它自己的本地时区切，两边通常是同一时区的家用部署）。
 */
object TimeUtil {

    const val DAY_MS = 24L * 60 * 60 * 1000

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val dateTimeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)

    /** 今天（本机时区）的 YYYY-MM-DD */
    fun today(): String = dayFmt.format(Date())

    /** 把毫秒时间戳格式化成本地日期 */
    fun dateOf(ts: Long): String = dayFmt.format(Date(ts))

    /** HH:mm:ss，用于瞬间列表 */
    fun timeOf(ts: Long): String = timeFmt.format(Date(ts))

    /** MM-dd HH:mm，用于更新时间 */
    fun dateTimeOf(ts: Long): String =
        if (ts <= 0L) "—" else dateTimeFmt.format(Date(ts))

    /** 某天 00:00:00.000 的毫秒值 */
    fun dayStart(date: String): Long {
        val cal = Calendar.getInstance()
        val d = try {
            dayFmt.parse(date)
        } catch (t: Throwable) {
            null
        } ?: return 0L
        cal.time = d
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** 今天的 00:00:00.000 */
    fun todayStart(): Long = dayStart(today())

    /** 最近 n 天的日期列表（含今天），按日期升序 */
    fun lastDays(n: Int): List<String> {
        val out = ArrayList<String>(n)
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_YEAR, -(n - 1))
        for (i in 0 until n) {
            out.add(dayFmt.format(cal.time))
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return out
    }

    /** 列表里显示用的短标签：今天 / 昨天 / MM-dd */
    fun shortLabel(date: String): String {
        val today = today()
        if (date == today) return "今天"
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -1)
        if (date == dayFmt.format(cal.time)) return "昨天"
        return if (date.length >= 10) date.substring(5) else date
    }

    /** 毫秒 -> "1小时23分" / "23分" / "12秒" */
    fun humanMs(ms: Long): String {
        if (ms <= 0L) return "0 分钟"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "${h}小时${m}分"
            m > 0 -> "${m}分${s}秒"
            else -> "${s}秒"
        }
    }
}

/**
 * 与 docs/API.md 第 3 节完全一致的数据结构。
 * 所有 ts 都是 Unix 毫秒；统计桶的 ts 必须对齐到 10 分钟整点。
 */

/** 事件类型，见 API.md 第 3 节 */
object EventType {
    const val UNLOCK = "unlock"
    const val SCREEN_ON = "screen_on"
    const val SCREEN_OFF = "screen_off"
    const val APP_OPEN = "app_open"
    const val APP_CLOSE = "app_close"
    const val BOOT = "boot"

    /** 事件的中文描述 */
    fun describe(type: String, label: String?): String = when (type) {
        UNLOCK -> "解锁了手机"
        SCREEN_ON -> "点亮屏幕"
        SCREEN_OFF -> "熄灭屏幕"
        APP_OPEN -> "打开「${label ?: "未知应用"}」"
        APP_CLOSE -> "关闭「${label ?: "未知应用"}」"
        BOOT -> "手机开机"
        else -> type
    }
}

/** 瞬间记录 */
data class Event(
    val ts: Long,
    val type: String,
    val pkg: String?,
    val label: String?
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("ts", ts)
        o.put("type", type)
        // pkg / label 允许为 null，服务端幂等键是 (uid, ts, type, pkg)
        o.put("pkg", pkg ?: JSONObject.NULL)
        o.put("label", label ?: JSONObject.NULL)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Event = Event(
            ts = o.optLong("ts", 0L),
            type = o.optString("type", ""),
            pkg = optStringOrNull(o, "pkg"),
            label = optStringOrNull(o, "label")
        )

        fun fromArray(arr: JSONArray?): List<Event> {
            if (arr == null) return emptyList()
            val list = ArrayList<Event>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(fromJson(o))
            }
            return list
        }

        /** JSONObject.optString 会把 JSON null 变成 "null" 字符串，这里自己处理 */
        fun optStringOrNull(o: JSONObject, key: String): String? {
            if (o.isNull(key)) return null
            val s = o.optString(key, "")
            return if (s.isEmpty() || s == "null") null else s
        }
    }
}

/** 10 分钟粒度的应用使用统计桶 */
data class Stat(
    val ts: Long,
    val pkg: String,
    val label: String?,
    val ms: Long
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("ts", ts)
        o.put("pkg", pkg)
        o.put("label", label ?: pkg)
        o.put("ms", ms)
        return o
    }

    companion object {
        const val BUCKET_MS = 600_000L

        /** ts 必须对齐到 10 分钟整点 */
        fun alignBucket(ts: Long): Long = ts / BUCKET_MS * BUCKET_MS

        fun fromJson(o: JSONObject): Stat = Stat(
            ts = o.optLong("ts", 0L),
            pkg = o.optString("pkg", ""),
            label = Event.optStringOrNull(o, "label"),
            ms = o.optLong("ms", 0L)
        )

        fun fromArray(arr: JSONArray?): List<Stat> {
            if (arr == null) return emptyList()
            val list = ArrayList<Stat>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(fromJson(o))
            }
            return list
        }
    }
}

/** 实时状态：不落历史，只保留最新一条。address / addressAt 由后端逆地理编码返回 */
data class Live(
    val battery: Int = -1,
    val charging: Boolean = false,
    val wifi: String? = null,
    val net: String = "none",           // wifi | mobile | none
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val locAcc: Double = 0.0,
    val screenOn: Boolean = false,
    /** 当前正在前台使用的应用名（读不到权限时为 null） */
    val currentApp: String? = null,
    val updatedAt: Long = 0L,
    val address: String? = null,
    val addressAt: Long = 0L,
    val todayScreenMs: Long = 0L,
    val todayUnlocks: Int = 0,
    val todayTopApps: List<Stat> = emptyList(),
    /** 对方是否在共享（GET /peer/live 的 sharing 字段）；本地自己这条恒为 true */
    val sharing: Boolean = true
) {
    /** 上报给 /push 的 live 字段 */
    fun toPushJson(): JSONObject {
        val o = JSONObject()
        o.put("battery", battery)
        o.put("charging", charging)
        o.put("wifi", wifi ?: JSONObject.NULL)
        o.put("net", net)
        o.put("lat", lat)
        o.put("lon", lon)
        o.put("locAcc", locAcc)
        o.put("screenOn", screenOn)
        o.put("currentApp", currentApp ?: JSONObject.NULL)
        o.put("updatedAt", updatedAt)
        return o
    }

    /** 存本地 live 表用的完整 JSON */
    fun toLocalJson(): JSONObject {
        val o = toPushJson()
        o.put("sharing", sharing)
        o.put("address", address ?: JSONObject.NULL)
        o.put("addressAt", addressAt)
        o.put("todayScreenMs", todayScreenMs)
        o.put("todayUnlocks", todayUnlocks)
        val tops = JSONArray()
        for (s in todayTopApps) tops.put(s.toJson())
        o.put("todayTopApps", tops)
        return o
    }

    companion object {
        private fun optDoubleOrNull(o: JSONObject, key: String): Double? {
            if (o.isNull(key) || !o.has(key)) return null
            val d = o.optDouble(key, Double.NaN)
            return if (d.isNaN()) null else d
        }

        /** 解析 GET /peer/live 的响应体 */
        fun fromPeerJson(o: JSONObject): Live = Live(
            battery = o.optInt("battery", -1),
            charging = o.optBoolean("charging", false),
            wifi = Event.optStringOrNull(o, "wifi"),
            net = o.optString("net", "none"),
            lat = o.optDouble("lat", 0.0),
            lon = o.optDouble("lon", 0.0),
            locAcc = o.optDouble("locAcc", 0.0),
            screenOn = o.optBoolean("screenOn", false),
            currentApp = Event.optStringOrNull(o, "currentApp"),
            updatedAt = o.optLong("updatedAt", 0L),
            address = Event.optStringOrNull(o, "address"),
            addressAt = o.optLong("addressAt", 0L),
            todayScreenMs = o.optLong("todayScreenMs", 0L),
            todayUnlocks = o.optInt("todayUnlocks", 0),
            todayTopApps = Stat.fromArray(o.optJSONArray("todayTopApps")),
            sharing = o.optBoolean("sharing", true)
        )

        /** 从本地 live 表的 json 字段还原 */
        fun fromLocalJson(s: String?): Live? {
            if (s.isNullOrEmpty()) return null
            return try {
                fromPeerJson(JSONObject(s))
            } catch (t: Throwable) {
                null
            }
        }
    }
}

/** GET /peer/stats 与 /me/stats 的响应 */
data class StatsResponse(
    val date: String,
    val totalMs: Long,
    val apps: List<Stat>,
    val hourly: List<Long>
) {
    companion object {
        fun fromJson(o: JSONObject): StatsResponse {
            val hourly = ArrayList<Long>(24)
            val arr = o.optJSONArray("hourly")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val h = arr.optJSONObject(i) ?: continue
                    hourly.add(h.optLong("ms", 0L))
                }
            }
            while (hourly.size < 24) hourly.add(0L)
            return StatsResponse(
                date = o.optString("date", ""),
                totalMs = o.optLong("totalMs", 0L),
                apps = Stat.fromArray(o.optJSONArray("apps")),
                hourly = hourly
            )
        }
    }
}

/** GET /me/daily 与 /peer/daily 的单日条目 */
data class DailyItem(
    val date: String,
    val totalMs: Long,
    val unlocks: Int,
    val topAppLabel: String?
) {
    companion object {
        fun fromArray(arr: JSONArray?): List<DailyItem> {
            if (arr == null) return emptyList()
            val list = ArrayList<DailyItem>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(
                    DailyItem(
                        date = o.optString("date", ""),
                        totalMs = o.optLong("totalMs", 0L),
                        unlocks = o.optInt("unlocks", 0),
                        topAppLabel = Event.optStringOrNull(o, "topAppLabel")
                    )
                )
            }
            return list
        }
    }
}

/** 账号 / 配对信息，来自 login 与 GET /me */
data class AccountInfo(
    val uid: Long,
    val username: String,
    val sharing: Boolean,
    val partnerUid: Long,
    val partnerName: String,
    val partnerSharing: Boolean,
    val hasPartner: Boolean,
    val retentionDays: Int,
    val serverTime: Long
) {
    val partnerText: String
        get() = if (hasPartner) partnerName.ifEmpty { "已配对" } else "尚未配对"

    companion object {
        fun fromJson(o: JSONObject): AccountInfo {
            val p = o.optJSONObject("partner")
            return AccountInfo(
                uid = o.optLong("uid", 0L),
                username = o.optString("username", ""),
                sharing = o.optBoolean("sharing", true),
                partnerUid = p?.optLong("uid", 0L) ?: 0L,
                partnerName = p?.optString("username", "") ?: "",
                partnerSharing = p?.optBoolean("sharing", false) ?: false,
                hasPartner = p != null,
                retentionDays = o.optInt("retentionDays", 7),
                serverTime = o.optLong("serverTime", 0L)
            )
        }
    }
}

/** POST /api/v1/push 的请求体 */
class PushPayload {
    val events = ArrayList<Event>()
    val stats = ArrayList<Stat>()
    var live: Live? = null
    var sharing: Boolean = true

    fun toJson(): JSONObject {
        val o = JSONObject()
        val ea = JSONArray()
        for (e in events) ea.put(e.toJson())
        o.put("events", ea)
        val sa = JSONArray()
        for (s in stats) sa.put(s.toJson())
        o.put("stats", sa)
        o.put("live", live?.toPushJson() ?: JSONObject.NULL)
        o.put("sharing", sharing)
        return o
    }

    val isEmpty: Boolean
        get() = events.isEmpty() && stats.isEmpty() && live == null
}

/** outbox 记录类型 */
object OutboxKind {
    const val EVENT = "event"
    const val STAT = "stat"
    const val LIVE = "live"
    const val SHARE = "share"
}
