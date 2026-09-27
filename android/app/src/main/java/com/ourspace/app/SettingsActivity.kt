package com.ourspace.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 设置页。
 *
 * 包含：
 *  - 服务器地址 / 配对密钥 / 用户名、退出登录、立即同步；
 *  - 解锁记录开关（默认关闭）：打开时弹二次确认，文案明确说明对方也能看到你是否关闭；
 *    系统不支持时自动置回关闭并提示；
 *  - 权限引导列表：逐项显示当前状态 + 点击跳转对应系统页面；
 *  - 「共享开关由本人控制」的隐私说明。
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var db: LocalDb

    private lateinit var etServer: EditText
    private lateinit var etSecret: EditText
    private lateinit var etUsername: EditText
    private lateinit var tvUid: TextView
    private lateinit var tvSaveStatus: TextView
    private lateinit var swUnlock: Switch
    private lateinit var tvUnlockError: TextView
    private lateinit var btnSyncNow: Button
    private lateinit var btnLogout: Button
    private lateinit var tvSyncStatus: TextView
    private lateinit var tvVersionInfo: TextView
    private lateinit var lvPermissions: ListView

    private lateinit var permAdapter: PermissionAdapter

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ourspace-settings").apply { isDaemon = true }
    }

    /** 用户正在手动切换开关，避免监听器递归 */
    private var suppressUnlockListener = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        prefs = Prefs.get()
        db = LocalDb.get(this)

        setContentView(R.layout.activity_settings)
        title = "设置"

        bindViews()
        loadValues()
        setupActions()
        setupPermissionList()
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页返回后刷新权限状态
        refreshPermissionList()
        refreshUnlockState()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    // ===================== 初始化 =====================

    private fun bindViews() {
        etServer = findViewById(R.id.etServer)
        etSecret = findViewById(R.id.etSecret)
        etUsername = findViewById(R.id.etUsername)
        tvUid = findViewById(R.id.tvUid)
        tvSaveStatus = findViewById(R.id.tvSaveStatus)
        swUnlock = findViewById(R.id.swUnlock)
        tvUnlockError = findViewById(R.id.tvUnlockError)
        btnSyncNow = findViewById(R.id.btnSyncNow)
        btnLogout = findViewById(R.id.btnLogout)
        tvSyncStatus = findViewById(R.id.tvSyncStatus)
        tvVersionInfo = findViewById(R.id.tvVersionInfo)
        lvPermissions = findViewById(R.id.lvPermissions)
    }

    private fun loadValues() {
        etServer.setText(prefs.serverUrl)
        etSecret.setText(prefs.payloadSecret)
        etUsername.setText(prefs.username)
        tvUid.text = "UID：${prefs.uid}　对方：${if (prefs.partnerName.isEmpty()) "未配对" else prefs.partnerName}"
        refreshUnlockState()
        refreshVersionInfo()
    }

    /**
     * 版本信息：客户端版本固定可知；服务端版本走明文接口 GET /health 现查。
     * 协议版本不一致时红字警告——这是以后升级前后端时的主要排查入口。
     */
    private fun refreshVersionInfo() {
        val client = "客户端：${BuildConfig.VERSION_NAME}（协议 ${ApiClient.PROTOCOL_VERSION}）"
        tvVersionInfo.setTextColor(getColor(R.color.text_secondary))
        tvVersionInfo.text = "$client\n服务端：正在检查…"

        val server = prefs.serverUrl
        val secret = prefs.payloadSecret
        if (server.isEmpty() || secret.isEmpty()) {
            tvVersionInfo.text = "$client\n服务端：未配置"
            return
        }
        io.execute {
            val line = try {
                val h = ApiClient(server, secret, "").health()
                val mismatch = ApiClient.PROTOCOL_VERSION.let { mine ->
                    when {
                        h.protocolVersion == 0 ->
                            "服务端版本过旧：它不认识版本字段，请先更新后端。"
                        h.protocolVersion != mine ->
                            "前后端版本不匹配：客户端协议 $mine，服务端协议 ${h.protocolVersion}。" +
                                "请把两端更新到同一版本。"
                        else -> null
                    }
                }
                if (mismatch != null) "⚠ $mismatch"
                else "服务端：${h.serverVersion.ifEmpty { "未知" }}（协议 ${h.protocolVersion}）· 版本一致"
            } catch (t: Throwable) {
                "服务端：连不上（${(t as? ApiException)?.message ?: t.message ?: "未知错误"}）"
            }
            main.post {
                tvVersionInfo.text = "$client\n$line"
                if (line.startsWith("⚠") || line.contains("连不上")) {
                    tvVersionInfo.setTextColor(getColor(R.color.warn_red))
                }
            }
        }
    }

    private fun refreshUnlockState() {
        suppressUnlockListener = true
        swUnlock.isChecked = prefs.unlockRecord
        suppressUnlockListener = false
    }

    private fun setupActions() {
        swUnlock.setOnCheckedChangeListener { _, isChecked ->
            if (suppressUnlockListener) return@setOnCheckedChangeListener
            if (isChecked) {
                // 打开必须二次确认；取消则把开关拨回去
                confirmEnableUnlock()
            } else {
                disableUnlock()
            }
        }

        btnSyncNow.setOnClickListener { saveAndSync() }

        btnLogout.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("退出登录")
                .setMessage("退出后将停止共享，并清空本机缓存的历史数据。确定退出吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("退出") { _, _ -> doLogout() }
                .show()
        }
    }

    // ===================== 解锁记录开关 =====================

    /** 二次确认文案（必须完整包含这句话） */
    private val unlockConfirmText =
        "该功能会记录并共享你手机的解锁时间，需双方自愿，另一方也能看到你是否关闭了此功能。"

    private fun confirmEnableUnlock() {
        AlertDialog.Builder(this)
            .setTitle("开启解锁记录？")
            .setMessage(unlockConfirmText)
            .setNegativeButton("取消") { _, _ ->
                // 用户拒绝：把开关拨回关闭
                refreshUnlockState()
            }
            .setPositiveButton("我同意，开启") { _, _ ->
                enableUnlock()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 打开解锁记录。
     * 启动解锁监听（注册 ACTION_USER_PRESENT 广播）可能抛异常或不生效，
     * 这时必须自动把开关置回关闭，并显示「本机系统不支持解锁记录，已自动关闭」。
     */
    private fun enableUnlock() {
        val supported = try {
            verifyUnlockSupport()
        } catch (t: Throwable) {
            Log.w(TAG, "解锁监听不可用", t)
            false
        }

        if (!supported) {
            rollbackUnlockSwitch(UNSUPPORTED_TEXT)
            return
        }

        prefs.unlockRecord = true
        hideUnlockError()
        refreshUnlockState()
        Toast.makeText(this, "已开启解锁记录", Toast.LENGTH_SHORT).show()

        // 让采集服务立刻重新注册广播并刷新常驻通知
        CollectorService.start(this)

        // 写本地 + 上报共享状态（本机确实在共享解锁信息，对方可见）
        try {
            db.enqueue(OutboxKind.SHARE, JSONObject().put("sharing", prefs.sharing))
        } catch (t: Throwable) {
            Log.w(TAG, "写共享标记失败", t)
        }
        io.execute {
            try {
                if (prefs.isLoggedIn) {
                    ApiClient(prefs.serverUrl, prefs.payloadSecret, prefs.token)
                        .setSharing(prefs.sharing)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "上报共享状态失败", t)
            }
        }
    }

    /**
     * 在真正打开开关之前，先探测本机是否支持解锁事件监听：
     * 动态注册一次 ACTION_USER_PRESENT 广播，注册失败或系统不提供 KeyguardManager 就判定不支持。
     * 探测用的 receiver 立刻注销，不会泄漏。
     */
    private fun verifyUnlockSupport(): Boolean {
        val kg = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        if (kg == null) return false
        // 能查询锁屏状态，说明系统提供键盘锁服务
        try {
            kg.isKeyguardLocked
        } catch (t: Throwable) {
            // 某些定制 ROM 可能抛异常，继续尝试注册广播
            Log.w(TAG, "查询锁屏状态失败", t)
        }

        var ok = false
        // BroadcastReceiver 是抽象类，必须用对象表达式实例化
        val probe = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: android.content.Intent?) = Unit
        }
        try {
            registerReceiver(probe, android.content.IntentFilter(Intent.ACTION_USER_PRESENT))
            ok = true
        } catch (t: Throwable) {
            Log.w(TAG, "注册解锁广播失败", t)
            ok = false
        } finally {
            if (ok) {
                try {
                    unregisterReceiver(probe)
                } catch (t: Throwable) {
                    Log.w(TAG, "注销探测 receiver 失败", t)
                }
            }
        }
        return ok
    }

    /** 关闭解锁记录：既不写库也不上传 */
    private fun disableUnlock() {
        prefs.unlockRecord = false
        hideUnlockError()
        Toast.makeText(this, "已关闭解锁记录，本机不再记录解锁时间", Toast.LENGTH_SHORT).show()
        CollectorService.start(this)
        io.execute {
            try {
                if (prefs.isLoggedIn) {
                    ApiClient(prefs.serverUrl, prefs.payloadSecret, prefs.token)
                        .setSharing(prefs.sharing)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "上报共享状态失败", t)
            }
        }
    }

    private fun rollbackUnlockSwitch(msg: String) {
        prefs.unlockRecord = false
        refreshUnlockState()
        tvUnlockError.visibility = View.VISIBLE
        tvUnlockError.text = msg
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun hideUnlockError() {
        tvUnlockError.visibility = View.GONE
        tvUnlockError.text = ""
    }

    // ===================== 保存 / 同步 / 退出 =====================

    private fun saveFields(): Boolean {
        val server = etServer.text.toString().trim().trimEnd('/')
        val secret = etSecret.text.toString().trim()
        if (server.isEmpty() || !server.startsWith("http")) {
            tvSaveStatus.setTextColor(getColor(R.color.warn_red))
            tvSaveStatus.text = "服务器地址不合法，需要以 http:// 或 https:// 开头"
            return false
        }
        if (secret.isEmpty()) {
            tvSaveStatus.setTextColor(getColor(R.color.warn_red))
            tvSaveStatus.text = "配对密钥不能为空"
            return false
        }
        prefs.serverUrl = server
        prefs.payloadSecret = secret
        if (prefs.token.isEmpty()) prefs.username = etUsername.text.toString().trim()
        return true
    }

    private fun saveAndSync() {
        if (!saveFields()) return
        tvSaveStatus.setTextColor(getColor(R.color.ok_green))
        tvSaveStatus.text = "已保存"

        if (!prefs.isLoggedIn) {
            tvSyncStatus.text = "尚未登录，正在跳转登录页…"
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        btnSyncNow.isEnabled = false
        tvSyncStatus.text = "正在同步…"

        val server = prefs.serverUrl
        val secret = prefs.payloadSecret
        val token = prefs.token

        io.execute {
            val api = ApiClient(server, secret, token)
            var message = "同步失败：未知错误"
            try {
                // 1) 先上报本机攒下的数据
                val outbox = Outbox(applicationContext)
                outbox.flush(api, token.isNotEmpty())
                // 2) 再拉取服务端数据
                val r = SyncEngine(applicationContext).sync(api)
                message = r.message
            } catch (t: Throwable) {
                message = "同步失败：" + ((t as? ApiException)?.message ?: t.message ?: "未知错误")
            }
            main.post {
                btnSyncNow.isEnabled = true
                tvSyncStatus.text = message
            }
        }
    }

    private fun doLogout() {
        val server = prefs.serverUrl
        val secret = prefs.payloadSecret
        val token = prefs.token
        if (token.isNotEmpty()) {
            // 先用一个独立线程跑注销请求：本 Activity 马上要结束，
            // 不能依赖自己的 io 线程池（onDestroy 会 shutdownNow）。
            try {
                val t = Thread({
                    try {
                        ApiClient(server, secret, token).logout()
                    } catch (e: Throwable) {
                        Log.w(TAG, "注销接口调用失败（忽略）", e)
                    }
                }, "ourspace-logout")
                t.isDaemon = true
                t.start()
            } catch (e: Throwable) {
                Log.w(TAG, "启动注销线程失败（忽略）", e)
            }
        }
        // 停止采集 + 清本地缓存，避免把数据留在设备上
        CollectorService.stop(this)
        try {
            db.clearAll()
        } catch (t: Throwable) {
            Log.w(TAG, "清空本地库失败", t)
        }
        prefs.clearSession()
        prefs.unlockRecord = false
        startActivity(
            Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    // ===================== 权限引导列表 =====================

    private fun currentPermissionItems(): List<PermissionItem> {
        val usageOk = PermissionHelper.hasUsageAccess(this)
        val fgLoc = PermissionHelper.hasForegroundLocation(this)
        val bgLoc = PermissionHelper.hasBackgroundLocation(this)
        val notifyOk = PermissionHelper.hasNotificationPermission(this)
        val batteryOk = PermissionHelper.isIgnoringBatteryOptimizations(this)

        return listOf(
            PermissionItem(
                "1. 使用情况访问权限",
                "用于统计各应用使用时长和打开/关闭事件。系统设置 → 使用情况访问 → 找到「OurSpace」并允许。",
                usageOk,
                Action.USAGE
            ),
            PermissionItem(
                "2. 定位权限（前台）",
                if (fgLoc) "已授予。用于上报当前位置的经纬度。" else "未授予。点击后在弹窗里选择「仅在使用该应用时允许」。",
                fgLoc,
                Action.LOCATION
            ),
            PermissionItem(
                "3. 定位权限（始终允许）",
                if (bgLoc) "已授予（始终允许）。" else
                    "未授予。Android 11+ 需要在应用详情页里选「权限 → 位置 → 始终允许」，否则锁屏后无法获取位置。",
                bgLoc,
                Action.LOCATION_BACKGROUND
            ),
            PermissionItem(
                "4. 通知权限",
                if (notifyOk) "已授予。用于显示「正在共享」常驻通知。" else "未授予。常驻通知是后台不被系统杀掉的关键。",
                notifyOk,
                Action.NOTIFICATION
            ),
            PermissionItem(
                "5. 忽略电池优化",
                if (batteryOk) "已加入白名单。" else "未加入。点击后在系统弹窗里允许，避免后台被系统限制。",
                batteryOk,
                Action.BATTERY
            ),
            PermissionItem(
                "6. 自启动 / 后台运行",
                "部分厂商 ROM 需要手动允许自启动。点击尝试打开本机自启动设置页；打不开也没关系，" +
                    "请按下方文字说明手动设置。",
                false,
                Action.AUTOSTART
            )
        )
    }

    private fun setupPermissionList() {
        permAdapter = PermissionAdapter(this)
        lvPermissions.adapter = permAdapter
        refreshPermissionList()
    }

    private fun refreshPermissionList() {
        permAdapter.submit(currentPermissionItems())
        // 必须等布局完成再量高度，否则拿不到 ListView 的真实宽度
        lvPermissions.post { setListViewHeight() }
    }

    /**
     * ListView 放在 ScrollView 里必须手动量高，否则只显示一行。
     *
     * 关键点：测量宽度必须用 ListView 的实际宽度。
     * 之前用的是 UNSPECIFIED 宽度（等价于「不限宽」），TextView 按不换行算高度，
     * 于是总高度偏小，最后一项（第 6 项 自启动）就被裁掉了。
     */
    private fun setListViewHeight() {
        val adapter = lvPermissions.adapter ?: return
        val parentWidth = (lvPermissions.parent as? View)?.width?.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val widthSpec = View.MeasureSpec.makeMeasureSpec(parentWidth, View.MeasureSpec.AT_MOST)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

        var total = 0
        for (i in 0 until adapter.count) {
            val item = adapter.getView(i, null, lvPermissions)
            item.measure(widthSpec, heightSpec)
            total += item.measuredHeight
        }
        val dividers = if (adapter.count > 1) lvPermissions.dividerHeight * (adapter.count - 1) else 0
        val lp = lvPermissions.layoutParams
        lp.height = total + dividers
        lvPermissions.layoutParams = lp
        lvPermissions.requestLayout()
    }

    private enum class Action {
        USAGE, LOCATION, LOCATION_BACKGROUND, NOTIFICATION, BATTERY, AUTOSTART
    }

    private data class PermissionItem(
        val name: String,
        val desc: String,
        val granted: Boolean,
        val action: Action
    )

    /** 权限行里的三个 TextView，避免每次 findViewById */
    private class PermViewHolder(val name: TextView, val desc: TextView, val state: TextView)

    private inner class PermissionAdapter(private val ctx: Context) : BaseAdapter() {

        private val items = ArrayList<PermissionItem>()

        fun submit(list: List<PermissionItem>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Any = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            var view = convertView
            val holder: PermViewHolder
            if (view == null) {
                view = LayoutInflater.from(ctx).inflate(R.layout.item_permission, parent, false)
                holder = PermViewHolder(
                    name = view.findViewById(R.id.tvPermName),
                    desc = view.findViewById(R.id.tvPermDesc),
                    state = view.findViewById(R.id.tvPermState)
                )
                view.tag = holder
            } else {
                holder = view.tag as PermViewHolder
            }

            val item = items[position]
            holder.name.text = item.name
            holder.desc.text = item.desc
            // 第 6 项是说明性质，状态栏显示「去设置」
            if (item.action == Action.AUTOSTART) {
                holder.state.text = "去设置"
                holder.state.setTextColor(getColor(R.color.brand))
            } else if (item.granted) {
                holder.state.text = "已授予"
                holder.state.setTextColor(getColor(R.color.ok_green))
            } else {
                holder.state.text = "未授予"
                holder.state.setTextColor(getColor(R.color.warn_red))
            }

            view.setOnClickListener { handlePermissionClick(item.action) }
            return view
        }
    }

    private fun handlePermissionClick(action: Action) {
        when (action) {
            Action.USAGE -> PermissionHelper.openUsageAccess(this)
            Action.LOCATION -> {
                if (PermissionHelper.hasForegroundLocation(this)) {
                    Toast.makeText(this, "定位权限已授予", Toast.LENGTH_SHORT).show()
                }
                PermissionHelper.requestForegroundLocation(this)
            }
            Action.LOCATION_BACKGROUND -> {
                Toast.makeText(this, "请在应用详情页把位置权限改为「始终允许」", Toast.LENGTH_LONG).show()
                PermissionHelper.openAppDetails(this)
            }
            Action.NOTIFICATION -> {
                if (PermissionHelper.hasNotificationPermission(this)) {
                    Toast.makeText(this, "通知权限已授予", Toast.LENGTH_SHORT).show()
                } else {
                    PermissionHelper.requestNotification(this)
                }
            }
            Action.BATTERY -> PermissionHelper.requestIgnoreBatteryOptimizations(this)
            Action.AUTOSTART -> {
                val opened = PermissionHelper.openAutoStartSettings(this)
                if (!opened) {
                    Toast.makeText(this, "请按页面中的厂商说明手动允许自启动", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // 权限弹窗关闭后会走 onResume()，在那里统一刷新权限状态，
    // 因此这里不覆写 onRequestPermissionsResult，避免数组类型的重载歧义。

    companion object {
        private const val TAG = "SettingsActivity"

        /** 自动降级提示文案 */
        const val UNSUPPORTED_TEXT = "本机系统不支持解锁记录，已自动关闭"
    }
}
