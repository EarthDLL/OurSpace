# OurSpace 后端

私密情侣共享 App 的服务端。**零第三方依赖**，只用 Node.js 内置模块：

| 能力 | 用的内置模块 |
|---|---|
| HTTP / HTTPS 服务 | `node:http` / `node:https` |
| 数据库 | `node:sqlite`（Node ≥ 22.5 内置，启动时会打印 ExperimentalWarning，已自动抑制） |
| 信封加密 / JWT | `node:crypto`（AES-256-GCM + HMAC-SHA256） |
| 密码哈希 | `crypto.scryptSync`（每用户随机 salt） |
| 逆地理编码出站请求 | 内置 `fetch` |

接口契约见 `docs/API.md`（本仓库唯一契约，请勿修改）。除 `GET /api/v1/health` 外，
**所有请求体与响应体都是信封 `{"e":"..."}`**（AES-256-GCM，密钥 = SHA-256(payloadSecret)）。

---

## 1. 快速开始

```bash
# 1) 从模板创建配置
copy config.example.json config.json      # Linux/macOS: cp config.example.json config.json

# 2) 生成两串随机密钥并填进 config.json
node -e "console.log(require('crypto').randomBytes(32).toString('base64url'))"
node -e "console.log(require('crypto').randomBytes(32).toString('base64url'))"

# 3) 建号 / 配对
node src/cli.js adduser alice 强密码1
node src/cli.js adduser bob   强密码2
node src/cli.js link alice bob
node src/cli.js list

# 4) 启动
npm start                 # 等同 node src/server.js
```

启动后日志会打印监听地址、数据库路径、保留期、TLS/高德开关。健康检查（**明文**，可直接用浏览器打开）：

```
http://127.0.0.1:8080/api/v1/health
{"ok":true,"service":"ourspace","retentionDays":7,"amap":false}
```

自测（临时端口 + 临时数据库，不污染正式数据）：

```bash
node test/smoke.js        # 全部断言通过时打印 SMOKE PASS
```

---

## 2. 配置说明 `config.json`

| 字段 | 类型 | 说明 |
|---|---|---|
| `port` | number | 监听端口，默认 8080。**环境变量 `PORT` 会覆盖它**。 |
| `host` | string | 监听地址。`0.0.0.0` 表示所有网卡（局域网可访问）；只允许本机反代访问就填 `127.0.0.1`。 |
| `tls.enabled` | boolean | `true` 时用 `https` 启动（需同时填 cert/key）。 |
| `tls.cert` | string | 证书文件路径（PEM）。自签或正规证书都行。 |
| `tls.key` | string | 私钥文件路径（PEM）。 |
| `payloadSecret` | string | **配对密钥，必须和 App 设置页里填的完全一致**（区分大小写、不能有首尾空格）。它决定信封加密密钥：`key = SHA-256(UTF-8(payloadSecret))`。改了这一串，旧客户端立刻解不开数据。 |
| `jwtSecret` | string | 登录 token（HS256 JWT）的签名密钥，和 `payloadSecret` 用**两串不同的**随机值。 |
| `amapKey` | string | 高德 Web 服务 Key，用于 `peer/live` 的 `address` 逆地理编码。留空则 `address` 恒为 `null`，不影响其它功能。 |
| `db` | string | SQLite 文件路径。相对路径按 `backend/` 解析。 |
| `retentionDays` | number | 保留天数（默认 7）。服务端只保留最近 N 天的 events/stats，`peer/daily` 也按它截断。 |
| `liveIntervalSec` | number | 客户端上报实时状态的周期（60），服务端只做透传与展示。 |
| `statsIntervalSec` | number | 统计桶粒度（600 = 10 分钟）。服务端写入时会把 `stats.ts` 向下对齐到这个整点。 |

### 生成随机密钥的建议命令

```bash
# payloadSecret（填进 App 和后端 config.json，两边必须一致）
node -e "console.log(require('crypto').randomBytes(32).toString('base64url'))"
# 例：K7fQ2m9xT1vP4sR8uW0yZ3aB6cD5eF7gH9jK2lM4nP6

# jwtSecret（只放后端，别给客户端）
node -e "console.log(require('crypto').randomBytes(48).toString('base64url'))"
```

PowerShell 等价写法：`node -e "console.log(require('crypto').randomBytes(32).toString('base64url'))"`；
没有 node 在 PATH 里时用全路径，例如 Windows 上是 `"D:\Program Files\DSH\node\node.exe"`。

---

## 3. 命令行管理

```
node src/cli.js adduser <username> <password>   # 建号
node src/cli.js passwd  <username> <password>   # 改密（会让该用户旧 token 全部失效）
node src/cli.js link    <usernameA> <usernameB> # 双向配对
node src/cli.js unlink  <username>              # 解除配对
node src/cli.js list                            # 列出用户/共享开关/配对/最后活跃
```

任何命令都可以追加 `--config <路径>` 指定另一个配置文件（默认 `backend/config.json`），
方便多实例或测试。账号**只能**由后端命令行创建，服务端没有注册/找回密码接口。

---

## 4. 部署方式

### 4.1 直接 HTTP（仅内网，靠应用层加密）

`config.json` 里 `tls.enabled = false`，启动后 App 基地址填 `http://<内网IP>:8080`。
业务数据仍然是 AES-256-GCM 信封加密的，抓包看不到内容，但**登录 token 和响应体长度会暴露**，
只建议在完全可信的家庭局域网里这么用。记得放行防火墙端口：

```powershell
New-NetFirewallRule -DisplayName "OurSpace 8080" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow
```

### 4.2 直接 HTTPS（自签或正规证书）

先拿证书。自签（局域网自用，App 需允许信任自签证书）：

```bash
openssl req -x509 -newkey rsa:2048 -nodes -keyout key.pem -out cert.pem -days 825 \
  -subj "/CN=ourspace.local" -addext "subjectAltName=IP:192.168.1.5"
```

正规证书可用 Let's Encrypt（certbot）或域名服务商签发。然后：

```jsonc
"tls": { "enabled": true, "cert": "cert.pem", "key": "key.pem" }
```

启动：`npm start`，访问 `https://192.168.1.5:8080`。App 基地址填同一个。

### 4.3 Nginx / Caddy 反向代理 + HTTPS（推荐）

后端只监听本机，TLS 交给反向代理，证书续期更省心。`config.json`：

```jsonc
{ "host": "127.0.0.1", "port": 8080, "tls": { "enabled": false, "cert": "", "key": "" } }
```

**Nginx**（`/etc/nginx/conf.d/ourspace.conf`）：

```nginx
server {
  listen 443 ssl;
  server_name ourspace.example.com;
  ssl_certificate     /etc/letsencrypt/live/ourspace.example.com/fullchain.pem;
  ssl_certificate_key /etc/letsencrypt/live/ourspace.example.com/privkey.pem;

  client_max_body_size 2m;          # 与后端 2MB 上限保持一致
  location /api/ {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_read_timeout 30s;
  }
}
```

**Caddy**（自动申请/续期证书，最省事）：

```
ourspace.example.com {
  encode zstd gzip
  request_body { max_size 2MB }
  reverse_proxy 127.0.0.1:8080
}
```

App 基地址填 `https://ourspace.example.com`（不要带 `/api/v1`，路径由 App 自己拼）。

---

## 5. 数据备份与保留期

- **备份**：全部数据都在一个 SQLite 文件里（默认 `data/ourspace.db`）。停服后直接拷贝即可；
  不停服时 WAL 模式下建议同时拷贝 `ourspace.db`、`ourspace.db-wal`、`ourspace.db-shm` 三个文件。
  ```powershell
  Copy-Item data\ourspace.db "D:\backup\ourspace-$(Get-Date -f yyyyMMdd).db"
  ```
  ```bash
  cp data/ourspace.db ~/backup/ourspace-$(date +%F).db
  ```
- **恢复**：停服 → 用备份文件覆盖 `data/ourspace.db` → 启动。
- **保留期**：默认 7 天。服务启动时清理一次，之后**每小时**再清理一次，删除
  `ts < now - retentionDays*86400000` 的 events / stats（`live` 只保留最新一条，不参与清理）。
  `peer/daily`、`peer/events`、`peer/stats` 在查询时也会按保留期截断，超期数据不会返回。
- 日志只输出清理条数与启动信息，不记录坐标、应用名等内容。

---

## 6. 常驻运行

### 6.1 Windows 计划任务（开机自启）

```powershell
$node = "D:\Program Files\DSH\node\node.exe"
$work = "D:\DSH\OurSpace\backend"

$action  = New-ScheduledTaskAction -Execute $node -Argument "src\server.js" -WorkingDirectory $work
$trigger = New-ScheduledTaskTrigger -AtStartup
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType S4U -RunLevel Highest
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)

Register-ScheduledTask -TaskName "OurSpace Backend" -Action $action -Trigger $trigger `
  -Principal $principal -Settings $settings -Description "OurSpace 后端常驻"

Start-ScheduledTask -TaskName "OurSpace Backend"
Get-ScheduledTaskInfo -TaskName "OurSpace Backend"
```

用 `Unregister-ScheduledTask -TaskName "OurSpace Backend"` 删除；
看日志可以改成 `-Argument "src\server.js *> logs\server.log"`（先建 `logs` 目录）。

### 6.2 Linux systemd

`/etc/systemd/system/ourspace.service`：

```ini
[Unit]
Description=OurSpace backend
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=ourspace
WorkingDirectory=/opt/ourspace/backend
ExecStart=/usr/bin/node src/server.js
Restart=always
RestartSec=3
Environment=NODE_ENV=production
# 需要临时改端口时：Environment=PORT=9090
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now ourspace
sudo systemctl status ourspace
journalctl -u ourspace -f
```

---

## 7. 目录结构

```
backend/
  package.json          # private, commonjs, scripts: start / adduser / test
  config.example.json   # 配置模板
  config.json           # 你自己的配置（已 gitignore）
  data/ourspace.db      # SQLite 数据（已 gitignore）
  src/
    config.js           # 读 config.json + 校验 + PORT 环境变量覆盖
    crypto.js           # 信封加解密、scrypt 密码哈希、HS256 JWT
    db.js               # 建表、预编译语句、保留期清理
    http.js             # 极简路由 + 2MB 体积限制 + 统一错误响应
    amap.js             # 高德逆地理编码 + 10 分钟内存缓存（坐标取 3 位小数）
    routes.js           # 所有 /api/v1 业务接口
    app.js              # 装配：db + 路由 + Bearer 鉴权
    cli.js              # adduser / passwd / link / unlink / list
    server.js           # 入口：http 或 https，启动时与每小时清理
  test/smoke.js         # 端到端自测（临时端口 + 临时 db）
```

## 8. 常见问题

- **启动报“找不到配置文件”**：`copy config.example.json config.json`。
- **启动报 `payloadSecret 还没设置`**：模板里的占位文字没改，填上真实随机串。
- **App 一登录就报 bad_envelope / 解不开**：App 里填的 `payloadSecret` 与后端 `config.json`
  不一致（最常见是多了空格或大小写不同）。
- **`address` 一直是 null**：`amapKey` 没填、Key 类型不对（要用“Web 服务”Key）、或高德配额用尽。
- **改密后 App 掉线**：`passwd` 会递增 token 版本，旧 token 立即失效，属预期行为，重新登录即可。
