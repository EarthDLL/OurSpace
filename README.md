# OurSpace · 我们

两个人的私密状态共享系统。**自己的后端、自己的数据库、自己的服务器**，不把权限交给任何商业 App。

```
┌─────────────┐   AES-256-GCM 信封 + HTTPS   ┌──────────────┐
│  Android A  │ ───────────────────────────► │  Node.js 后端 │
│  (前台服务) │ ◄─────────────────────────── │  SQLite 7 天  │
└─────────────┘                              └──────┬───────┘
                                                    │ 逆地理编码
┌─────────────┐                                     ▼
│  Android B  │                              高德 Web 服务 API
└─────────────┘
```

## 能共享什么

| 内容 | 频率 | 是否留历史 |
|---|---|---|
| 当前位置（经纬度 → 文字地址） | 60 秒 | 否，只看最新 |
| 手机电量 / 是否充电 | 60 秒 | 否 |
| WiFi 名称 / 网络类型 | 60 秒 | 否 |
| 屏幕是否点亮 | 60 秒 | 否 |
| 应用使用时长（按应用） | 10 分钟聚合 | 是，7 天 |
| 手机解锁记录（可选开关 + 二次确认） | 实时 | 是，7 天 |
| 应用使用记录（打开/关闭某应用） | 实时 | 是，7 天 |
| 亮屏 / 熄屏 / 开机 | 实时 | 是，7 天 |

## 目录

| 路径 | 说明 |
|---|---|
| [dist/OurSpace-release.apk](dist/OurSpace-release.apk) | **正式签名的 APK**（0.7 MB），日常装机用这个 |
| [dist/OurSpace-debug.apk](dist/OurSpace-debug.apk) | 调试包（可 `run-as` 抓数据，仅开发用） |
| [docs/API.md](docs/API.md) | **接口与加密契约**，前后端唯一依据 |
| [docs/部署与使用.md](docs/部署与使用.md) | 从建号到手机装上的完整步骤 |
| [backend/](backend/) | Node.js 后端（**零第三方依赖**） |
| [android/](android/) | Android 客户端（Kotlin，纯 framework UI） |
| [tools/build-apk.ps1](tools/build-apk.ps1) | 重新编译（`-Release` 出正式包） |

## 三条设计红线

1. **数据是自己的。** 后端跑在你自己的机器/服务器上，数据库是一个 SQLite 文件，备份就是复制文件。
2. **权限是自己给的。** 解锁记录默认关闭，开启必须二次确认；随时可单方暂停共享，暂停后手机不再发出任何上传请求。
3. **内容是自己加密的。** 所有业务请求/响应体在 HTTPS 之上再做一层 AES-256-GCM 信封加密，配对密钥只存在于你和对象的手机、以及你的服务器配置文件里。

## 快速开始

```powershell
# 1. 后端
cd backend
copy config.example.json config.json     # 填 payloadSecret / jwtSecret / amapKey
node src/cli.js adduser alice 你的密码
node src/cli.js adduser bob 对象密码
node src/cli.js link alice bob
node src/server.js

# 2. 客户端（已编译好，也可自行重新编译）
#    正式包：dist\OurSpace-release.apk  —— 日常装机用这个
#    调试包：dist\OurSpace-debug.apk
powershell -ExecutionPolicy Bypass -File tools/build-apk.ps1 -Release
# 产物：android/app/build/outputs/apk/release/app-release.apk
```

> **签名**：release 用独立密钥 `android/keystore/ourspace-release.jks` 签名
> （密码在同目录的 `keystore.properties`，两者都不入库）。
> **这个 jks 一定要单独备份**——丢了就没法给你已装的 App 做覆盖升级，只能卸载重装。
> 换签名后无法覆盖安装旧包，需要先卸载。

手机装上后打开 App，填「服务器地址 + 配对密钥 + 用户名 + 密码」，再按设置页的权限引导逐项授权。

详见 [docs/部署与使用.md](docs/部署与使用.md)。
