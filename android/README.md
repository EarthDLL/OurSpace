# 我们 · Android 客户端

私密情侣共享 App 的 Android 端，**纯 Kotlin + Android framework**，不使用任何第三方库。

- 唯一的依赖是 **Kotlin 标准库**（由 Kotlin 插件自动加入）。
- 没有 AndroidX / Material / RecyclerView / ConstraintLayout / OkHttp / Gson / 协程。
- 网络用 `java.net.HttpURLConnection`，JSON 用 `org.json`，加密用 `javax.crypto`，
  数据库用 `android.database.sqlite.SQLiteOpenHelper`，线程用 `Executors + Handler`。

接口与字段严格遵循 `docs/API.md`（不要修改那份文档）。

---

## 1. 用 Android Studio 打开并编译

1. **准备 SDK**：确认本机 SDK 路径。仓库里已经写好 `local.properties`：

   ```
   sdk.dir=D\:\\AndroidSDK
   ```

   如果你机器上的路径不同，改这一行即可（`local.properties` 不入库，属于本机配置）。

2. **Gradle Wrapper**：仓库里**只有** `gradle/wrapper/gradle-wrapper.properties`，
   没有 `gradle-wrapper.jar`，也没有 `gradlew` / `gradlew.bat` 脚本。
   这是有意为之——伪造一个 jar 一定会构建失败。两种做法：

   - **推荐**：用 Android Studio 直接 `Open` 这个 `android/` 目录，IDE 会用自己的
     Gradle 发行版（Android Studio 会自动读取 `distributionUrl` 并下载 Gradle 8.11.1）。
   - 或者在本机已装 Gradle 的前提下执行一次 `gradle wrapper`，生成缺失的 jar 与脚本，
     之后再统一用 `./gradlew` 构建。

3. **版本要求**（本机缓存里只有这些，别改）：

   | 组件 | 版本 |
   |---|---|
   | Gradle | 8.11.1 |
   | Android Gradle Plugin | 8.6.1 |
   | Kotlin | 2.1.21 |
   | compileSdk | 36 |
   | buildToolsVersion | 36.0.0 |
   | minSdk / targetSdk | 26 / 35 |
   | Java / Kotlin 目标 | 17 |

4. 编译产物：`app/build/outputs/apk/debug/app-debug.apk`（Debug 直接 Run 即可）。

> 首次编译需要联网拉取 Gradle 发行包与 AGP/Kotlin 插件；依赖已经在 Gradle 缓存里的机器可离线编译。

---

## 2. 后端准备

1. 在后端目录里启动服务：

   ```
   node src/server.js           # 或按后端 README 的方式启动
   ```

2. 用后端命令行创建账号并配对（没有注册接口，账号只能命令行建）：

   ```
   node src/cli.js adduser <用户名> <密码>
   node src/cli.js link <用户A> <用户B>
   ```

3. 记下后端 `config.json` 里的 `payloadSecret`，App 里要填**完全一样**的字符串，
   否则服务端会返回 `bad_envelope`。

---

## 3. 填服务器地址并登录

打开 App → 登录页：

| 字段 | 说明 |
|---|---|
| 服务器地址 | 例如 `http://192.168.1.5:8080`，**不要**带结尾斜杠，也**不要**带 `/api/v1` |
| 配对密钥 | 与后端 `config.json` 的 `payloadSecret` 完全一致 |
| 用户名 / 密码 | 后端命令行创建的那组 |

点「登录并开始共享」。成功后进入主界面，同时启动常驻前台服务。

> 明文 HTTP 是被允许的：`network_security_config.xml` 显式放行明文，
> 因为业务内容在应用层已经用 AES-256-GCM 信封加密（见 `Crypto.kt`），
> 网络上抓到的只是一段不可读的 base64 密文。后端配了 TLS 就填 `https://`，两层叠加。

---

## 4. 首次安装后的权限引导（请按顺序）

打开 **设置** 页，从上到下逐项授予：

1. **使用情况访问权限**
   设置页点第 1 项 →「使用情况访问」→ 在列表里找到「OurSpace」→ 允许。
   *没有它就无法统计应用使用时长和打开/关闭事件。*

2. **定位权限（前台）**
   点第 2 项 → 弹窗里选「仅在使用该应用时允许」。

3. **定位权限（始终允许）**
   Android 11+ 不能直接弹窗申请后台定位，必须手动改：
   点第 3 项会跳到**应用详情页** →「权限」→「位置」→ 选 **「始终允许」**。
   *否则锁屏后拿不到位置，对方看到的位置会停在最后一次。*

4. **通知权限**（Android 13+）
   点第 4 项 → 在系统弹窗里允许。
   *常驻通知「正在共享」是前台服务保命的关键，拒绝通知会让后台更容易被系统杀掉。*

5. **忽略电池优化**
   点第 5 项 → 系统弹窗里选「允许」。
   若系统不给弹窗，会自动跳到电池优化列表，手动把「OurSpace」设为「不优化」。

6. **自启动 / 后台运行**（不同厂商 ROM 不一样）
   点第 6 项会尝试直接打开本机的自启动管理页；打不开就按下面手动设置：

   | 厂商 | 路径 |
   |---|---|
   | 小米 / Redmi | 设置 → 应用设置 → 授权管理 → 自启动 → 允许 |
   | 华为 / 荣耀 | 设置 → 应用 → 应用启动管理 → 找到「OurSpace」→ 手动管理 → 允许自启动 / 关联启动 / 后台活动 |
   | OPPO / 一加 / realme | 设置 → 电池 → 应用耗电管理 → 允许后台运行 |
   | vivo / iQOO | 设置 → 电池 → 后台高耗电 → 允许 |
   | 三星 | 设置 → 电池 → 后台使用限制 → 把「OurSpace」加入「永不休眠的应用」 |
   | 原生 Android | 一般不需要，做好第 5 步即可 |

7. **解锁记录开关**（默认关闭，可选）
   设置页的「解锁记录」开关。打开时会弹二次确认：

   > 该功能会记录并共享你手机的解锁时间，需双方自愿，另一方也能看到你是否关闭了此功能。

   确认后才会开启并上报共享状态；点「取消」则保持关闭。
   **如果本机系统不支持，开关会自动置回关闭，并提示「本机系统不支持解锁记录，已自动关闭」。**

所有权限授予完成后，回到概览页点一次「刷新」，等 60 秒左右就能看到数据。

---

## 5. 功能与采集周期

| 内容 | 周期 | 说明 |
|---|---|---|
| 实时状态（电量 / 充电 / WiFi / 网络类型 / 经纬度 / 屏幕） | **60 秒** | 写入本地 `live` 表并随 `POST /api/v1/push` 上报；地址文字由后端逆地理编码返回 |
| 应用使用时长统计桶 | **600 秒** | `UsageStatsManager.queryEvents` 配对 `ACTIVITY_RESUMED/PAUSED`，ts 对齐 `floor(now/600000)*600000` |
| 瞬间事件（解锁 / 亮屏 / 熄屏 / 开应用 / 关应用 / 开机） | 实时产生，**60 秒**或攒够一批上传 | 解锁由动态注册的 `ACTION_USER_PRESENT` 捕获，受「解锁记录」开关控制 |
| 上传 | 每 60 秒或 outbox 攒够（events ≤ 500、stats ≤ 200/次） | 失败保留在 outbox，指数退避重试，最长 30 分钟 |
| 下行同步（自己 + 对方最近 7 天） | 每 5 分钟 | 写入本地库 `owner='me' / 'peer'`，UI 只读本地库，离线可看 |
| 本地数据修剪 | 每天一次 + 每次启动 | 删除早于 7 天的 `events` / `stats` / 过期 `outbox` |

**共享暂停时**：只写本地数据库，**不产生任何网络请求**（上传和下行同步都会停）。
唯一的例外是你在界面上主动关掉开关的那一次 `POST /api/v1/share`——那是为了让服务端和对方知道「我暂停了」。

主界面三个 Tab：

- **概览**：顶部「我的共享」开关；对方状态卡片（共享状态 / 电量 / WiFi / 网络 / 位置文字 + 经纬度 / 屏幕 / 今日屏幕时长 / 今日解锁次数 / 今日 Top5 应用）；本地「我的状态」。
- **瞬间**：`ListView` 展示 7 天内事件，可切「我 / 对方」和最近 7 天日期。
- **统计**：日期选择、应用时长排行（横向 `ProgressBar`）、当天总时长、近 7 天自绘柱状图（`BarChartView`）。

---

## 6. 代码结构

```
app/src/main/java/com/ourspace/app/
├─ App.kt                 Application：通知渠道 + Prefs 初始化 + 启动修剪
├─ Prefs.kt               SharedPreferences 封装（token / payloadSecret / 开关）
├─ Crypto.kt              AES-256-GCM 信封（key = SHA-256(payloadSecret)）
├─ ApiClient.kt           HttpURLConnection + 信封加解密 + 统一错误
├─ Models.kt              Event / Stat / Live / PushPayload + TimeUtil
├─ LocalDb.kt             SQLiteOpenHelper，表结构同 API.md 第 8 节
├─ Outbox.kt              上传队列（攒批 + 指数退避）+ SyncEngine（下行同步）
├─ UsageCollector.kt      600 秒：应用使用时长统计桶 + app_open/app_close
├─ StatusCollector.kt     60 秒：电量 / WiFi / 网络 / 定位 / 屏幕
├─ CollectorService.kt    前台服务：节拍调度、事件广播、上传、保活
├─ BootReceiver.kt        开机自启
├─ PermissionHelper.kt    权限校验与系统设置跳转
├─ LoginActivity.kt       登录 / 首次配置
├─ MainActivity.kt        主界面（三个 Tab）
├─ SettingsActivity.kt    设置页（开关 / 权限引导 / 同步 / 退出）
├─ TimelineAdapter.kt     瞬间列表
├─ AppUsageAdapter.kt     应用时长排行
└─ BarChartView.kt        自绘柱状图
```

本地库表结构（同 `docs/API.md` 第 8 节）：

```sql
events(id, owner, ts, type, pkg, label, UNIQUE(owner,ts,type,pkg));
stats (id, owner, ts, pkg, label, ms,   UNIQUE(owner,ts,pkg));
outbox(id, kind, body, created);
live  (owner PK, json, updatedAt);
```

---

## 7. 常见问题

**Q：常驻通知没了 / 服务被杀，数据不再更新。**
依次检查：① 设置页第 4 项通知权限是否授予；② 第 5 项电池优化是否已忽略；
③ 第 6 项厂商自启动是否允许；④ 最近任务里把「OurSpace」**加锁**（很多 ROM 划掉就杀）。
本应用已用前台服务 + `START_STICKY` + `onTaskRemoved` 保持存活，但厂商 ROM 的省电策略
优先级更高，只能靠白名单解决。

**Q：概览页对方一直显示「暂无数据」。**
① 确认后端用 `node src/cli.js link A B` 完成了配对；
② 确认对方 App 里「我的共享」开关是打开的；
③ 若对方关闭了共享，服务端仍会返回 200，界面会显示「已暂停」而不是报错。

**Q：位置一直是空的 / 地址为空。**
- 经纬度为空 → 检查设置页第 2、3 项定位权限，尤其是**后台定位**必须手动选「始终允许」。
- 经纬度有值但 `address` 为空 → 后端 `config.json` 的 `amapKey` 没配或逆地理编码失败，
  地址文字由后端返回，客户端不集成高德 SDK。

**Q：WiFi 名称显示「（名称不可见）」。**
Android 10+ 读 SSID 需要定位权限，且部分 ROM 要求定位开关处于打开状态；
另外 `<unknown ssid>` 会被过滤掉，这是系统正常行为。

**Q：统计页某天没有数据。**
统计依赖「使用情况访问权限」。另外 App 被杀期间不会产生新的统计桶；
本地库里没有的数据会尝试从服务端 `/me/stats`、`/peer/stats` 拉取一次。

**Q：登录提示 `bad_envelope`。**
配对密钥和后端 `payloadSecret` 不一致。修正后重新登录即可。

**Q：登录提示「登录已失效，请重新登录」。**
Token 有效期 30 天，过期后到设置页重新登录。

**Q：我暂停共享后，对方还能看到我的数据吗？**
不能。暂停后本机不再上传任何数据，服务端会被标记为暂停，对方看到「已暂停共享」。
但你自己的本机记录仍在继续（只写本地库），恢复共享后会继续上传。
解锁记录同理——关闭后本机既不记录也不上传。

**Q：换手机 / 重装后历史还在吗？**
本机缓存会被清空，但联网后后台会从服务端把自己和对方最近 7 天的历史重新拉回本地。

**Q：为什么明文 HTTP 也可以？**
见上文第 3 节：业务内容在应用层已经做过 AES-256-GCM 信封加密，
不过仍建议在公网部署时给后端配上 TLS（`config.json.tls`）。
