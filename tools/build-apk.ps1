# OurSpace 一键编译 APK
# 用法： powershell -ExecutionPolicy Bypass -File tools/build-apk.ps1 -Release
param(
    [switch]$Release,
    [switch]$Clean
)

$ErrorActionPreference = "Stop"
$root  = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$proj  = Join-Path $root "android"

# 这台机器对「用户目录下的写入」有限制：
#   - Gradle 的 native 库从 %USERPROFILE%\.gradle 加载会失败
#     （Failed to load native library 'native-platform.dll'）；
#   - Kotlin 编译器建不了 %LOCALAPPDATA%\Temp / kotlin\daemon 下的文件
#     （AccessDeniedException / Could not connect to Kotlin compile daemon）。
# 所以把 Gradle 家目录与临时目录固定到工程内。别的机器上这样也无副作用。
$env:GRADLE_USER_HOME = Join-Path $root ".gh"
$env:TEMP = Join-Path $root ".tmp"
$env:TMP  = Join-Path $root ".tmp"
New-Item -ItemType Directory -Force $env:GRADLE_USER_HOME | Out-Null
New-Item -ItemType Directory -Force $env:TEMP | Out-Null

function Write-Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Fail($msg) { Write-Host "[错误] $msg" -ForegroundColor Red; exit 1 }

# ---------- 1. Java ----------
# AGP 8.6 / Kotlin 2.1 只支持 JDK 17~21，不能用 JDK 25。
$candidates = @()
if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
$candidates += @(
    "$root\tools\jdk\jdk-*",
    "D:\Program Files\Android\Android Studio\jbr",
    "C:\Program Files\Android\Android Studio\jbr",
    "$env:LOCALAPPDATA\Programs\Android Studio\jbr",
    "D:\Program Files\Java\jdk-21*",
    "C:\Program Files\Java\jdk-21*",
    "D:\Program Files\Java\jdk-17*",
    "C:\Program Files\Java\jdk-17*"
)
$javaHome = $null
foreach ($c in $candidates) {
    foreach ($p in (Resolve-Path $c -ErrorAction SilentlyContinue)) {
        $java = Join-Path $p.Path "bin\java.exe"
        if (-not (Test-Path $java)) { continue }
        $out = & $java -version 2>&1 | Out-String
        if ($out -match 'version "(\d+)') {
            $major = [int]$Matches[1]
            if ($major -ge 17 -and $major -le 23) { $javaHome = $p.Path; break }
        }
    }
    if ($javaHome) { break }
}
if (-not $javaHome) {
    Fail "找不到 JDK 17~21。请安装 JDK 21（或直接装 Android Studio，它自带 JBR），然后重试。`n注意：JDK 25 不被 AGP 8.6 / Kotlin 2.1 支持。"
}
$env:JAVA_HOME = $javaHome
Write-Step "JAVA_HOME = $javaHome"

# ---------- 2. Android SDK ----------
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) {
    foreach ($c in @("$env:LOCALAPPDATA\Android\Sdk", "D:\AndroidSDK", "C:\Android\Sdk")) {
        if (Test-Path $c) { $sdk = $c; break }
    }
}
if (-not $sdk -or -not (Test-Path $sdk)) { Fail "找不到 Android SDK。请设置 ANDROID_HOME 环境变量。" }
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
Write-Step "ANDROID_HOME = $sdk"

# 检查必需组件
foreach ($need in @("platforms\android-36", "build-tools\36.0.0", "platform-tools")) {
    if (-not (Test-Path (Join-Path $sdk $need))) {
        Write-Host "[警告] SDK 缺少 $need，编译可能失败，请用 Android Studio 的 SDK Manager 安装。" -ForegroundColor Yellow
    }
}

# local.properties
$lp = Join-Path $proj "local.properties"
$sdkEscaped = $sdk -replace '\\', '\\'
"sdk.dir=$sdkEscaped" | Set-Content -Path $lp -Encoding ASCII
Write-Step "写入 $lp"

# ---------- 3. Gradle ----------
$gradle = $null
$wrapperJar = Join-Path $proj "gradle\wrapper\gradle-wrapper.jar"
if ((Test-Path $wrapperJar) -and (Test-Path (Join-Path $proj "gradlew.bat"))) {
    $gradle = Join-Path $proj "gradlew.bat"
} else {
    $cmd = Get-Command gradle -ErrorAction SilentlyContinue
    if ($cmd) { $gradle = $cmd.Source }
}
if (-not $gradle) {
    $dist = Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists" -Directory -Filter "gradle-8.*" -ErrorAction SilentlyContinue |
            ForEach-Object { Get-ChildItem $_.FullName -Recurse -Filter "gradle.bat" -ErrorAction SilentlyContinue } |
            Select-Object -First 1
    if ($dist) { $gradle = $dist.FullName }
}
if (-not $gradle) {
    Write-Host "[提示] 没有找到 Gradle。用 Android Studio 打开 android/ 目录，它会自动下载并同步；或者手动安装 Gradle 8.11.1。" -ForegroundColor Yellow
    exit 1
}
Write-Step "Gradle = $gradle"

# ---------- 4. 编译 ----------
$tasks = @()
if ($Clean) { $tasks += "clean" }
$tasks += if ($Release) { "assembleRelease" } else { "assembleDebug" }

Push-Location $proj
try {
    & $gradle --no-daemon "-Dorg.gradle.jvmargs=-Xmx2048m" @tasks
    if ($LASTEXITCODE -ne 0) { Fail "Gradle 编译失败（exit $LASTEXITCODE）。" }
} finally {
    Pop-Location
}

# ---------- 5. 结果 ----------
$apkDir = Join-Path $proj ("app\build\outputs\apk\" + ($(if ($Release) { "release" } else { "debug" })))
Get-ChildItem $apkDir -Filter *.apk -ErrorAction SilentlyContinue | ForEach-Object {
    Write-Host ""
    Write-Host "APK: $($_.FullName)  ($([math]::Round($_.Length/1MB,2)) MB)" -ForegroundColor Green
}
