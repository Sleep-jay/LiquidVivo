plugins {
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.compose.compiler) apply false
}

extra["androidMinSdkVersion"] = 28
extra["androidTargetSdkVersion"] = 37
extra["androidCompileSdkVersion"] = 37
extra["androidCompileSdkVersionMinor"] = 0
extra["androidBuildToolsVersion"] = "37.0.0"
extra["androidCompileNdkVersion"] = libs.versions.ndk.get()
extra["androidSourceCompatibility"] = JavaVersion.VERSION_17
extra["androidTargetCompatibility"] = JavaVersion.VERSION_17
// 版本号统一在 version.properties 维护（规则见该文件注释）
val versionProps = java.util.Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
extra["managerVersionCode"] = versionProps.getProperty("versionCode").toInt()
extra["managerVersionName"] = versionProps.getProperty("versionName")
