// 根构建脚本：本机 Gradle 缓存里只有 AGP 8.6.1 / Kotlin 2.1.21，版本必须完全一致。
plugins {
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
}
