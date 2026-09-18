package com.LiquidVivo.ui.util

import android.content.Context
import android.os.Build
import android.os.Process
import com.LiquidVivo.BuildConfig
import com.LiquidVivo.xposed.HookEntry
import com.LiquidVivo.xposed.XposedState
import com.LiquidVivo.xposed.XposedFrameworkState
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 非 root 环境 stub：模块模板无 root 探测。 */
fun rootAvailable(): Boolean = false

fun getSuperuserCount(): Int = 0

/**
 * Generates a self-contained diagnostic report for sharing. The report keeps
 * framework state, running target metadata, non-sensitive settings, and every
 * readable module log line together so a failure can be reconstructed later.
 */
fun getBugreportFile(context: Context): File {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    val sb = StringBuilder()
    sb.appendLine("=== LiquidVivo Diagnostic Report ===")
    sb.appendLine("生成时间: ${fmt.format(Date())}")
    sb.appendLine("报告格式: 2")
    sb.appendLine("应用包名: ${BuildConfig.APPLICATION_ID}")
    sb.appendLine("模块版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    sb.appendLine("构建时间: ${BuildConfig.BUILD_TIME}")
    sb.appendLine()
    sb.appendLine("=== 设备信息 ===")
    sb.appendLine("型号: ${Build.MANUFACTURER} ${Build.MODEL}")
    sb.appendLine("设备代号: ${Build.DEVICE}")
    sb.appendLine("产品: ${Build.PRODUCT}")
    sb.appendLine("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    sb.appendLine("固件: ${Build.DISPLAY}")
    sb.appendLine("指纹: ${Build.FINGERPRINT}")
    sb.appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
    sb.appendLine()
    sb.appendLine("=== 框架状态 ===")
    when (val st = XposedState.frameworkState) {
        is XposedFrameworkState.Active -> {
            sb.appendLine("状态: 已激活")
            sb.appendLine("框架: ${st.frameworkName} ${st.frameworkVersion} (${st.frameworkVersionCode})")
            sb.appendLine("API: ${st.apiVersion}")
            sb.appendLine("作用域: ${st.scope.joinToString(", ").ifEmpty { "(空)" }}")
        }
        is XposedFrameworkState.Error -> sb.appendLine("状态: 读取框架信息时出错")
        is XposedFrameworkState.Inactive -> sb.appendLine("状态: 未连接（框架未绑定服务）")
    }
    sb.appendLine(
        "调试日志: " + if (XposedState.remotePreferences(HookEntry.PREFS_NAME)
                ?.getString("logging_mode", null) == "debug"
        ) "开启" else "关闭"
    )
    sb.appendLine()
    sb.appendLine("=== 注入目标 ===")
    runCatching {
        val targets = XposedState.runningTargets()
        sb.appendLine("本模块 versionCode: ${BuildConfig.VERSION_CODE}")
        if (targets.isEmpty()) sb.appendLine("运行中目标: (无)")
        else targets.forEachIndexed { index, target ->
            sb.appendLine("[$index] process=${target.processName}")
            sb.appendLine("    pid=${target.pid}, state=${target.state}, loadedVersionCode=${target.loadedVersionCode}")
        }
    }.onFailure { sb.appendLine("运行中目标读取失败: ${it.javaClass.simpleName}: ${it.message}") }

    sb.appendLine()
    sb.appendLine("=== 模块配置 ===")
    appendSettingsSnapshot(sb)
    sb.appendLine()
    sb.appendLine("=== 模块日志 (logcat) ===")
    sb.appendLine("说明: 优先按模块标签收集；系统限制读取跨进程日志时，报告会保留本进程日志和失败原因。")
    appendModuleLogcat(sb)

    val out = File(context.cacheDir, "liquidvivo_diagnostic.txt")
    out.writeText(sb.toString())
    return out
}

private fun appendSettingsSnapshot(sb: StringBuilder) {
    val prefs = runCatching { XposedState.remotePreferences(HookEntry.PREFS_NAME) }.getOrNull()
    if (prefs == null) {
        sb.appendLine("设置读取: 框架服务不可用")
        return
    }

    val safeKeys = listOf(
        "logging_mode",
        "blur_enabled",
        "liquid_glass_enabled",
        "blur_radius",
        "blur_alpha",
        "glass_opacity",
    )
    val values = runCatching { prefs.all }.getOrElse {
        sb.appendLine("设置读取失败: ${it.javaClass.simpleName}: ${it.message}")
        return
    }
    val matching = values.entries.filter { (key, _) ->
        key in safeKeys || key.startsWith("blur_") || key.startsWith("liquid_")
    }.sortedBy { it.key }
    if (matching.isEmpty()) sb.appendLine("可导出设置: (无)")
    else matching.forEach { (key, value) -> sb.appendLine("$key=$value") }
}

private fun appendModuleLogcat(sb: StringBuilder) {
    val allLogs = runLogcat(arrayOf("logcat", "-d", "-v", "threadtime"), 4_000)
    if (allLogs.error != null) {
        sb.appendLine("模块标签日志读取失败: ${allLogs.error}")
    } else {
        val moduleLines = allLogs.lines.filter { line ->
            line.contains("LiquidVivo", ignoreCase = true) ||
                line.contains("LiquidVivo", ignoreCase = true)
        }
        sb.appendLine("模块标签日志: ${moduleLines.size} 行（logcat 总输出 ${allLogs.lines.size} 行）")
        moduleLines.forEach(sb::appendLine)
    }

    val appLogs = runLogcat(
        arrayOf("logcat", "-d", "-v", "threadtime", "--pid=${Process.myPid()}"),
        2_000,
    )
    sb.appendLine()
    sb.appendLine("=== 管理器进程日志 (pid=${Process.myPid()}) ===")
    if (appLogs.error != null) sb.appendLine("本进程日志读取失败: ${appLogs.error}")
    else {
        sb.appendLine("本进程日志: ${appLogs.lines.size} 行")
        appLogs.lines.forEach(sb::appendLine)
    }
}

private data class LogcatResult(val lines: List<String>, val error: String?)

private fun runLogcat(command: Array<String>, maxLines: Int): LogcatResult = runCatching {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    val lines = ArrayList<String>(maxLines)
    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
        while (lines.size < maxLines) {
            val line = reader.readLine() ?: break
            lines += line
        }
    }
    if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroy()
    val exitCode = if (process.isAlive) null else process.exitValue()
    LogcatResult(lines, if (exitCode == null || exitCode == 0) null else "exit=$exitCode")
}.getOrElse { LogcatResult(emptyList(), "${it.javaClass.simpleName}: ${it.message}") }
