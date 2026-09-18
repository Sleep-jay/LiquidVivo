package com.LiquidVivo.xposed

import com.LiquidVivo.xposed.hooks.systemui.BlurStyleController
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 用 root 仅重启系统界面进程。
 *
 * 红线：
 * - 只允许崩溃 [BlurStyleController.PKG_SYSTEMUI] 与 [BlurStyleController.PKG_SYSTEMUI_PLUGIN]
 * - 参数全部是编译期常量 argv，不拼接用户输入、不走 shell 字符串拼接
 * - 禁止 reboot / zygote / system_server / ctl.restart / stop / rm / uninstall / remount
 * - 失败不得升级为重启手机
 */
internal object SystemUiRootRestarter {

    enum class Outcome {
        RESTARTED,
        SU_MISSING,
        SU_DENIED,
    }

    private val SU_CANDIDATES = arrayOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/debug_ramdisk/su",
        "/debug_ramdisk/bin/su",
    )

    private const val CRASH_SYSTEMUI = "/system/bin/am crash com.android.systemui"
    private const val CRASH_PLUGIN = "/system/bin/am crash com.vivo.systemuiplugin"

    fun restartAllowlistedUi(): Outcome {
        val su = locateSu() ?: return Outcome.SU_MISSING
        var anyOk = false
        for (pkg in ALLOWED_PACKAGES) {
            if (crashPackage(su, pkg)) anyOk = true
        }
        return if (anyOk) Outcome.RESTARTED else Outcome.SU_DENIED
    }

    private val ALLOWED_PACKAGES = arrayOf(
        BlurStyleController.PKG_SYSTEMUI,
        BlurStyleController.PKG_SYSTEMUI_PLUGIN,
    )

    private fun isAllowed(pkg: String): Boolean =
        pkg == BlurStyleController.PKG_SYSTEMUI ||
            pkg == BlurStyleController.PKG_SYSTEMUI_PLUGIN

    private fun locateSu(): String? {
        for (path in SU_CANDIDATES) {
            val file = File(path)
            if (file.exists()) return path
        }
        return null
    }

    private fun crashPackage(su: String, pkg: String): Boolean {
        if (!isAllowed(pkg)) return false
        val dashC = hardcodedCrashCommand(pkg) ?: return false
        // 只走 -c + 编译期常量，避免 su 0 掉进交互 shell。
        if (runArgv(arrayOf(su, "-c", dashC))) return true
        return runArgv(arrayOf(su, "0", "-c", dashC))
    }

    private fun hardcodedCrashCommand(pkg: String): String? = when (pkg) {
        BlurStyleController.PKG_SYSTEMUI -> CRASH_SYSTEMUI
        BlurStyleController.PKG_SYSTEMUI_PLUGIN -> CRASH_PLUGIN
        else -> null
    }

    private fun runArgv(argv: Array<String>): Boolean {
        if (argv.isEmpty() || argv.any { it.isEmpty() }) return false
        val process = runCatching {
            ProcessBuilder(*argv).redirectErrorStream(true).start()
        }.getOrNull() ?: return false
        return try {
            // 关掉 stdin，避免 su 掉进交互 root shell。
            runCatching { process.outputStream.close() }
            drainQuietly(process)
            val finished = process.waitFor(12, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
                false
            } else {
                process.exitValue() == 0
            }
        } catch (_: Throwable) {
            runCatching { process.destroyForcibly() }
            false
        }
    }

    private fun drainQuietly(process: Process) {
        Thread({
            runCatching {
                process.inputStream.use { stream ->
                    val buf = ByteArray(256)
                    while (stream.read(buf) >= 0) {
                        // discard
                    }
                }
            }
        }, "fv-su-drain").apply {
            isDaemon = true
            start()
        }
    }
}
