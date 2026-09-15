package com.FucVivo.xposed

import android.content.SharedPreferences
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Xposed 模块入口：在目标应用进程内由框架实例化。
 * 类名必须与 META-INF/xposed/java_init.list 中的声明完全一致。
 */
class HookEntry : XposedModule() {

    companion object {
        const val PREFS_NAME = "settings"

        /** hook 功能开关 prefs（app 侧写、hook 侧经 getRemotePreferences 读） */
        const val HOOK_PREFS_NAME = "fucvivo_hooks"
        private const val KEY_LOGGING = "logging_mode"

        @Volatile
        var debugLoggingEnabled: Boolean = false
            private set

        /** SharedPreferences 对 listener 可能是弱引用，强引用保活 */
        private val loggingListeners = CopyOnWriteArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()
    }

    /**
     * hook 开关配置门面：32 分片 RemotePreferences（见 [ShardedSharedPreferences]）。
     * 进程内单例，所有 hooker 共用。
     */
    val hookedPrefs: SharedPreferences by lazy {
        ShardedSharedPreferences { name -> runCatching { getRemotePreferences(name) }.getOrNull() }
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        // 每个被注入进程：读初始值 + 装监听，开关实时生效
        runCatching {
            debugLoggingEnabled = readDebugLoggingEnabled()
            installLoggingListener()
        }
        if (!param.isFirstPackage) return
        HookerRegistry.find(param.packageName)?.onPackageLoaded(this, param)
    }

    /** 调试日志（info 级）：受日志总开关门控；错误日志不受限 */
    fun logD(tag: String, msg: String) {
        if (debugLoggingEnabled) log(4, tag, msg)
    }

    fun logE(tag: String, msg: String) {
        log(6, tag, msg)
    }

    /** 监听 remote prefs 的 logging_mode，开关实时生效 */
    private fun installLoggingListener() {
        val prefs = getRemotePreferences(PREFS_NAME) ?: return
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == KEY_LOGGING) {
                debugLoggingEnabled =
                    runCatching { p.getString(KEY_LOGGING, null) }.getOrNull() == "debug"
            }
        }
        loggingListeners += listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        HookerRegistry.find(param.packageName)?.onPackageReady(this, param)
    }

    private fun readDebugLoggingEnabled(): Boolean = try {
        getRemotePreferences(PREFS_NAME).getString(KEY_LOGGING, null) == "debug"
    } catch (t: Throwable) {
        false
    }
}
