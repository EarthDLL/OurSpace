package com.ourspace.app

import android.content.Context
import android.content.SharedPreferences

/**
 * 本地配置。
 *
 * 关于安全性：Token 与配对密钥直接存在 SharedPreferences 里。
 * 现代 Android（本应用 minSdk 26，实际设备基本都是 Android 10+）默认开启文件级加密 FBE，
 * SharedPreferences 落盘时已被系统密钥加密；而且所有业务内容在应用层还做了一层
 * AES-256-GCM 信封加密（见 Crypto.kt），因此这里不再额外引入加密库。
 */
class Prefs(ctx: Context) {

    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    // ===== 服务器 / 账号 =====
    var serverUrl: String
        get() = sp.getString(K_SERVER, "") ?: ""
        set(v) = sp.edit().putString(K_SERVER, v).apply()

    var payloadSecret: String
        get() = sp.getString(K_SECRET, "") ?: ""
        set(v) = sp.edit().putString(K_SECRET, v).apply()

    var token: String
        get() = sp.getString(K_TOKEN, "") ?: ""
        set(v) = sp.edit().putString(K_TOKEN, v).apply()

    var username: String
        get() = sp.getString(K_USERNAME, "") ?: ""
        set(v) = sp.edit().putString(K_USERNAME, v).apply()

    /**
     * “记住密码”：登录成功后与用户名一起写入，下次打开 App 用它静默自动登录。
     * 只有在设置页点“退出登录”时才会清除，这样什么时候需要重新登录由用户自己决定。
     */
    var savedPassword: String
        get() = sp.getString(K_PASSWORD, "") ?: ""
        set(v) = sp.edit().putString(K_PASSWORD, v).apply()

    var uid: Long
        get() = sp.getLong(K_UID, 0L)
        set(v) = sp.edit().putLong(K_UID, v).apply()

    /** 对方的显示名，登录/`GET /me` 时从 partner 字段填充 */
    var partnerName: String
        get() = sp.getString(K_PARTNER, "") ?: ""
        set(v) = sp.edit().putString(K_PARTNER, v).apply()

    var partnerUid: Long
        get() = sp.getLong(K_PARTNER_UID, 0L)
        set(v) = sp.edit().putLong(K_PARTNER_UID, v).apply()

    // ===== 共享开关 =====
    /** 我的共享总开关：false 时只写本地库、绝不发任何网络请求 */
    var sharing: Boolean
        get() = sp.getBoolean(K_SHARING, true)
        set(v) = sp.edit().putBoolean(K_SHARING, v).apply()

    /**
     * 解锁记录开关，默认关闭。
     * 打开时由 SettingsActivity 弹二次确认；若系统不支持会被自动置回 false。
     */
    var unlockRecord: Boolean
        get() = sp.getBoolean(K_UNLOCK, false)
        set(v) = sp.edit().putBoolean(K_UNLOCK, v).apply()

    // ===== 时间戳 =====
    /** 上次成功同步服务端数据的时间 */
    var lastSyncAt: Long
        get() = sp.getLong(K_LAST_SYNC, 0L)
        set(v) = sp.edit().putLong(K_LAST_SYNC, v).apply()

    /** 最近一次同步成功的时间；0 表示从来没成功过 */
    var lastSyncOkAt: Long
        get() = sp.getLong(K_SYNC_OK_AT, 0L)
        set(v) = sp.edit().putLong(K_SYNC_OK_AT, v).apply()

    /** 最近一次同步失败的描述（中文，可直接显示给用户）；空串表示当前没有错误 */
    var lastSyncError: String
        get() = sp.getString(K_SYNC_ERR, "") ?: ""
        set(v) = sp.edit().putString(K_SYNC_ERR, v).apply()

    /** 最近一次同步失败的时间 */
    var lastSyncErrorAt: Long
        get() = sp.getLong(K_SYNC_ERR_AT, 0L)
        set(v) = sp.edit().putLong(K_SYNC_ERR_AT, v).apply()

    /** 当前是否处于「同步失败且比上次成功更新」的状态，用于界面报警 */
    val hasFreshSyncError: Boolean
        get() = lastSyncError.isNotEmpty() && lastSyncErrorAt > lastSyncOkAt

    /** 上次修剪本地库的时间（每天一次） */
    var lastTrimAt: Long
        get() = sp.getLong(K_LAST_TRIM, 0L)
        set(v) = sp.edit().putLong(K_LAST_TRIM, v).apply()

    /**
     * 上次「应用使用时长」采集到的截止时间。
     * 必须持久化：服务被杀掉一段时间后重启，要能从断点开始把空档补回来
     * （UsageStatsManager 自己保留了约 7 天的事件历史）。
     */
    var usageLastRunAt: Long
        get() = sp.getLong(K_USAGE_LAST_RUN, 0L)
        set(v) = sp.edit().putLong(K_USAGE_LAST_RUN, v).apply()

    /** 上次上报的 serverTime，仅用于诊断显示 */
    var serverTimeSkew: Long
        get() = sp.getLong(K_SKEW, 0L)
        set(v) = sp.edit().putLong(K_SKEW, v).apply()

    val isLoggedIn: Boolean
        get() = token.isNotEmpty() && serverUrl.isNotEmpty()

    /** 是否具备自动登录条件：服务器地址、配对密钥、用户名、记住的密码都在 */
    val hasSavedCredentials: Boolean
        get() = serverUrl.isNotEmpty() && payloadSecret.isNotEmpty() &&
                username.isNotEmpty() && savedPassword.isNotEmpty()

    /**
     * 退出登录：清掉登录凭据和「记住的密码」。
     * 服务器地址、配对密钥、用户名保留下来，方便再次登录时不用重新输。
     * 密码一清，下次打开 App 就不会再自动登录，而是停在登录页。
     */
    fun clearSession() {
        sp.edit()
            .remove(K_TOKEN)
            .remove(K_UID)
            .remove(K_PARTNER)
            .remove(K_PARTNER_UID)
            .remove(K_PASSWORD)
            .apply()
    }

    /**
     * 只清登录令牌，保留「记住的密码」。
     * 用于令牌过期时自动重新登录：清掉令牌后回到登录页，
     * 那里会用记住的密码静默换一个新令牌，用户无感知。
     */
    fun clearToken() {
        sp.edit()
            .remove(K_TOKEN)
            .remove(K_UID)
            .remove(K_PARTNER)
            .remove(K_PARTNER_UID)
            .apply()
    }

    fun save(cfg: SessionConfig) {
        sp.edit()
            .putString(K_SERVER, cfg.serverUrl)
            .putString(K_SECRET, cfg.payloadSecret)
            .putString(K_TOKEN, cfg.token)
            .putString(K_USERNAME, cfg.username)
            .putLong(K_UID, cfg.uid)
            .apply()
    }

    data class SessionConfig(
        val serverUrl: String,
        val payloadSecret: String,
        val token: String,
        val username: String,
        val uid: Long
    )

    companion object {
        private const val NAME = "ourspace_prefs"
        private const val K_SERVER = "serverUrl"
        private const val K_SECRET = "payloadSecret"
        private const val K_TOKEN = "token"
        private const val K_USERNAME = "username"
        private const val K_UID = "uid"
        private const val K_PASSWORD = "savedPassword"
        private const val K_PARTNER = "partnerName"
        private const val K_PARTNER_UID = "partnerUid"
        private const val K_SHARING = "sharing"
        private const val K_UNLOCK = "unlockRecord"
        private const val K_LAST_SYNC = "lastSyncAt"
        private const val K_SYNC_OK_AT = "lastSyncOkAt"
        private const val K_SYNC_ERR = "lastSyncError"
        private const val K_SYNC_ERR_AT = "lastSyncErrorAt"
        private const val K_LAST_TRIM = "lastTrimAt"
        private const val K_USAGE_LAST_RUN = "usageLastRunAt"
        private const val K_SKEW = "serverTimeSkew"

        /** 全局单例，Application 里初始化 */
        @Volatile
        private var instance: Prefs? = null

        fun init(ctx: Context) {
            if (instance == null) {
                synchronized(Prefs::class.java) {
                    if (instance == null) instance = Prefs(ctx)
                }
            }
        }

        fun get(): Prefs = instance
            ?: throw IllegalStateException("Prefs 未初始化，请检查 App.onCreate()")

        fun peek(): Prefs? = instance
    }
}
