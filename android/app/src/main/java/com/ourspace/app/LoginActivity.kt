package com.ourspace.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * 登录 / 首次配置页（也是 App 的启动入口）。
 *
 * 自动登录流程：
 *  1. 第一次填完信息登录成功后，把用户名和密码一起「记住」；
 *  2. 之后每次打开 App，都用记住的凭据静默登录一次，成功就直接进主界面，用户不用再输；
 *  3. 如果密码被改过（服务端返回 unauthorized）或服务器连不上，就停在登录页让用户处理，
 *     输入框里已经回填好上次的内容，改完直接点登录即可；
 *  4. 想换账号或彻底重新登录，去设置页点「退出登录」——那会清掉记住的密码。
 */
class LoginActivity : Activity() {

    private lateinit var etServer: EditText
    private lateinit var etSecret: EditText
    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var tvStatus: TextView

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ourspace-login").apply { isDaemon = true }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val p = Prefs.get()

        // 已经持有登录令牌：直接进主界面。
        // 令牌有效期 30 天，没必要每次冷启动都再登录一遍——只有令牌真的失效时
        // （MainActivity 收到 401 → 清掉令牌 → 回到这里）才需要重新换令牌。
        if (p.isLoggedIn) {
            CollectorService.start(this)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_login)
        title = "登录"

        etServer = findViewById(R.id.etServer)
        etSecret = findViewById(R.id.etSecret)
        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        btnLogin = findViewById(R.id.btnLogin)
        tvStatus = findViewById(R.id.tvLoginStatus)

        // 回填已保存的配置与「记住的密码」，方便自动登录失败后直接改一改重试
        etServer.setText(p.serverUrl.ifEmpty { DEFAULT_SERVER })
        etSecret.setText(p.payloadSecret)
        etUsername.setText(p.username)
        etPassword.setText(p.savedPassword)
        etPassword.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

        btnLogin.setOnClickListener { doLogin() }

        // 有记住的凭据就自动登录，用户不必再点一次
        if (p.hasSavedCredentials) {
            setBusy(true)
            showStatus("正在自动登录…", isError = false)
            submit(p.serverUrl, p.payloadSecret, p.username, p.savedPassword, auto = true)
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    /** 用户手动点「登录」 */
    private fun doLogin() {
        val server = etServer.text.toString().trim().trimEnd('/')
        val secret = etSecret.text.toString().trim()
        val user = etUsername.text.toString().trim()
        val pass = etPassword.text.toString()

        if (server.isEmpty() || !server.startsWith("http")) {
            showStatus("请填写服务器地址，例如 $DEFAULT_SERVER")
            return
        }
        if (secret.isEmpty()) {
            showStatus("请填写配对密钥（必须与后端 payloadSecret 一致）")
            return
        }
        if (user.isEmpty() || pass.isEmpty()) {
            showStatus("请填写用户名和密码")
            return
        }

        setBusy(true)
        showStatus("正在登录…", isError = false)
        submit(server, secret, user, pass, auto = false)
    }

    /**
     * 真正的登录请求。[auto] 只影响失败时的提示语：
     * 自动登录失败要说清楚「是记住的密码失效了」而不是让用户莫名其妙。
     */
    private fun submit(server: String, secret: String, user: String, pass: String, auto: Boolean) {
        executor.execute {
            try {
                val api = ApiClient(server, secret, "")
                val device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
                val resp = api.login(user, pass, device)

                // 前后端版本校验：不配套就明确报错，绝不带着不匹配的协议往下跑，
                // 否则以后升级时会出现「数据默默对不上」这种最难查的问题。
                val mismatch = api.versionMismatch(resp.protocolVersion, resp.serverVersion)
                if (mismatch != null) {
                    Log.w(TAG, "版本不匹配：client=${ApiClient.PROTOCOL_VERSION} server=${resp.protocolVersion}")
                    main.post {
                        setBusy(false)
                        showStatus("⚠ $mismatch")
                    }
                    return@execute
                }

                val p = Prefs.get()
                p.save(
                    Prefs.SessionConfig(
                        serverUrl = server,
                        payloadSecret = secret,
                        token = resp.token,
                        username = resp.username.ifEmpty { user },
                        uid = resp.uid
                    )
                )
                // 记住密码，供下次打开 App 自动登录
                p.savedPassword = pass
                // 保存配对信息
                if (resp.account.hasPartner) {
                    p.partnerName = resp.account.partnerName
                    p.partnerUid = resp.account.partnerUid
                }
                p.sharing = resp.account.sharing

                main.post {
                    setBusy(false)
                    showStatus("登录成功", isError = false)
                    // 拉起采集前台服务
                    CollectorService.start(this)
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
            } catch (t: Throwable) {
                val ex = t as? ApiException
                val msg = ex?.message ?: (t.message ?: "登录失败")
                Log.w(TAG, "登录失败（auto=$auto）", t)
                main.post {
                    setBusy(false)
                    when {
                        // 自动登录时密码不对：说清楚原因，让用户直接改密码
                        auto && ex?.code == "unauthorized" ->
                            showStatus("记住的密码已失效，请输入新密码后重新登录")
                        auto -> showStatus("自动登录失败：$msg（可直接在下方重试）")
                        else -> showStatus(msg)
                    }
                }
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        btnLogin.isEnabled = !busy
        btnLogin.text = if (busy) "登录中…" else "登录并开始共享"
    }

    private fun showStatus(text: String, isError: Boolean = true) {
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = text
        tvStatus.setTextColor(
            if (isError) getColor(R.color.warn_red) else getColor(R.color.ok_green)
        )
    }

    companion object {
        private const val TAG = "LoginActivity"
        private const val DEFAULT_SERVER = "http://192.168.1.5:8080"
    }
}
