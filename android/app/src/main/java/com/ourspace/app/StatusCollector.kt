package com.ourspace.app

import android.Manifest
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.util.Log

/**
 * 实时状态采集（10 秒一次，见 API.md 第 9 节）：
 *   电量 / 是否充电        —— BatteryManager 粘性广播
 *   WiFi 名称              —— WifiManager.getConnectionInfo().getSSID()（去引号、过滤 <unknown ssid>）
 *   网络类型 wifi|mobile|none —— ConnectivityManager
 *   经纬度 / 精度          —— LocationManager.getLastKnownLocation + 请求一次单次定位更新
 *   屏幕是否点亮           —— PowerManager.isInteractive
 *
 * 只上报经纬度，地址文字（address）由后端逆地理编码得到，本机不集成高德 SDK。
 * 定位请求不超过 3 秒，避免拖慢 10 秒的采集节拍，也不长期持有定位回调（省电）。
 */
class StatusCollector(private val ctx: Context) {

    /** 采集一次实时状态。任何一项失败都用默认值兜底，不会抛给调用方 */
    fun collect(): Live {
        // 先尽量刷新一次定位（同步部分只取 getLastKnownLocation），再组装快照
        collectLocationOnce()
        val now = System.currentTimeMillis()
        val battery = readBattery()
        val loc = lastLocation
        val net = currentNetType()
        // 网络类型变了（比如从 WiFi 切到移动数据）就立刻让 SSID 缓存失效，下一次读新的
        if (net != lastNetType) {
            lastNetType = net
            ssidAt = 0L
        }
        return Live(
            battery = battery.first,
            charging = battery.second,
            wifi = readWifiSsid(),
            net = net,
            lat = loc?.latitude ?: 0.0,
            lon = loc?.longitude ?: 0.0,
            locAcc = loc?.accuracy?.toDouble() ?: 0.0,
            screenOn = isScreenOn(),
            currentApp = currentForegroundApp(),
            updatedAt = now
        )
    }

    // ===================== 当前前台应用 =====================

    /**
     * 当前正在前台使用的应用名（用来在概览页显示「当前使用：微信」）。
     *
     * 做法：取最近 10 分钟的 UsageEvents，找最后一条「进入前台」且之后没有对应「离开前台」的包。
     * 没有「使用情况访问」权限时 queryEvents 返回空，这里就返回 null，界面会隐藏这一行。
     */
    private fun currentForegroundApp(): String? {
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return null
            val now = System.currentTimeMillis()
            val ue = usm.queryEvents(now - 10 * 60 * 1000L, now) ?: return null
            val ev = UsageEvents.Event()
            var lastPkg: String? = null
            while (ue.hasNextEvent()) {
                ue.getNextEvent(ev)
                val pkg = ev.packageName ?: continue
                // 忽略自己，否则会显示成「正在使用 OurSpace」
                if (pkg == ctx.packageName) continue
                when (ev.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED,
                    @Suppress("DEPRECATION")
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> lastPkg = pkg

                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.ACTIVITY_STOPPED,
                    @Suppress("DEPRECATION")
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> if (pkg == lastPkg) lastPkg = null
                }
            }
            lastPkg?.let { appLabel(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "读取当前前台应用失败", t)
            null
        }
    }

    private fun appLabel(pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (t: Throwable) {
        pkg
    }

    // ===================== 电量 =====================

    private fun readBattery(): Pair<Int, Boolean> {
        return try {
            val intent: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent == null) return -1 to false
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            pct to charging
        } catch (t: Throwable) {
            Log.w(TAG, "读取电量失败", t)
            -1 to false
        }
    }

    // ===================== WiFi =====================

    /** WiFi 名称需要定位权限才能读到，也就有可能触发定位图标，所以同样做缓存限流 */
    private var cachedSsid: String? = null
    private var ssidAt: Long = 0L

    /**
     * 读 WiFi 名称。
     * 每 WIFI_MIN_INTERVAL_MS 才真的读一次（WiFi 名称不会频繁变化），
     * 网络类型变化时（例如从 WiFi 切到移动数据）会立刻失效重读。
     */
    private fun readWifiSsid(): String? {
        val now = System.currentTimeMillis()
        if (now - ssidAt < WIFI_MIN_INTERVAL_MS) return cachedSsid
        ssidAt = now
        cachedSsid = readWifiSsidRaw()
        return cachedSsid
    }

    private fun readWifiSsidRaw(): String? {
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // Android 10+ 读 SSID 需要定位权限，没有就返回空
            return null
        }
        return try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val raw = wm?.connectionInfo?.ssid
            normalizeSsid(raw)
        } catch (t: Throwable) {
            Log.w(TAG, "读取 WiFi 名称失败", t)
            null
        }
    }

    // ===================== 网络类型 =====================

    private fun currentNetType(): String {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "none"
            val network = cm.activeNetwork ?: return "none"
            val caps = cm.getNetworkCapabilities(network) ?: return "none"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                // 以太网 / VPN 归到 wifi 一档，契约里只有 wifi|mobile|none
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "wifi"
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> "mobile"
                else -> "none"
            }
        } catch (t: Throwable) {
            Log.w(TAG, "读取网络类型失败", t)
            "none"
        }
    }

    // ===================== 屏幕 =====================

    private fun isScreenOn(): Boolean {
        return try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isInteractive == true
        } catch (t: Throwable) {
            false
        }
    }

    // ===================== 定位 =====================

    @Volatile
    private var lastLocation: Location? = null

    @Volatile
    private var lastRequestAt: Long = 0L

    /** 上一次的网络类型，用于判断是否需要立刻重读 WiFi 名称 */
    private var lastNetType: String? = null

    /** 单次定位监听，收到第一帧就移除自己，不留常驻回调 */
    private val oneShotListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
        }

        @Deprecated("Android 框架层面的旧回调")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit
    }

    /** 先用 getLastKnownLocation 兜底，再请求一次单次更新（不阻塞） */
    private fun collectLocationOnce() {
        try {
            if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED &&
                ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return

            // 整段定位访问都按 LOCATION_MIN_INTERVAL_MS 限流。
            // 注意 getLastKnownLocation 同样算「访问定位」：之前它在每个 10 秒节拍都跑一次，
            // 结果状态栏每 10 秒就闪一次定位图标，看着像被持续追踪。中间几拍直接用缓存值。
            val now = System.currentTimeMillis()
            if (now - lastRequestAt < LOCATION_MIN_INTERVAL_MS) return
            lastRequestAt = now

            // 1) 最后一次已知位置：取三个 provider 里最新的那个
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
                try {
                    val loc = lm.getLastKnownLocation(p) ?: continue
                    val cur = lastLocation
                    if (cur == null || loc.time > cur.time) lastLocation = loc
                } catch (t: Throwable) {
                    // 该 provider 不可用，继续
                }
            }

            // 2) 再请求一次单次更新
            val providers = ArrayList<String>()
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) providers.add(LocationManager.NETWORK_PROVIDER)
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) providers.add(LocationManager.GPS_PROVIDER)
            if (providers.isEmpty()) return

            @Suppress("DEPRECATION")
            lm.requestSingleUpdate(providers[0], oneShotListener, Looper.getMainLooper())
        } catch (t: Throwable) {
            // 定位服务被关闭等情况直接忽略
            Log.w(TAG, "单次定位请求失败", t)
        }
    }

    companion object {
        private const val TAG = "StatusCollector"

        /**
         * 位置向系统请求的最小间隔：5 分钟。
         * 状态本身每 10 秒上报一次（中间用的是最近一次已知位置），
         * 只有超过这个间隔才会真的去要一次新定位，避免持续唤醒 GPS 耗电。
         */
        private const val LOCATION_MIN_INTERVAL_MS = 5 * 60 * 1000L

        /** WiFi 名称的读取间隔（同理会牵扯定位权限，也做限流） */
        private const val WIFI_MIN_INTERVAL_MS = 5 * 60 * 1000L

        /** 去掉 SSID 两侧的引号，并过滤未知 / 空值 */
        fun normalizeSsid(raw: String?): String? {
            if (raw == null) return null
            val s = raw.trim().removeSurrounding("\"")
            if (s.isEmpty()) return null
            if (s.equals("<unknown ssid>", ignoreCase = true)) return null
            if (s.equals("0x", ignoreCase = true)) return null
            if (s.equals("unknown", ignoreCase = true)) return null
            return s
        }
    }
}
