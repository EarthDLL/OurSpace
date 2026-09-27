package com.ourspace.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * 主界面：底部三个 Tab（概览 / 瞬间 / 统计）+ 右上角设置。
 *
 * 用 FrameLayout 切换三个 include 进来的布局，底部导航自己用 LinearLayout + Button 实现，
 * 不使用 AndroidX / Material 的任何控件。
 * 界面只读本地库（LocalDb），后台服务负责同步，因此离线也能看 7 天历史。
 * 界面不可见（onStop）时停止 UI 轮询。
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var db: LocalDb
    private lateinit var syncEngine: SyncEngine
    private lateinit var timelineAdapter: TimelineAdapter
    private lateinit var usageAdapter: AppUsageAdapter

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ourspace-ui-io").apply { isDaemon = true }
    }

    // 顶层
    private lateinit var tvTitle: TextView
    private lateinit var tabOverview: View
    private lateinit var tabTimeline: View
    private lateinit var tabStats: View
    private lateinit var tabBtnOverview: Button
    private lateinit var tabBtnTimeline: Button
    private lateinit var tabBtnStats: Button

    // 概览
    private lateinit var swSharing: Switch
    private lateinit var tvSharingHint: TextView
    private lateinit var tvPeerName: TextView
    private lateinit var tvPeerState: TextView
    private lateinit var tvPeerUpdated: TextView
    private lateinit var tvPeerBattery: TextView
    private lateinit var tvPeerNet: TextView
    private lateinit var tvPeerScreen: TextView
    private lateinit var tvPeerLocation: TextView
    private lateinit var tvPeerTodayScreen: TextView
    private lateinit var tvPeerTodayUnlocks: TextView
    private lateinit var tvPeerTopApps: TextView
    private lateinit var tvMyLive: TextView

    // 瞬间
    private lateinit var btnOwnerMe: Button
    private lateinit var btnOwnerPeer: Button
    private lateinit var btnStatsMe: Button
    private lateinit var btnStatsPeer: Button
    private lateinit var tvHeaderSharing: TextView
    private lateinit var tvSharingState: TextView
    private lateinit var tvSyncState: TextView
    private lateinit var tvTimelinePeerState: TextView
    private lateinit var tvStatsPeerState: TextView
    private lateinit var tvPeerCurrentApp: TextView
    private lateinit var llDateBar: LinearLayout
    private lateinit var lvTimeline: ListView
    private lateinit var tvTimelineEmpty: TextView

    // 统计
    private lateinit var llStatsDateBar: LinearLayout
    private lateinit var llAppUsage: LinearLayout
    private lateinit var btnPrevPage: Button
    private lateinit var btnNextPage: Button
    private lateinit var tvPageInfo: TextView
    private lateinit var tvStatsEmpty: TextView
    private lateinit var tvTotalMs: TextView
    private lateinit var barChart: BarChartView

    private var currentTab = TAB_OVERVIEW

    /** “瞬间”页当前在看谁 */
    private var owner = LocalDb.OWNER_PEER

    /** “统计”页当前在看谁。统计同样是双方共享的，这里独立切换，不受瞬间页影响 */
    private var statsOwner = LocalDb.OWNER_PEER

    private var selectedDate = TimeUtil.today()

    /** UI 轮询计数，用于统计页的定时重新拉取 */
    private var uiTickCount = 0L

    /** 令牌失效只处理一次，避免多个请求同时触发跳转 */
    private var sessionExpired = false

    /** 「瞬间」当前页码（每页 30 条） */
    private var timelinePage = 0

    /** 「瞬间」总页数，供翻页按钮判断边界 */
    private var timelinePageCount = 1

    /** 统计页当前已经渲染过的应用列表，用于避免每 10 秒重建一遍视图 */
    private var renderedApps: List<Stat> = emptyList()

    /**
     * 界面可见时的本地库轮询。
     * 三个 Tab 用同一个节拍（10 秒），跟采集服务的上传/下行周期一致，不会再有「概览比瞬间新」的感觉。
     * 刷新只是读本地 SQLite，开销很小；adapter 内部做了「内容没变就不通知」的判断，
     * 所以高频刷新不会把正在滚动的位置顶掉。
     */
    private val uiTick = object : Runnable {
        override fun run() {
            refreshOverview()
            refreshTimeline()
            refreshStats()
            uiTickCount++
            // 每 5 分钟主动拉一次「当前正在看的这一天、这个人」的服务端应用排行。
            // 否则对方的应用明细只有在你切日期/切人/重进页面时才会更新，
            // 看起来就是「对方那边的数字总比我这边落后」。
            if (uiTickCount % STATS_RESYNC_EVERY_TICKS == 0L) {
                syncStatsFromServer(selectedDate, statsOwner)
            }
            main.postDelayed(this, UI_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        prefs = Prefs.get()
        db = LocalDb.get(this)
        syncEngine = SyncEngine(this)

        // 没有 token 就回登录页
        if (!prefs.isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        bindViews()
        setupTabs()
        setupOverview()
        setupTimeline()
        setupStats()

        // 进主界面就确保采集服务在跑
        CollectorService.start(this)
    }

    override fun onResume() {
        super.onResume()
        if (!prefs.isLoggedIn) return
        // 界面可见时才开始轮询
        main.removeCallbacks(uiTick)
        main.post(uiTick)
        rebuildDateBar(llDateBar, "timeline")
        rebuildDateBar(llStatsDateBar, "stats")
        refreshAll()
    }

    override fun onStop() {
        // 界面不可见：停止 UI 轮询，省电
        main.removeCallbacks(uiTick)
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        super.onDestroy()
    }

    // ===================== 初始化 =====================

    private fun bindViews() {
        tvTitle = findViewById(R.id.tvTitle)
        tabOverview = findViewById(R.id.tabOverview)
        tabTimeline = findViewById(R.id.tabTimeline)
        tabStats = findViewById(R.id.tabStats)
        tabBtnOverview = findViewById(R.id.tabBtnOverview)
        tabBtnTimeline = findViewById(R.id.tabBtnTimeline)
        tabBtnStats = findViewById(R.id.tabBtnStats)

        swSharing = findViewById(R.id.swSharing)
        tvSharingHint = findViewById(R.id.tvSharingHint)
        tvPeerName = findViewById(R.id.tvPeerName)
        tvPeerState = findViewById(R.id.tvPeerState)
        tvPeerUpdated = findViewById(R.id.tvPeerUpdated)
        tvPeerBattery = findViewById(R.id.tvPeerBattery)
        tvPeerNet = findViewById(R.id.tvPeerNet)
        tvPeerScreen = findViewById(R.id.tvPeerScreen)
        tvPeerLocation = findViewById(R.id.tvPeerLocation)
        tvPeerTodayScreen = findViewById(R.id.tvPeerTodayScreen)
        tvPeerTodayUnlocks = findViewById(R.id.tvPeerTodayUnlocks)
        tvPeerTopApps = findViewById(R.id.tvPeerTopApps)
        tvMyLive = findViewById(R.id.tvMyLive)

        btnOwnerMe = findViewById(R.id.btnOwnerMe)
        btnOwnerPeer = findViewById(R.id.btnOwnerPeer)
        llDateBar = findViewById(R.id.llDateBarTimeline)
        lvTimeline = findViewById(R.id.lvTimeline)
        tvTimelineEmpty = findViewById(R.id.tvTimelineEmpty)

        btnStatsMe = findViewById(R.id.btnStatsMe)
        btnStatsPeer = findViewById(R.id.btnStatsPeer)
        tvHeaderSharing = findViewById(R.id.tvHeaderSharing)
        tvSharingState = findViewById(R.id.tvSharingState)
        tvSyncState = findViewById(R.id.tvSyncState)
        tvTimelinePeerState = findViewById(R.id.tvTimelinePeerState)
        tvStatsPeerState = findViewById(R.id.tvStatsPeerState)
        tvPeerCurrentApp = findViewById(R.id.tvPeerCurrentApp)
        llStatsDateBar = findViewById(R.id.llDateBarStats)
        llAppUsage = findViewById(R.id.llAppUsage)
        btnPrevPage = findViewById(R.id.btnPrevPage)
        btnNextPage = findViewById(R.id.btnNextPage)
        tvPageInfo = findViewById(R.id.tvPageInfo)
        tvStatsEmpty = findViewById(R.id.tvStatsEmpty)
        tvTotalMs = findViewById(R.id.tvTotalMs)
        barChart = findViewById(R.id.barChart)

        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.btnRefreshOverview).setOnClickListener {
            refreshAll()
            CollectorService.start(this)
        }

        timelineAdapter = TimelineAdapter(this)
        usageAdapter = AppUsageAdapter(this)
        lvTimeline.adapter = timelineAdapter
    }

    private fun setupTabs() {
        tabBtnOverview.setOnClickListener { switchTab(TAB_OVERVIEW) }
        tabBtnTimeline.setOnClickListener { switchTab(TAB_TIMELINE) }
        tabBtnStats.setOnClickListener { switchTab(TAB_STATS) }
        switchTab(TAB_OVERVIEW)
    }

    private fun switchTab(tab: Int) {
        currentTab = tab
        tabOverview.visibility = if (tab == TAB_OVERVIEW) View.VISIBLE else View.GONE
        tabTimeline.visibility = if (tab == TAB_TIMELINE) View.VISIBLE else View.GONE
        tabStats.visibility = if (tab == TAB_STATS) View.VISIBLE else View.GONE
        tvTitle.text = when (tab) {
            TAB_OVERVIEW -> "概览"
            TAB_TIMELINE -> "瞬间"
            else -> "统计"
        }
        highlightTab(tab)
        if (tab == TAB_TIMELINE) refreshTimeline()
        if (tab == TAB_STATS) refreshStats()
    }

    private fun highlightTab(tab: Int) {
        val buttons = listOf(tabBtnOverview, tabBtnTimeline, tabBtnStats)
        for ((i, b) in buttons.withIndex()) {
            b.setBackgroundResource(if (i == tab) R.drawable.bg_tab_selected else android.R.color.transparent)
            b.setTypeface(null, if (i == tab) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    // ===================== 概览 =====================

    private fun setupOverview() {
        swSharing.isChecked = prefs.sharing
        updateSharingHint()
        swSharing.setOnCheckedChangeListener { _, isChecked ->
            prefs.sharing = isChecked
            updateSharingHint()
            updateNotificationHint()
            // 共享状态变化后需要立刻告诉服务端（这是一次用户主动操作），
            // 之后暂停期间不会再有任何网络请求。
            pushSharingState(isChecked)
            refreshOverview()
        }
    }

    private fun updateSharingHint() {
        tvSharingHint.text = if (prefs.sharing) {
            "正在共享：每 10 秒上传一次状态，对方状态也是 10 秒一次"
        } else {
            "已暂停：不再上传任何数据，仅保存在本机"
        }
    }

    private fun updateNotificationHint() {
        // 服务在下一个 10 秒节拍会刷新常驻通知文案
        CollectorService.start(this)
    }

    /** 用户主动切换共享开关后立刻上报一次 POST /share */
    private fun pushSharingState(isChecked: Boolean) {
        val server = prefs.serverUrl
        val secret = prefs.payloadSecret
        val token = prefs.token
        if (server.isEmpty() || token.isEmpty()) return
        io.execute {
            try {
                ApiClient(server, secret, token).setSharing(isChecked)
            } catch (t: Throwable) {
                Log.w(TAG, "上报共享状态失败（下次同步会重试）", t)
                db.enqueue(OutboxKind.SHARE, org.json.JSONObject().put("sharing", isChecked))
            }
        }
    }

    /**
     * 同步状态提示。
     *
     * 网络失败 ≠ 登录失效，所以绝不能把人踢回登录页——一断网就被登出反而更烦。
     * 正确做法是在界面上说清楚「连不上服务器，数据只存在本机」，让人知道不是数据坏了。
     */
    private fun refreshSyncState() {
        tvSyncState.text = when {
            !prefs.sharing -> "已暂停共享：本机不上传数据，也不拉取对方数据"
            !prefs.isLoggedIn -> "未登录"
            prefs.hasFreshSyncError ->
                "⚠ 同步失败：${prefs.lastSyncError}\n" +
                    "最后成功：${agoText(prefs.lastSyncOkAt)}\n" +
                    "数据只保存在本机，恢复联网后会自动补传"
            prefs.lastSyncOkAt > 0L -> "同步正常 · 最后成功 ${TimeUtil.timeOf(prefs.lastSyncOkAt)}"
            else -> "尚未完成首次同步（等待采集服务上报）"
        }
        tvSyncState.setTextColor(
            if (prefs.hasFreshSyncError || !prefs.sharing) getColor(R.color.warn_red)
            else getColor(R.color.text_secondary)
        )
    }

    /**
     * 「对方是否暂停了共享」提示。
     * 共享开关是全局的（上传和下行都会停），所以三个 Tab 都要能看到对方的状态。
     */
    private fun refreshPeerSharingHint() {
        val peer = db.loadLive(LocalDb.OWNER_PEER)
        val paused = peer != null && peer.updatedAt > 0L && !peer.sharing
        val text = if (paused) "对方已暂停共享" else ""
        for (tv in listOf(tvTimelinePeerState, tvStatsPeerState)) {
            tv.text = text
            tv.setTextColor(getColor(R.color.warn_red))
            tv.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    /** 把时间戳说成「3 分钟前」这种更好懂的形式 */
    private fun agoText(ts: Long): String {
        if (ts <= 0L) return "从未"
        val d = System.currentTimeMillis() - ts
        return when {
            d < 60_000L -> "刚刚"
            d < 3_600_000L -> "${d / 60_000L} 分钟前"
            d < 86_400_000L -> "${d / 3_600_000L} 小时前"
            else -> "${d / 86_400_000L} 天前"
        }
    }

    private fun refreshOverview() {
        // 我的共享开关（现在在标题栏，三个 Tab 都看得到）
        if (swSharing.isChecked != prefs.sharing) {
            swSharing.isChecked = prefs.sharing
        }
        tvHeaderSharing.text = if (prefs.sharing) "共享中" else "已暂停"
        tvSharingState.text = if (prefs.sharing) "共享中" else "已暂停共享"
        tvSharingState.setTextColor(
            if (prefs.sharing) getColor(R.color.ok_green) else getColor(R.color.warn_red)
        )
        refreshSyncState()
        refreshPeerSharingHint()

        val peer = db.loadLive(LocalDb.OWNER_PEER)
        tvPeerName.text = "对方状态"
        if (peer == null || peer.updatedAt <= 0L) {
            tvPeerState.text = "暂无数据"
            tvPeerUpdated.text = "更新时间：—"
            tvPeerBattery.text = "电量：—"
            tvPeerNet.text = "网络：—"
            tvPeerScreen.text = "屏幕：—"
            tvPeerCurrentApp.text = "当前使用：—"
            tvPeerLocation.text = "位置：—"
            tvPeerTodayScreen.text = "今日屏幕时长：—"
            tvPeerTodayUnlocks.text = "今日解锁次数：—"
            tvPeerTopApps.text = "今日应用 Top5：—"
        } else {
            // 超过 ONLINE_TIMEOUT_MS 没更新就算离线
            val online = System.currentTimeMillis() - peer.updatedAt <= ONLINE_TIMEOUT_MS
            tvPeerState.text = when {
                !peer.sharing -> "已暂停共享"
                online -> "在线"
                else -> "离线"
            }
            tvPeerState.setTextColor(
                when {
                    !peer.sharing -> getColor(R.color.warn_red)
                    online -> getColor(R.color.ok_green)
                    else -> getColor(R.color.text_hint)
                }
            )
            tvPeerUpdated.text =
                "更新时间：${TimeUtil.dateTimeOf(peer.updatedAt)}（${agoText(peer.updatedAt)}）"
            tvPeerBattery.text =
                if (peer.battery >= 0) "电量：${peer.battery}%${if (peer.charging) "（充电中）" else ""}"
                else "电量：—"
            val wifi = peer.wifi
            tvPeerNet.text = when {
                peer.net == "none" -> "网络：未连接"
                !wifi.isNullOrEmpty() -> "网络：WiFi($wifi)"
                peer.net == "wifi" -> "网络：WiFi(名称不可见)"
                peer.net == "mobile" -> "网络：移动数据"
                else -> "网络：${peer.net}"
            }
            tvPeerScreen.text = "屏幕：${if (peer.screenOn) "亮着" else "关闭"}"
            tvPeerCurrentApp.text =
                if (!peer.currentApp.isNullOrEmpty()) "当前使用：${peer.currentApp}" else "当前使用：—"
            val addr = peer.address
            val coord = if (peer.lat != 0.0 || peer.lon != 0.0) {
                String.format(java.util.Locale.US, "%.5f, %.5f", peer.lat, peer.lon)
            } else {
                "无定位"
            }
            tvPeerLocation.text = if (!addr.isNullOrEmpty()) {
                "位置：$addr\n（$coord，精度 ${peer.locAcc.toInt()}m）"
            } else {
                "位置：$coord（地址解析中或后端未配置高德 Key）"
            }
            tvPeerTodayScreen.text = "今日屏幕时长：${TimeUtil.humanMs(peer.todayScreenMs)}"
            tvPeerTodayUnlocks.text = "今日解锁次数：${peer.todayUnlocks}"
            tvPeerTopApps.text = if (peer.todayTopApps.isEmpty()) {
                "今日应用 Top5：—"
            } else {
                val sb = StringBuilder("今日应用 Top5：")
                for ((i, s) in peer.todayTopApps.withIndex()) {
                    sb.append("\n${i + 1}. ${s.label ?: s.pkg}  ${TimeUtil.humanMs(s.ms)}")
                }
                sb.toString()
            }
        }

        // 我的状态（本地库）
        val mine = db.loadLive(LocalDb.OWNER_ME)
        tvMyLive.text = if (mine == null) {
            "还没有采集到数据，请确认采集服务正在运行"
        } else {
            val sb = StringBuilder()
            // 自己的在线状态：超过 3 分钟没采到新状态，说明采集服务被系统杀了
            sb.append(if (System.currentTimeMillis() - mine.updatedAt <= ONLINE_TIMEOUT_MS) "在线" else "离线")
            sb.append("（最后采集 ").append(agoText(mine.updatedAt)).append("）\n")
            sb.append("电量：")
            sb.append(if (mine.battery >= 0) "${mine.battery}%${if (mine.charging) "（充电中）" else ""}" else "—")
            sb.append("\n网络：")
            if (!mine.wifi.isNullOrEmpty()) {
                sb.append("WiFi(").append(mine.wifi).append(")")
            } else if (mine.net == "wifi") {
                sb.append("WiFi(名称不可见)")
            } else {
                sb.append(mine.net)
            }
            sb.append("\n屏幕：").append(if (mine.screenOn) "亮着" else "关闭")
            sb.append("\n当前使用：").append(mine.currentApp ?: "—")
            sb.append("\n位置：")
            if (mine.lat != 0.0 || mine.lon != 0.0) {
                sb.append(String.format(java.util.Locale.US, "%.5f, %.5f", mine.lat, mine.lon))
                // 地址由后端逆地理编码后随 push 响应带回来
                if (!mine.address.isNullOrEmpty()) {
                    sb.append("\n      ").append(mine.address)
                } else {
                    sb.append("（地址解析中或后端未配置高德 Key）")
                }
            } else {
                sb.append("无定位（请检查定位权限）")
            }
            sb.append("\n更新时间：").append(TimeUtil.dateTimeOf(mine.updatedAt))
            sb.toString()
        }
    }

    // ===================== 瞬间 =====================

    private fun setupTimeline() {
        btnOwnerMe.setOnClickListener {
            owner = LocalDb.OWNER_ME
            timelinePage = 0
            refreshTimeline()
        }
        btnOwnerPeer.setOnClickListener {
            owner = LocalDb.OWNER_PEER
            timelinePage = 0
            refreshTimeline()
        }
        btnPrevPage.setOnClickListener {
            if (timelinePage > 0) {
                timelinePage--
                refreshTimeline()
            }
        }
        btnNextPage.setOnClickListener {
            if (timelinePage < timelinePageCount - 1) {
                timelinePage++
                refreshTimeline()
            }
        }
        highlightOwner()
    }

    private fun highlightOwner() {
        val isMe = owner == LocalDb.OWNER_ME
        btnOwnerMe.setBackgroundResource(if (isMe) R.drawable.bg_tab_selected else android.R.color.transparent)
        btnOwnerPeer.setBackgroundResource(if (!isMe) R.drawable.bg_tab_selected else android.R.color.transparent)
    }

    private fun refreshTimeline() {
        highlightOwner()
        // 先把「打开/关闭应用」合并成「某应用使用了多久」，再分页
        val all = TimelineRows.build(db.queryEvents(owner, selectedDate))
        val pages = if (all.isEmpty()) 1 else (all.size + TIMELINE_PAGE_SIZE - 1) / TIMELINE_PAGE_SIZE
        timelinePageCount = pages
        if (timelinePage >= pages) timelinePage = pages - 1
        if (timelinePage < 0) timelinePage = 0

        val from = timelinePage * TIMELINE_PAGE_SIZE
        val to = minOf(from + TIMELINE_PAGE_SIZE, all.size)
        val pageRows = if (from >= all.size) emptyList() else all.subList(from, to)

        timelineAdapter.submit(pageRows)
        tvPageInfo.text = "第 ${timelinePage + 1} / $pages 页 · 共 ${all.size} 条"
        btnPrevPage.isEnabled = timelinePage > 0
        btnNextPage.isEnabled = timelinePage < pages - 1
        tvTimelineEmpty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
    }

    // ===================== 统计 =====================

    private fun setupStats() {
        // 日期按钮在 rebuildDateBar 里生成；这里只处理「我 / 对方」切换
        btnStatsMe.setOnClickListener {
            statsOwner = LocalDb.OWNER_ME
            refreshStats()
            syncStatsFromServer(selectedDate, statsOwner)
        }
        btnStatsPeer.setOnClickListener {
            statsOwner = LocalDb.OWNER_PEER
            refreshStats()
            syncStatsFromServer(selectedDate, statsOwner)
        }
        highlightStatsOwner()
    }

    private fun highlightStatsOwner() {
        val isMe = statsOwner == LocalDb.OWNER_ME
        btnStatsMe.setBackgroundResource(if (isMe) R.drawable.bg_tab_selected else android.R.color.transparent)
        btnStatsPeer.setBackgroundResource(if (!isMe) R.drawable.bg_tab_selected else android.R.color.transparent)
    }

    /**
     * 统计界面只读本地库（保证离线可用）。
     * 服务端的聚合结果由 syncStatsFromServer() 在后台线程写入本地库。
     */
    private fun refreshStats() {
        highlightStatsOwner()
        val apps = db.statsByDay(statsOwner, selectedDate)
        renderAppUsage(apps)
        tvStatsEmpty.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE

        // 当天总时长：本地桶合计优先（可能是服务端天粒度合成桶），否则退化为应用时长之和
        val bucketTotal = db.totalMsByDay(statsOwner, selectedDate)
        val total = if (bucketTotal > 0L) bucketTotal else apps.sumOf { it.ms }
        tvTotalMs.text = TimeUtil.humanMs(total)

        // 近 7 天柱状图
        val totals = db.dailyTotals(statsOwner, 7)
        barChart.setData(totals.keys.toList(), totals.values.toList())
    }

    /** 应用排行直接铺进 LinearLayout，和上面的卡片连成一片、整页一起滚 */
    private fun renderAppUsage(apps: List<Stat>) {
        // 内容没变就不重建：10 秒一次的重建会影响整页滚动位置
        if (apps == renderedApps) return
        renderedApps = apps
        usageAdapter.submit(apps)
        llAppUsage.removeAllViews()
        for (i in apps.indices) {
            llAppUsage.addView(usageAdapter.getView(i, null, llAppUsage))
        }
    }

    /**
     * 后台拉取某天的服务端统计（/me/stats 或 /peer/stats）并落到本地库。
     * 共享暂停时不发任何网络请求。
     */
    private fun syncStatsFromServer(date: String, who: String) {
        if (!prefs.sharing) return
        val server = prefs.serverUrl
        val token = prefs.token
        if (server.isEmpty() || token.isEmpty()) return
        val secret = prefs.payloadSecret
        val api = ApiClient(server, secret, token)
        io.execute {
            try {
                val resp = syncEngine.statsFor(api, who, date) ?: return@execute
                if (resp.apps.isNotEmpty()) {
                    // 存进 day_app 这张独立表，绝不能当成一条 ts=当天 0 点 的统计桶：
                    // 那样会被 statsByDay 的 SUM 加上本地真实桶，同一天的时长直接翻倍，
                    // 表现就是「我这边显示的数字比对方看到的大一截」。
                    db.upsertDayApps(
                        who, date,
                        resp.apps.map { Stat(TimeUtil.dayStart(date), it.pkg, it.label, it.ms) }
                    )
                    main.post { if (who == statsOwner && date == selectedDate) refreshStats() }
                }
            } catch (t: Throwable) {
                if ((t as? ApiException)?.code == "unauthorized") onSessionExpired(t)
                Log.w(TAG, "拉取服务端统计失败", t)
            }
        }
    }

    // ===================== 日期选择条 =====================

    /**
     * @param which "timeline" 或 "stats"（两个 Tab 各自的日期条）
     */
    private fun rebuildDateBar(bar: LinearLayout, which: String) {
        bar.removeAllViews()
        // lastDays() 是「从早到晚」，这里反过来，让「今天」排在最前面
        for (date in TimeUtil.lastDays(7).reversed()) {
            val b = Button(this)
            b.text = TimeUtil.shortLabel(date)
            b.textSize = 13f
            b.setBackgroundResource(
                if (date == selectedDate) R.drawable.bg_tab_selected else android.R.color.transparent
            )
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            lp.rightMargin = (4 * resources.displayMetrics.density).toInt()
            b.layoutParams = lp
            b.setOnClickListener {
                selectedDate = date
                timelinePage = 0
                rebuildDateBar(llDateBar, "timeline")
                rebuildDateBar(llStatsDateBar, "stats")
                refreshTimeline()
                refreshStats()
                syncStatsFromServer(date, statsOwner)
            }
            bar.addView(b)
        }
    }

    private fun refreshAll() {
        refreshOverview()
        refreshTimeline()
        refreshStats()
        // 本地没有当天数据时，后台补一次服务端聚合
        if (db.statsByDay(statsOwner, selectedDate).isEmpty()) {
            syncStatsFromServer(selectedDate, statsOwner)
        }
    }

    /**
     * 登录令牌失效（服务端返回 401）时的自愈：
     * 只清掉令牌、保留「记住的密码」，然后回登录页——那边会用记住的密码静默换一个新令牌，
     * 成功后立刻回到主界面，用户基本无感。
     */
    private fun onSessionExpired(t: Throwable) {
        if (sessionExpired) return
        sessionExpired = true
        Log.w(TAG, "令牌已失效，自动重新登录", t)
        prefs.clearToken()
        main.post {
            Toast.makeText(this, "登录已过期，正在自动重新登录…", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val TAB_OVERVIEW = 0
        private const val TAB_TIMELINE = 1
        private const val TAB_STATS = 2

        /** 概览页刷新周期：10 秒（只读本地库，开销很小） */
        private const val UI_REFRESH_MS = 10_000L

        /** 统计页每隔多少拍（10 秒/拍）主动去服务端重拉一次应用排行：30 拍 = 5 分钟 */
        private const val STATS_RESYNC_EVERY_TICKS = 30L

        /** 「瞬间」每页条数 */
        private const val TIMELINE_PAGE_SIZE = 30

        /** 超过这个时间没有新状态就认为对方（或自己的采集服务）离线：3 分钟 */
        private const val ONLINE_TIMEOUT_MS = 3 * 60 * 1000L
    }
}
