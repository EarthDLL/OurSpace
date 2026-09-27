package com.ourspace.app

import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/**
 * 后端 HTTP 客户端。只用 java.net.HttpURLConnection，不引入 OkHttp。
 *
 * 统一处理：
 *  - 所有业务请求/响应走 AES-GCM 信封（Crypto.kt），只有 /health 是明文；
 *  - 请求头 Authorization / Content-Type / X-Client: android；
 *  - 出错时抛出 ApiException(code, message)，UI 层直接展示 message。
 */
class ApiClient(
    @Volatile var baseUrl: String,
    @Volatile var payloadSecret: String,
    @Volatile var token: String
) {

    data class LoginResponse(
        val token: String,
        val uid: Long,
        val username: String,
        val account: AccountInfo,
        /** 服务端协议版本；老服务端不返回这个字段时为 0 */
        val protocolVersion: Int,
        /** 服务端软件版本，仅用于展示 */
        val serverVersion: String
    )

    data class PushResponse(
        val acceptedEvents: Int,
        val acceptedStats: Int,
        val serverTime: Long,
        /** 后端顺手做的逆地理编码结果，命中缓存时才有；null 表示这次还没算出来 */
        val address: String?,
        val addressAt: Long
    )

    data class HealthInfo(
        val ok: Boolean,
        val service: String,
        val retentionDays: Int,
        val amap: Boolean,
        val protocolVersion: Int,
        val serverVersion: String
    )

    // ================= 认证 =================

    /** POST /api/v1/auth/login  请求体 {username,password,device} */
    fun login(username: String, password: String, device: String): LoginResponse {
        val body = JSONObject()
        body.put("username", username)
        body.put("password", password)
        body.put("device", device)

        val resp = request("POST", "/api/v1/auth/login", body, withToken = false)
        val t = resp.optString("token", "")
        if (t.isEmpty()) throw ApiException("bad_response", "服务端没有返回 token")
        token = t
        return LoginResponse(
            token = t,
            uid = resp.optLong("uid", 0L),
            username = resp.optString("username", username),
            account = AccountInfo.fromJson(resp),
            protocolVersion = resp.optInt("protocolVersion", 0),
            serverVersion = resp.optString("serverVersion", "")
        )
    }

    /** POST /api/v1/auth/logout 请求体 {} */
    fun logout(): Boolean {
        val resp = request("POST", "/api/v1/auth/logout", JSONObject(), withToken = true)
        return resp.optBoolean("ok", true)
    }

    // ================= 上传 =================

    /** POST /api/v1/push  离线可攒批：events 最多 500 条/次 */
    fun push(payload: PushPayload): PushResponse {
        val resp = request("POST", "/api/v1/push", payload.toJson(), withToken = true)
        val acc = resp.optJSONObject("accepted")
        return PushResponse(
            acceptedEvents = acc?.optInt("events", 0) ?: 0,
            acceptedStats = acc?.optInt("stats", 0) ?: 0,
            serverTime = resp.optLong("serverTime", 0L),
            address = Event.optStringOrNull(resp, "address"),
            addressAt = resp.optLong("addressAt", 0L)
        )
    }

    /** POST /api/v1/share  单方暂停/恢复共享 */
    fun setSharing(sharing: Boolean): Boolean {
        val body = JSONObject()
        body.put("sharing", sharing)
        val resp = request("POST", "/api/v1/share", body, withToken = true)
        return resp.optBoolean("sharing", sharing)
    }

    // ================= 实时状态 =================

    /** GET /api/v1/peer/live 未配对/对方暂停时仍返回 200，用 sharing=false 表示 */
    fun peerLive(): JSONObject = request("GET", "/api/v1/peer/live", null, withToken = true)

    /** GET /api/v1/me  当前账号与配对信息 */
    fun me(): AccountInfo = AccountInfo.fromJson(request("GET", "/api/v1/me", null, withToken = true))

    // ================= 事件 / 统计 =================

    /**
     * GET /api/v1/peer/events?date=YYYY-MM-DD&since=<ts>
     * since > 0 时只返回比它新的事件（增量同步），=0 表示全量拉这一天。
     */
    fun peerEvents(date: String, since: Long = 0L): List<Event> =
        Event.fromArray(
            request("GET", "/api/v1/peer/events", null, withToken = true, query = eventQuery(date, since))
                .optJSONArray("items")
        )

    /** GET /api/v1/me/events?date=YYYY-MM-DD&since=<ts>，语义同上 */
    fun myEvents(date: String, since: Long = 0L): List<Event> =
        Event.fromArray(
            request("GET", "/api/v1/me/events", null, withToken = true, query = eventQuery(date, since))
                .optJSONArray("items")
        )

    private fun eventQuery(date: String, since: Long): Map<String, String> =
        if (since > 0L) mapOf("date" to date, "since" to since.toString())
        else mapOf("date" to date)

    /** GET /api/v1/peer/stats?date=YYYY-MM-DD */
    fun peerStats(date: String): StatsResponse =
        StatsResponse.fromJson(request("GET", "/api/v1/peer/stats", null, withToken = true, query = mapOf("date" to date)))

    /** GET /api/v1/me/stats?date=YYYY-MM-DD （形状与 peer/stats 相同） */
    fun myStats(date: String): StatsResponse =
        StatsResponse.fromJson(request("GET", "/api/v1/me/stats", null, withToken = true, query = mapOf("date" to date)))

    /** GET /api/v1/peer/daily?days=7 返回按日期升序的列表 */
    fun peerDaily(days: Int = 7): List<DailyItem> =
        DailyItem.fromArray(request("GET", "/api/v1/peer/daily", null, withToken = true, query = mapOf("days" to days.toString())).optJSONArray("days"))

    /** GET /api/v1/me/daily?days=7 返回按日期升序的列表 */
    fun myDaily(days: Int = 7): List<DailyItem> =
        DailyItem.fromArray(request("GET", "/api/v1/me/daily", null, withToken = true, query = mapOf("days" to days.toString())).optJSONArray("days"))

    // ================= 探活 =================

    /** GET /api/v1/health 唯一明文响应 */
    fun health(): HealthInfo {
        val resp = request("GET", "/api/v1/health", null, withToken = false, plain = true)
        return HealthInfo(
            ok = resp.optBoolean("ok", false),
            service = resp.optString("service", ""),
            retentionDays = resp.optInt("retentionDays", 7),
            amap = resp.optBoolean("amap", false),
            protocolVersion = resp.optInt("protocolVersion", 0),
            serverVersion = resp.optString("serverVersion", "")
        )
    }

    /**
     * 检查服务端协议版本是否配套。
     * @return null 表示兼容；否则返回一句给用户看的说明。
     */
    fun versionMismatch(serverProtocol: Int, serverVersion: String): String? {
        val tag = if (serverVersion.isEmpty()) "" else "（服务端 $serverVersion）"
        return when {
            serverProtocol == 0 ->
                "服务端版本过旧$tag：它不认识版本字段，请先更新后端。"
            serverProtocol != PROTOCOL_VERSION ->
                "前后端版本不匹配：客户端协议 $PROTOCOL_VERSION，服务端协议 $serverProtocol$tag。" +
                    "请把两端更新到同一版本后再使用，否则数据可能出错。"
            else -> null
        }
    }

    // ================= 内部实现 =================

    /**
     * 发一次请求。
     * @param body    业务 JSON，null 表示 GET 无请求体
     * @param query   GET 查询参数
     * @param plain   true 表示响应是明文（仅 /health）
     */
    private fun request(
        method: String,
        path: String,
        body: JSONObject?,
        withToken: Boolean,
        query: Map<String, String>? = null,
        plain: Boolean = false
    ): JSONObject {
        val base = baseUrl.trimEnd('/')
        if (base.isEmpty()) throw ApiException("no_server", "还没有填写服务器地址")

        var urlStr = base + path
        if (query != null && query.isNotEmpty()) {
            val sb = StringBuilder("?")
            var first = true
            for ((k, v) in query) {
                if (!first) sb.append('&')
                first = false
                sb.append(URLEncoder.encode(k, "UTF-8")).append('=').append(URLEncoder.encode(v, "UTF-8"))
            }
            urlStr += sb.toString()
        }

        if (!plain && payloadSecret.isEmpty()) {
            throw ApiException("no_secret", "还没有填写配对密钥")
        }

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doInput = true
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty("X-Client", "android")
                if (withToken && token.isNotEmpty()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }

            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                // 业务内容整体加密后再发出
                val raw = Crypto.encryptJson(payloadSecret, body)
                conn.outputStream.use { os ->
                    os.write(raw.toByteArray(Charsets.UTF_8))
                    os.flush()
                }
            }

            val code = conn.responseCode
            val text = readBody(conn, code)

            if (code !in 200..299) {
                // 错误体同样是信封加密（bad_envelope 例外，可能是明文）
                val (ec, em) = parseError(text)
                if (code == 401) throw ApiException("unauthorized", em.ifEmpty { "登录已失效，请重新登录" })
                throw ApiException(ec, em.ifEmpty { "服务端返回 $code" })
            }

            if (text.isBlank()) return JSONObject()
            if (plain) return JSONObject(text)

            val bodyJson = try {
                JSONObject(text)
            } catch (t: Throwable) {
                throw ApiException("bad_response", "响应不是合法 JSON")
            }
            // 未加密的响应直接透传（兼容后端在解密失败时返回的明文错误）
            if (!bodyJson.has("e")) return bodyJson

            val plainText = try {
                Crypto.decrypt(payloadSecret, text)
            } catch (t: Throwable) {
                throw ApiException("bad_envelope", t.message ?: "解密失败")
            }
            return JSONObject(plainText)
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "request failed: $method $urlStr", e)
            throw ApiException("network_error", "网络请求失败：${e.message ?: e.javaClass.simpleName}")
        } finally {
            conn?.disconnect()
        }
    }

    private fun readBody(conn: HttpURLConnection, code: Int): String {
        val stream: InputStream? = try {
            if (code in 200..299) conn.inputStream else conn.errorStream
        } catch (t: Throwable) {
            null
        }
        if (stream == null) return ""
        val raw = stream.use { readFully(it) }
        val encoding = conn.contentEncoding
        val bytes = if (encoding != null && encoding.contains("gzip", ignoreCase = true)) {
            try {
                GZIPInputStream(raw.inputStream()).use { readFully(it) }
            } catch (t: Throwable) {
                raw
            }
        } else {
            raw
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun readFully(input: InputStream): ByteArray {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        var n = input.read(buf)
        while (n >= 0) {
            bos.write(buf, 0, n)
            n = input.read(buf)
        }
        return bos.toByteArray()
    }

    /** 解析错误体，返回 (code, message)。解不开就当明文处理 */
    private fun parseError(text: String): Pair<String, String> {
        if (text.isBlank()) return "" to ""
        val obj = try {
            JSONObject(text)
        } catch (t: Throwable) {
            return "" to ""
        }
        val real = try {
            if (obj.has("e")) JSONObject(Crypto.decrypt(payloadSecret, text)) else obj
        } catch (t: Throwable) {
            obj
        }
        return real.optString("error", "") to real.optString("message", "")
    }

    companion object {
        private const val TAG = "ApiClient"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000

        /**
         * 客户端内置的接口协议版本。
         * 只要 docs/API.md 里的字段、语义或信封格式有变化就 +1，
         * 必须与后端 backend/src/version.js 的 PROTOCOL_VERSION 保持一致。
         */
        const val PROTOCOL_VERSION = 1
    }
}

/** 统一的接口异常 */
class ApiException(val code: String, message: String) : Exception(message) {

    /** 是否值得稍后重试（网络类错误） */
    val retryable: Boolean
        get() = code == "network_error" || code == "server_error" || code == "timeout"

    companion object {
        @Suppress("unused")
        fun from(t: Throwable): ApiException =
            if (t is ApiException) t else ApiException("unknown", t.message ?: "未知错误")
    }
}
