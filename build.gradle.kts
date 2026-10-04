// 顶层构建脚本：只声明插件版本，不在这里加依赖
//
// 版本对齐说明（2026-10 环境）：
//   本机 Android Studio 自带 JDK 25、SDK platform android-37.0、build-tools 36.0.0，
//   必须用 Gradle 9.x + AGP 9.x（AGP 9 已内置 Kotlin 支持，不再单独应用 kotlin-android 插件）。
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
