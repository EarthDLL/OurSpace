# OurSpace 接口与数据契约 v1

本文件是后端与 Android 客户端的**唯一契约**。两端实现必须严格遵守。

## 0. 总体

- 基地址：由用户在 App 设置页填写，例如 `https://example.com` 或 `http://192.168.1.5:8080`。
  所有接口路径前缀 `/api/v1`。
- 编码：UTF-8，`Content-Type: application/json`。
- 时间：一律 Unix 毫秒（`long`）。日期字符串一律 `YYYY-MM-DD`，按**服务端本地时区**切分。
- 保留期：`RETENTION_DAYS = 7`。服务端与客户端都只能读到最近 7 天（含今天）。

## 1. 传输加密（应用层信封）

即使跑在明文 HTTP 上，业务内容也不可读。

- 双方共享一个**配对密钥** `payloadSecret`（App 设置页手工输入，后端 `config.json` 里配置同一串）。
- 密钥派生：`key = SHA-256(UTF-8(payloadSecret))` → 32 字节。
- 算法：`AES-256-GCM`，随机 `iv` 12 字节，`tag` 16 字节。
- 信封：**请求体和响应体的 JSON 统一替换为**
  `{"e":"<base64( iv || ciphertext || tag )>"}`
- 例外：`GET /api/v1/health` 返回明文 `{"ok":true,...}`（探活用）。
- 服务端解不开 → `400 {"error":"bad_envelope"}`。
- 建议同时在 HTTPS 上部署（`config.json.tls`），两层叠加。

## 2. 认证

- `Authorization: Bearer <token>`，token 为 HS256 JWT，有效期 30 天，载荷 `{uid, username}`。
- 账号**只能由后端命令行创建**，没有注册/找回密码接口。

## 3. 数据结构

```jsonc
// 事件（瞬间记录）
{ "ts": 1730000000000, "type": "unlock", "pkg": null, "label": null }
// type ∈ unlock | screen_on | screen_off | app_open | app_close | boot
// app_open/app_close 必须带 pkg 与 label（应用显示名）

// 统计桶（10 分钟粒度）
{ "ts": 1730000000000, "pkg": "com.tencent.mm", "label": "微信", "ms": 62000 }
// ts 必须对齐到 10 分钟整点： floor(now / 600000) * 600000

// 实时状态（不落历史，只保留最新一条）
{
  "battery": 82, "charging": true,
  "wifi": "HomeWiFi", "net": "wifi",        // net ∈ wifi | mobile | none
  "lat": 31.2304, "lon": 121.4737, "locAcc": 25.0,
  "screenOn": true,
  "currentApp": "微信",                      // 当前前台应用名，读不到权限时为 null
  "updatedAt": 1730000000000
}
```

## 4. 接口

### 4.1 `POST /api/v1/auth/login`
请求 `{ "username":"a", "password":"...", "device":"Pixel 7" }`
响应 `{ "token":"...", "uid":1, "username":"a", "partner": {"uid":2,"username":"b","sharing":true} | null, "serverTime": 1730000000000, "retentionDays": 7, "protocolVersion": 1, "serverVersion": "1.0.0" }`

**版本校验**：客户端内置同一个 `protocolVersion`，登录时比对；不一致就直接在登录页报错并拒绝进入，
不允许「带着不匹配的协议继续跑」。改动协议（字段、语义、信封格式）必须同时改
`backend/src/version.js` 的 `PROTOCOL_VERSION` 和客户端 `ApiClient.PROTOCOL_VERSION`。

### 4.2 `POST /api/v1/auth/logout`
请求 `{}` → `{"ok":true}`（服务端把 token 记入黑名单/`token_version+1` 即可，简单实现：仅返回 ok）

### 4.3 `POST /api/v1/push`
上传本机数据（离线可攒批，`events` 最多 500 条/次）。

请求：
```jsonc
{
  "events": [ /* 事件数组 */ ],
  "stats":  [ /* 统计桶数组 */ ],
  "live":   { /* 实时状态，可为 null */ },
  "sharing": true          // 本机当前是否在共享；服务端据此更新用户状态
}
```
响应：`{ "ok":true, "serverTime":1730000000000, "accepted":{"events":12,"stats":8}, "address":"上海市黄浦区…"|null, "addressAt":1730000000000 }`
说明：
- 服务端对 `events` 按 `(uid, ts, type, pkg)` 幂等去重；`stats` 按 `(uid, ts, pkg)` **累加或覆盖取大**（实现取 `ms = max(旧, 新)`）。
- **`address` 是后端顺手返回的逆地理编码结果**。因为地址只在「别人来读你的 live」时才会算，
  一个手机自己测试时永远看不到自己的地址；所以 push 时也一并算（命中缓存就同步返回，
  没命中先返回 null 并异步补算，下次就有了）。客户端拿到后写回本地 live，概览页「我的状态」即可显示。

### 4.4 `GET /api/v1/peer/live`
响应：
```jsonc
{
  "sharing": true, "updatedAt": 1730000000000, "lastSeenAt": 1730000000000,
  "battery": 82, "charging": true, "wifi": "HomeWiFi", "net": "wifi",
  "lat": 31.2304, "lon": 121.4737, "locAcc": 25.0,
  "address": "上海市黄浦区人民大道 200 号",     // 后端调高德逆地理编码得到，可能为 null
  "addressAt": 1730000000000,
  "screenOn": true,
  "currentApp": "微信",                        // 对方当前前台应用名，可为 null
  "todayScreenMs": 3600000, "todayUnlocks": 23,
  "todayTopApps": [ {"pkg":"com.tencent.mm","label":"微信","ms":1800000} ],  // 最多 5 个
  "serverTime": 1730000000000
}
```
未配对/已被对方暂停 → 仍然返回 200，用 `sharing:false` 与 `updatedAt` 表示。

### 4.5 `GET /api/v1/peer/events?date=YYYY-MM-DD&since=<ts>`
响应 `{ "date":"2025-01-01", "since":0, "items":[ {"ts":..,"type":"unlock","pkg":null,"label":null} ], "serverTime":.. }`（按 ts 倒序，最多 2000 条）

**`since` 是增量同步游标**（很重要，否则每天要传几十万次全量请求）：
- `since` 省略或为 0 → 全量返回这一天。
- `since > 0` → 只返回 `ts >= since` 的事件。客户端传「我已经拿到哪一条了」。
- 边界上会重复返回 ts 正好等于 since 的那条，客户端按 `UNIQUE(owner,ts,type,pkg)` 去重即可（所以服务端用 `>=` 而不是 `>`，避免同一毫秒的多条事件被漏掉）。

客户端配套策略：
- **当天**：每次都带 `since` 增量拉，通常返回空数组；
- **过去的日子**：拉一次之后「封盘」，之后永不重拉（那天的数据不会再变了）；
- 所以 steady state 下每轮只有 2 个事件请求（自己 + 对方各一个当天增量），而不是 7×2 个全量请求。

### 4.6 `GET /api/v1/me/events?date=YYYY-MM-DD&since=<ts>`  形状与语义同上。

### 4.7 `GET /api/v1/peer/stats?date=YYYY-MM-DD`
```jsonc
{ "date":"2025-01-01", "totalMs": 7200000,
  "apps": [ {"pkg":"com.tencent.mm","label":"微信","ms":1800000} ],   // 倒序，最多 50
  "hourly": [ {"hour":0,"ms":0}, ... 24 项 ],
  "serverTime": .. }
```

### 4.8 `GET /api/v1/peer/daily?days=7`
```jsonc
{ "days": [ {"date":"2025-01-01","totalMs":7200000,"unlocks":23,"topAppLabel":"微信"} ], "serverTime":.. }
```
（按日期升序，最多 `days` 项，超出保留期直接截断）

### 4.9 `GET /api/v1/me/daily?days=7`  形状同上。

### 4.9b `GET /api/v1/me/stats?date=YYYY-MM-DD`
形状与 `4.7 peer/stats` 完全相同，只是数据属于自己。客户端重装后用它回填本地库。

### 4.10 `POST /api/v1/share`
请求 `{ "sharing": false }` → `{"sharing":false}`
语义：单方暂停。暂停后本机不再上传（客户端自行停止），服务端把该用户标记为暂停，对方看到"已暂停共享"。

### 4.11 `GET /api/v1/me`
`{ "uid":1,"username":"a","sharing":true,"partner":{"uid":2,"username":"b","sharing":true}|null,"retentionDays":7,"serverTime":.. }`

### 4.12 `GET /api/v1/health`
明文 `{ "ok":true,"service":"ourspace","retentionDays":7,"amap":true|false,"protocolVersion":1,"serverVersion":"1.0.0" }`

设置页用它显示「服务端版本 / 协议」，版本不一致时红字提示。

## 5. 错误

统一 `{ "error": "<code>", "message": "<人类可读>" }`（同样走信封加密）。
`code` ∈ `bad_envelope` | `bad_request` | `unauthorized` | `forbidden` | `not_found` | `server_error`。

## 6. 后端配置 `backend/config.json`

```jsonc
{
  "port": 8080,
  "host": "0.0.0.0",
  "tls": { "enabled": false, "cert": "", "key": "" },
  "payloadSecret": "改成一串长随机字符（和 App 里填的一样）",
  "jwtSecret": "另一串长随机字符",
  "amapKey": "",
  "db": "data/ourspace.db",
  "retentionDays": 7,
  "liveIntervalSec": 60,
  "statsIntervalSec": 600
}
```

## 7. 后端命令行

```
node src/cli.js adduser <username> <password>
node src/cli.js link <usernameA> <usernameB>
node src/cli.js unlink <username>
node src/cli.js list
```

## 8. 客户端本地库（SQLite，同样 7 天修剪）

```sql
events(id INTEGER PK, owner TEXT, ts INTEGER, type TEXT, pkg TEXT, label TEXT, UNIQUE(owner,ts,type,pkg));
stats (id INTEGER PK, owner TEXT, ts INTEGER, pkg TEXT, label TEXT, ms INTEGER, UNIQUE(owner,ts,pkg));
outbox(id INTEGER PK, kind TEXT, body TEXT, created INTEGER);   -- kind ∈ event|stat|live|share
live  (owner TEXT PK, json TEXT, updatedAt INTEGER);
sync_cursor(owner, date, lastTs, finalized, PRIMARY KEY(owner,date));  -- 增量同步游标
```

> **去重陷阱**：SQLite 的 UNIQUE 约束把 NULL 视为互不相等，所以 `UNIQUE(owner,ts,type,pkg)`
> 对无包事件（解锁/亮屏/熄屏/开机，`pkg IS NULL`）**完全不起作用**。
> 客户端因此额外建了一个表达式唯一索引
> `CREATE UNIQUE INDEX idx_events_dedup ON events(owner, ts, type, IFNULL(pkg,''))`，
> 否则每次增量同步都会把边界那条事件重复插一遍。
> （后端是靠 `normalizeEvent` 把无包事件统一存成空串来规避的，两边做法不同但目的相同。）
`owner` ∈ `me` | `peer`。UI 一律只读本地库 → 离线也能看 7 天历史；联网时后台把服务端数据写入本地库再刷新 UI。

## 9. 采集周期（客户端）

| 内容 | 周期 | 说明 |
|---|---|---|
| 实时状态（电量/WiFi/屏幕/当前应用） | 10s | 不落历史，仅推送 `live` |
| 位置（向系统请求一次新定位） | 300s | 状态仍 10s 上报一次，中间用的是最近一次已知位置，避免持续唤醒 GPS |
| 对方实时状态拉取 | 10s | 只拉 `/peer/live` 这一个小请求 |
| 当天瞬间记录增量拉取 | 10s | 带 `since` 游标，没有新记录就是空数组 |
| 应用使用时长统计桶 | 60s 采集一次 | 桶仍然是 **10 分钟对齐**；每次写的是「该桶到目前为止的累计值」，取较大值写入，反复写不会重复计数 |
| 瞬间记录 | 实时产生，10s 或满 30 条批量上传 | unlock 由 `ACTION_USER_PRESENT` 动态广播捕获 |
| 天粒度汇总 + 过去日期封盘 | 300s | `/me/daily`、`/peer/daily` |
| 本地/服务端旧数据修剪 | 每天 1 次 | 删除早于 7 天前的数据 |
