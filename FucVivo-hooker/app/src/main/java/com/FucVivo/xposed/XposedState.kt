package com.FucVivo.xposed

import android.content.SharedPreferences
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet

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

/**
 * libxposed-service 连接管理：状态 + 作用域操作。
 * 在 FucVivoApplication.onCreate 中 registerListener。
 */
object XposedState : XposedServiceHelper.OnServiceListener {

    @Volatile
    private var xposedService: XposedService? = null

    @Volatile
    var frameworkState: XposedFrameworkState = XposedFrameworkState.Inactive
        private set

    private val listeners = CopyOnWriteArraySet<FrameworkStateListener>()

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

    fun refresh() {
        val service = xposedService
        updateState(if (service == null) XposedFrameworkState.Inactive else readState(service))
    }

    fun requestScope(packageName: String, onError: (String) -> Unit = {}) =
        requestScope(listOf(packageName), onError)

    /** 多包一次申请（合并管理的 hooker，如 SystemUI + SystemUIPlugin） */
    fun requestScope(packages: List<String>, onError: (String) -> Unit = {}) {
        val service = xposedService ?: return
        service.requestScope(
            packages,
            object : XposedService.OnScopeEventListener {
                override fun onScopeRequestFailed(message: String) = onError(message)
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
}
