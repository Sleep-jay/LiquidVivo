package com.LiquidVivo.xposed

import android.app.ActivityManager
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.LiquidVivo.fvApp
import com.LiquidVivo.xposed.hooks.systemui.BlurStyleController
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit

/** 框架绑定状态（working / disconnected / error 三态，对照 Rain） */
sealed class XposedFrameworkState {
    data object Inactive : XposedFrameworkState()
    data object Error : XposedFrameworkState()
    data class Active(
        val frameworkName: String,
        val frameworkVersion: String,
        val frameworkVersionCode: Long,
        val apiVersion: Int,
        val scope: List<String>,
    ) : XposedFrameworkState()
}

fun interface FrameworkStateListener {
    fun onFrameworkStateChanged(state: XposedFrameworkState)
}

/** 重启 / 热重载系统界面的执行结果（供 UI 给出对应提示） */
enum class RestartSystemUiResult {
    /** 已发起框架热重载：模块代码在 SystemUI 进程内重新加载，状态栏不闪 */
    HOT_RELOADED,

    /** 热重载不可用，已回退下发重启指令：SystemUI 进程内模块自杀、自动拉起（闪 1~2 秒） */
    FALLBACK_SENT,

    /** 模块当时未注入，已从管理器侧 crash 系统界面并等到重新注入 */
    PROCESS_CRASHED,

    /** 已通过 root 仅崩溃系统界面 / 插件进程，由 system_server 自动拉起 */
    ROOT_RESTARTED,

    /** 作用域已写回，但未能从本应用拉起 SystemUI（才需要重启手机） */
    MODULE_NOT_INJECTED,

    /** LSPosed 框架服务未连接（需在 LSPosed 中启用模块并给作用域） */
    FRAMEWORK_UNAVAILABLE,

    /** 设备上找不到可用的 su，未执行任何 root 命令 */
    ROOT_UNAVAILABLE,

    /** su 存在但授权被拒或命令失败，未执行危险操作 */
    ROOT_DENIED,
}

/**
 * libxposed-service 连接管理：状态 + 作用域操作。
 * 在 LiquidVivoApplication.onCreate 中 registerListener。
 */
object XposedState : XposedServiceHelper.OnServiceListener {

    @Volatile
    private var xposedService: XposedService? = null

    @Volatile
    var frameworkState: XposedFrameworkState = XposedFrameworkState.Inactive
        private set

    private val listeners = CopyOnWriteArraySet<FrameworkStateListener>()

    private val mainHandler = Handler(Looper.getMainLooper())

    private val systemUiPackages = listOf(
        BlurStyleController.PKG_SYSTEMUI,
        BlurStyleController.PKG_SYSTEMUI_PLUGIN,
    )

    fun init() {
        XposedServiceHelper.registerListener(this)
    }

    fun xposedServiceOrNull(): XposedService? = xposedService

    fun addListener(listener: FrameworkStateListener) {
        listeners += listener
    }

    fun removeListener(listener: FrameworkStateListener) {
        listeners -= listener
    }

    fun remotePreferences(name: String): SharedPreferences? =
        xposedService?.getRemotePreferences(name)

    @Volatile
    private var hookPrefsFacade: ShardedSharedPreferences? = null

    /**
     * hook 开关配置的写入门面（32 分片 RemotePreferences，见 [ShardedSharedPreferences]）。
     * 与 hook 侧 HookEntry.hookedPrefs 同一套路由规则；服务不可用时返回 null。
     */
    fun hookPreferences(): SharedPreferences? {
        if (xposedService == null) return null
        hookPrefsFacade?.let { return it }
        return ShardedSharedPreferences { name ->
            runCatching { remotePreferences(name) }.getOrNull()
        }.also { hookPrefsFacade = it }
    }

    fun pushLoggingMode(mode: String) {
        try {
            remotePreferences("settings")?.edit()?.putString("logging_mode", mode)?.apply()
        } catch (t: Throwable) {
        }
    }

/**
 * 重启系统界面：主路径用 root 仅 crash SystemUI / Plugin，不重启手机。
 * su 不可用时才回退到无 root 的 am crash，以及模块内动作键。
 * 失败不会升级成 reboot / zygote / system_server。
 */
    suspend fun requestRestartSystemUI(): RestartSystemUiResult = withContext(Dispatchers.IO) {
        when (val root = SystemUiRootRestarter.restartAllowlistedUi()) {
            SystemUiRootRestarter.Outcome.RESTARTED -> {
                if (awaitSystemUiInjected(timeoutMs = 8_000L) || xposedService == null) {
                    refresh()
                    return@withContext RestartSystemUiResult.ROOT_RESTARTED
                }
                return@withContext RestartSystemUiResult.MODULE_NOT_INJECTED
            }
            SystemUiRootRestarter.Outcome.SU_MISSING,
            SystemUiRootRestarter.Outcome.SU_DENIED -> {
                val service = xposedService
                    ?: return@withContext if (root == SystemUiRootRestarter.Outcome.SU_MISSING) {
                        RestartSystemUiResult.ROOT_UNAVAILABLE
                    } else {
                        RestartSystemUiResult.ROOT_DENIED
                    }
                val target = systemUiTarget(service)
                if (target != null) {
                    fallbackRestartByPrefs()
                    if (awaitSystemUiInjected(timeoutMs = 2_500L)) {
                        refresh()
                        return@withContext RestartSystemUiResult.FALLBACK_SENT
                    }
                }
                val prefsSent = fallbackRestartByPrefs()
                val crashed = crashSystemUiFromOutside()
                if (awaitSystemUiInjected(timeoutMs = 8_000L)) {
                    refresh()
                    return@withContext if (crashed) RestartSystemUiResult.PROCESS_CRASHED
                    else if (prefsSent) RestartSystemUiResult.FALLBACK_SENT
                    else RestartSystemUiResult.PROCESS_CRASHED
                }
                return@withContext if (root == SystemUiRootRestarter.Outcome.SU_MISSING) {
                    RestartSystemUiResult.ROOT_UNAVAILABLE
                } else {
                    RestartSystemUiResult.ROOT_DENIED
                }
            }
        }

    }

    fun isSystemUiScope(packages: Collection<String>): Boolean =
        packages.contains(BlurStyleController.PKG_SYSTEMUI)

    fun isSystemUiInjected(): Boolean = systemUiTarget(xposedService) != null

/** 回退方案：写动作键，由 SystemUI 进程内模块自杀重启（见 BlurStyleController） */
private fun fallbackRestartByPrefs(): Boolean {
    val prefs = hookPreferences() ?: return false
    return runCatching {
        prefs.edit()
            .putLong(HookEntry.ACTION_RESTART_SYSTEMUI, System.currentTimeMillis())
            .commit()
    }.getOrDefault(false)
}

    fun refresh() {
        val service = xposedService
        updateState(if (service == null) XposedFrameworkState.Inactive else readState(service))
    }

    fun requestScope(packageName: String, onError: (String) -> Unit = {}) =
        requestScope(listOf(packageName), onError = onError)

    /** 多包一次申请（合并管理的 hooker，如 SystemUI + SystemUIPlugin） */
    fun requestScope(
        packages: List<String>,
        onError: (String) -> Unit = {},
        onApproved: (List<String>) -> Unit = {},
    ) {
        val service = xposedService ?: return
        service.requestScope(
            packages,
            object : XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(approved: List<String>) {
                    postMain { onApproved(approved) }
                }
                override fun onScopeRequestFailed(message: String) {
                    postMain { onError(message) }
                }
            },
        )
    }

    fun removeScope(packageName: String) = removeScope(listOf(packageName))

    fun removeScope(packages: List<String>) {
        xposedService?.removeScope(packages)
    }

    fun runningTargets(): List<HookedTarget> = try {
        xposedService?.runningTargets.orEmpty()
    } catch (t: Throwable) {
        emptyList()
    }

    override fun onServiceBind(service: XposedService) {
        xposedService = service
        updateState(readState(service))
    }

    override fun onServiceDied(service: XposedService) {
        if (xposedService === service) {
            xposedService = null
            updateState(XposedFrameworkState.Inactive)
        }
    }

    private fun readState(service: XposedService): XposedFrameworkState = try {
        XposedFrameworkState.Active(
            frameworkName = service.frameworkName,
            frameworkVersion = service.frameworkVersion,
            frameworkVersionCode = service.frameworkVersionCode,
            apiVersion = service.apiVersion,
            scope = service.scope.orEmpty(),
        )
    } catch (t: Throwable) {
        XposedFrameworkState.Error
    }

    private fun updateState(state: XposedFrameworkState) {
        frameworkState = state
        listeners.forEach { it.onFrameworkStateChanged(state) }
    }

    private fun postMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    private fun systemUiTarget(service: XposedService?): HookedTarget? {
        if (service == null) return null
        return runCatching {
            service.getRunningTargets().firstOrNull {
                it.processName == BlurStyleController.PKG_SYSTEMUI
            }
        }.getOrNull()
    }

    private suspend fun awaitSystemUiInjected(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isSystemUiInjected()) return true
            delay(300)
        }
        return isSystemUiInjected()
    }

    /**
     * 无 root 回退：仅对本机 am crash 两个白名单包。
     * 不 force-stop，避免 SystemUI 被标成已停止后不再拉起。
     */
    private fun crashSystemUiFromOutside(): Boolean {
        var anyOk = false
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/app/")
        }
        val am = runCatching {
            fvApp.getSystemService(ActivityManager::class.java)
        }.getOrNull()
        for (pkg in systemUiPackages) {
            runCatching { am?.killBackgroundProcesses(pkg) }
            if (execQuiet(arrayOf("/system/bin/am", "crash", pkg))) anyOk = true
            if (execQuiet(arrayOf("/system/bin/cmd", "activity", "crash", pkg))) anyOk = true
        }
        return anyOk
    }

    private fun execQuiet(cmd: Array<String>): Boolean = runCatching {
        val process = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val finished = process.waitFor(3, TimeUnit.SECONDS)
        if (!finished) {
            process.destroy()
            false
        } else {
            process.exitValue() == 0
        }
    }.getOrDefault(false)
}
