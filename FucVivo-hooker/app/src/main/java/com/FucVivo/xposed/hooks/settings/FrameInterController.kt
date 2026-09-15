package com.FucVivo.xposed.hooks.settings

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import com.FucVivo.xposed.HookEntry
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 游戏插帧控制器（运行于 com.android.settings 进程 = system uid）。
 *
 * 机制（逆向自 GameCube 13.0.05.01，详见 rom-analysis/FRAMEINTERPOLATION-DEEPDIVE.md）：
 * 插帧执行引擎在 ROM 框架/vendor 层（Iris 独显芯片 MEMC），应用层只需写
 * Settings.System.gamecube_frame_interpolation = "ison:gameIndex:pid:depressFps:targetFps"。
 * 本控制器监听模块 remote prefs，代用户写该键，完全绕开 GameCube 的机型白名单/
 * Monster+ 依赖/温控门控（系统自身的 Monster+/温控调度足够激进，不再复刻）。
 *
 * remote prefs（HOOK_PREFS_NAME）键：
 *  - enable_frameinter  功能开关（默认 false）
 *  - fi_preset          档位 "off" | "<depress>:<target>"，如 "45:90"
 */
object FrameInterController {

    private const val TAG = "FucVivoFrameInter"
    const val KEY_ENABLE = "enable_frameinter"
    const val KEY_PRESET = "fi_preset"
    const val PRESET_OFF = "off"

    /** GameCube IrisUtils 写入的同名 Settings.System 键 */
    const val SYSTEM_KEY = "gamecube_frame_interpolation"

    /** 关闭命令，与 IrisUtils.c() 硬编码值一致 */
    const val VALUE_CLOSE = "0:-1:0:0:0"

    /** Monster+ 状态键（i9/l.n() 同款读取）；app 可读不可写，经本控制器代写 */
    const val MONSTER_KEY = "game_plus_mode_key"

    /** remote prefs 命令键："<timestamp>:<0|1>"，带时间戳防重复值不触发 listener */
    const val KEY_MONSTER_CMD = "fi_monster_cmd"

    /** 合法目标帧：面板可切换刷新率 */
    private val TARGETS = setOf(60, 90, 120, 144)

    /** listener 可能被弱引用，强引用保活 */
    private val listeners = CopyOnWriteArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

    fun install(module: HookEntry) {
        val prefs: SharedPreferences = module.hookedPrefs
        apply(module, prefs)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            when {
                key == KEY_PRESET && p.getBoolean(KEY_ENABLE, false) -> apply(module, p)
                key == KEY_ENABLE && !p.getBoolean(KEY_ENABLE, false) ->
                    // 关闭功能 → 回写关闭命令，恢复原状
                    runCatching {
                        val ctx = appContext() ?: return@runCatching
                        Settings.System.putString(ctx.contentResolver, SYSTEM_KEY, VALUE_CLOSE)
                        module.logD(TAG, "feature off, wrote close")
                    }.onFailure { module.logE(TAG, "close failed: $it") }
                key == KEY_ENABLE -> apply(module, p)
                key == KEY_MONSTER_CMD -> applyMonster(module, p)
            }
        }
        listeners += listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
        module.logD(TAG, "controller installed")
    }

    /** 代写 Monster+ 状态（system uid）；命令格式 "<ts>:<0|1>" */
    private fun applyMonster(module: HookEntry, prefs: SharedPreferences) {
        runCatching {
            val cmd = prefs.getString(KEY_MONSTER_CMD, null) ?: return
            val value = cmd.substringAfterLast(':').toIntOrNull() ?: return
            val ctx = appContext() ?: return
            Settings.System.putInt(ctx.contentResolver, MONSTER_KEY, value)
            module.logD(TAG, "monster+ -> $value")
        }.onFailure { module.logE(TAG, "monster apply failed: $it") }
    }

    /** 组合键值；非法返回 null */
    fun buildValue(preset: String?): String? {
        if (preset.isNullOrBlank() || preset == PRESET_OFF) return VALUE_CLOSE
        val parts = preset.split(":")
        if (parts.size != 2) return null
        var depress = parts[0].toIntOrNull() ?: return null
        val target = parts[1].toIntOrNull() ?: return null
        if (target !in TARGETS) return null
        // IrisUtils.b 同款钳位：降帧值 > 目标帧 → 改成 target/2
        if (depress > target) depress = target / 2
        if (depress > 0) {
            if (target / depress > 3) return null // 极限 3 倍插帧
        }
        // ison=1, gameIndex=-1(默认调校), pid=0(全局)
        return "1:-1:0:$depress:$target"
    }

    private fun apply(module: HookEntry, prefs: SharedPreferences) {
        runCatching {
            if (!prefs.getBoolean(KEY_ENABLE, false)) return
            val value = buildValue(prefs.getString(KEY_PRESET, PRESET_OFF)) ?: return
            val ctx = appContext() ?: return
            Settings.System.putString(ctx.contentResolver, SYSTEM_KEY, value)
            module.logD(TAG, "applied $value")
        }.onFailure { module.logE(TAG, "apply failed: $it") }
    }

    /** system_server/Settings 进程内取 Application Context */
    internal fun appContext(): Context? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Context
    }.getOrNull()
}
