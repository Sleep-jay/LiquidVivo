package com.FucVivo.ui.util

import android.content.Context
import android.os.Build
import android.os.Process
import com.FucVivo.BuildConfig
import com.FucVivo.xposed.HookEntry
import com.FucVivo.xposed.XposedState
import com.FucVivo.xposed.XposedFrameworkState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 非 root 环境 stub：模块模板无 root 探测。 */
fun rootAvailable(): Boolean = false

fun getSuperuserCount(): Int = 0

/**
 * 生成模块日志报告（纯文本）：
 * 设备/系统信息 + 框架绑定状态 + 作用域 + 本进程 logcat。
 */
fun getBugreportFile(context: Context): File {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    val sb = StringBuilder()
    sb.appendLine("=== FucVivo 日志报告 ===")
    sb.appendLine("生成时间: ${fmt.format(Date())}")
    sb.appendLine("模块版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    sb.appendLine()
    sb.appendLine("=== 设备信息 ===")
    sb.appendLine("型号: ${Build.MANUFACTURER} ${Build.MODEL}")
    sb.appendLine("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    sb.appendLine("固件: ${Build.DISPLAY}")
    sb.appendLine()
    sb.appendLine("=== 框架状态 ===")
    when (val st = XposedState.frameworkState) {
        is XposedFrameworkState.Active -> {
            sb.appendLine("状态: 已激活")
            sb.appendLine("框架: ${st.frameworkName} ${st.frameworkVersion} (${st.frameworkVersionCode})")
            sb.appendLine("API: ${st.apiVersion}")
            sb.appendLine("作用域: ${st.scope.joinToString(", ").ifEmpty { "(空)" }}")
        }
        is XposedFrameworkState.Error -> sb.appendLine("状态: 错误")
        is XposedFrameworkState.Inactive -> sb.appendLine("状态: 未连接（框架未绑定服务）")
    }
    sb.appendLine(
        "调试日志: " + if (XposedState.remotePreferences(HookEntry.PREFS_NAME)
                ?.getString("logging_mode", null) == "debug"
        ) "开启" else "关闭"
    )
    runCatching {
        val targets = XposedState.runningTargets()
        sb.appendLine("本模块 versionCode: ${BuildConfig.VERSION_CODE}")
        sb.appendLine(
            "运行中目标: " + targets.joinToString(", ") { t ->
                "${t.processName}(pid=${t.pid}, state=${t.state}, loadedVersionCode=${t.loadedVersionCode})"
            }.ifEmpty { "(无)" }
        )
    }
    sb.appendLine()
    sb.appendLine("=== 本进程日志 (logcat) ===")
    runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime", "--pid=${Process.myPid()}"))
        p.inputStream.bufferedReader().use { r ->
            var n = 0
            r.forEachLine { line ->
                if (n++ < 2000) sb.appendLine(line)
            }
        }
        p.destroy()
    }.onFailure { sb.appendLine("logcat 读取失败: $it") }

    val out = File(context.cacheDir, "fucvivo_log.txt")
    out.writeText(sb.toString())
    return out
}
