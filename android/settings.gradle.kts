// 顶层 settings：只保留 Google / Maven Central 仓库，不使用任何第三方依赖。
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 禁止各模块自己声明仓库，统一在这里管理，保证离线行为可预测。
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "OurSpace"
include(":app")
