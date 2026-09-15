package com.FucVivo.ui.screen.apphook

import android.content.Context
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.FucVivo.R
import com.FucVivo.xposed.HookEntry
import com.FucVivo.xposed.XposedState
import com.FucVivo.xposed.hooks.settings.FrameInterController
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/** 插帧档位（depress:target），与 GameCube 配置/社区验证的合法组合一致 */
private val PRESETS = listOf(
    FrameInterController.PRESET_OFF,
    "30:60", "30:90", "40:120", "45:90", "48:144", "60:120", "72:144",
)

private fun presetLabel(value: String, offLabel: String): String =
    if (value == FrameInterController.PRESET_OFF) offLabel
    else value.split(":").let { p -> "${p[0]} → ${p[1]}" }

/**
 * 设置（system uid）管理页附加卡：插帧档位选择 + Monster+ 开关。
 * 写入 remote prefs，由 Settings 进程内 FrameInterController 实时落键。
 */
@Composable
fun FrameInterControlCard() {
    val context = LocalContext.current
    Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Text(
            text = stringResource(R.string.frameinter_card_title),
            fontSize = 14.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            color = colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp),
        )
        val local = context.getSharedPreferences(HookEntry.HOOK_PREFS_NAME, Context.MODE_PRIVATE)
        var current by remember {
            mutableStateOf(
                XposedState.hookPreferences()
                    ?.getString(FrameInterController.KEY_PRESET, null)
                    ?: local.getString(FrameInterController.KEY_PRESET, FrameInterController.PRESET_OFF)
                    ?: FrameInterController.PRESET_OFF
            )
        }
        val offLabel = stringResource(R.string.frameinter_off)
        val index = PRESETS.indexOfFirst { it == current }.coerceAtLeast(0)

        // Monster+ 模式：状态读真实系统键（任意应用可读），写经 Settings 进程代写
        var monster by remember {
            mutableStateOf(
                Settings.System.getInt(
                    context.contentResolver, FrameInterController.MONSTER_KEY, 0
                ) == 1
            )
        }
        SwitchPreference(
            title = stringResource(R.string.frameinter_monster_title),
            summary = stringResource(R.string.frameinter_monster_summary),
            checked = monster,
            onCheckedChange = { enable ->
                monster = enable
                val cmd = "${System.currentTimeMillis()}:${if (enable) 1 else 0}"
                local.edit().putString(FrameInterController.KEY_MONSTER_CMD, cmd).apply()
                val remote = XposedState.hookPreferences()
                if (remote == null) {
                    Toast.makeText(context, R.string.scope_service_unavailable, Toast.LENGTH_SHORT).show()
                } else {
                    remote.edit().putString(FrameInterController.KEY_MONSTER_CMD, cmd).commit()
                }
            },
        )

        OverlayDropdownPreference(
            title = stringResource(R.string.frameinter_preset_title),
            items = PRESETS.map { presetLabel(it, offLabel) },
            selectedIndex = index,
            onSelectedIndexChange = { i ->
                current = PRESETS[i]
                local.edit().putString(FrameInterController.KEY_PRESET, current).apply()
                val remote = XposedState.hookPreferences()
                if (remote == null) {
                    Toast.makeText(context, R.string.scope_service_unavailable, Toast.LENGTH_SHORT).show()
                } else {
                    remote.edit().putString(FrameInterController.KEY_PRESET, current).commit()
                }
            },
        )
        Text(
            text = stringResource(R.string.frameinter_hint),
            fontSize = 13.sp,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}
