import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 发布签名配置从 android/keystore.properties 读取（该文件与 jks 都不入库）。
// 文件不存在时 release 会自动退回 debug 签名，保证 clone 下来也能直接 assembleRelease，
// 只是那种包不能覆盖安装你正式的 release 包。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystorePropsFile.exists() &&
        !keystoreProps.getProperty("storeFile", "").isNullOrBlank()

android {
    namespace = "com.ourspace.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.ourspace.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                // keystore.properties 里的路径相对于 android/ 目录
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // 不开启 v1 签名：minSdk 26 足够走 v2/v3
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // 两个人用的私有 App，不做混淆：包本来就只有 1MB，
            // 开了 R8 反而可能因为反射/自定义 View 出现难查的问题。
            isMinifyEnabled = false
            isShrinkResources = false
            // 调试用开关：tools\_ci-build.cmd assembleRelease -PdebuggableRelease
            // 这样出来的包仍用正式签名（可覆盖安装、本地数据不丢）但是 debuggable，
            // 就能用 adb run-as 把本地数据库捞出来排查问题。正常发版不要带这个参数。
            isDebuggable = project.hasProperty("debuggableRelease")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 不生成 BuildConfig 以外的多余产物；不使用 ViewBinding / DataBinding。
    buildFeatures {
        buildConfig = true
        viewBinding = false
        dataBinding = false
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "DebugProbesKt.bin")
    }

    lint {
        // 离线环境无法下载 lint 依赖时也不应阻断构建。
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 唯一允许的依赖：Kotlin 标准库（由 Kotlin 插件自动加入）。
    // 严禁添加 androidx / material / okhttp / gson / coroutines 等第三方库。
}
