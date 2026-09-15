package com.FucVivo.ui.screen.apphook

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.FucVivo.R
import com.FucVivo.xposed.HookEntry
import com.FucVivo.xposed.XposedState
import com.FucVivo.xposed.hooks.systemui.BlurStyleController
import com.FucVivo.xposed.hooks.systemui.BlurStyleController.BlurScene
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 系统界面管理页附加卡：模糊半径 / 不透明度按生效场景拆分为独立子项
 * （通知中心与控制中心（共用面板背景）/ 音量面板 / 锁屏 / 系统弹窗 / 原子岛 /
 * 其他表面），每个场景一张卡片、滑条 0-200%、默认 100%。
 *
 * 写入 32 分片 remote prefs（键 = 场景前缀 + 后缀，见 BlurStyleController），
 * SystemUI/Plugin 进程内 BlurStyleController 监听实时生效。
 * 拖动过程只更新本地状态，松手（onValueChangeFinished）才提交，避免 Binder 风暴。
 * 「恢复默认」移除全部场景键与旧版全局键（回退到 100%）。
 */

/** 展示顺序与各卡内容（hasRadius/hasAlpha 由场景定义决定） */
private data class SceneCardSpec(
    val scene: BlurScene,
    val titleRes: Int,
    val captionRes: Int,
)

private val SCENE_SPECS = listOf(
    SceneCardSpec(BlurScene.SHADE, R.string.blur_scene_shade, R.string.blur_scene_shade_caption),
    SceneCardSpec(BlurScene.VOLUME, R.string.blur_scene_volume, R.string.blur_scene_volume_caption),
    SceneCardSpec(BlurScene.KEYGUARD, R.string.blur_scene_keyguard, R.string.blur_scene_keyguard_caption),
    SceneCardSpec(BlurScene.POPUP, R.string.blur_scene_popup, R.string.blur_scene_popup_caption),
    SceneCardSpec(BlurScene.ISLAND, R.string.blur_scene_island, R.string.blur_scene_island_caption),
    SceneCardSpec(BlurScene.OTHER, R.string.blur_scene_other, R.string.blur_scene_other_caption),
)

/** 场景键 → 旧版全局键 → 默认值（与 hook 侧 BlurStyleController.resolve 同语义） */
private fun readEffective(prefs: SharedPreferences, key: String, legacyKey: String): Int =
    if (prefs.contains(key)) prefs.getInt(key, BlurStyleController.DEFAULT_SCALE)
    else if (prefs.contains(legacyKey)) prefs.getInt(legacyKey, BlurStyleController.DEFAULT_SCALE)
    else BlurStyleController.DEFAULT_SCALE

@Composable
fun BlurCustomControlCard() {
    val context = LocalContext.current

    // remote prefs（LSPosed 分片门面）优先，缺失时读本机影子副本
    val prefs = remember {
        XposedState.hookPreferences()
            ?: context.getSharedPreferences(HookEntry.HOOK_PREFS_NAME, Context.MODE_PRIVATE)
    }
    val range = BlurStyleController.MIN_SCALE.toFloat()..BlurStyleController.MAX_SCALE.toFloat()

    // key=prefs 键，value=当前滑条值；「恢复默认」统一改这里
    val values = remember {
        mutableStateMapOf<String, Float>().apply {
            for (spec in SCENE_SPECS) {
                if (spec.scene.hasRadius) {
                    put(
                        BlurStyleController.radiusKey(spec.scene),
                        readEffective(prefs, BlurStyleController.radiusKey(spec.scene), BlurStyleController.KEY_RADIUS_SCALE).toFloat(),
                    )
                }
                if (spec.scene.hasAlpha) {
                    put(
                        BlurStyleController.alphaKey(spec.scene),
                        readEffective(prefs, BlurStyleController.alphaKey(spec.scene), BlurStyleController.KEY_ALPHA_SCALE).toFloat(),
                    )
                }
            }
        }
    }

    for (spec in SCENE_SPECS) {
        Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
            Text(
                text = stringResource(spec.titleRes),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp),
            )
            Text(
                text = stringResource(spec.captionRes),
                fontSize = 13.sp,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp),
            )
            if (spec.scene.hasRadius) {
                val key = BlurStyleController.radiusKey(spec.scene)
                SceneSlider(
                    titleRes = R.string.blur_radius_title,
                    summaryRes = R.string.blur_radius_summary,
                    key = key,
                    values = values,
                    range = range,
                )
            }
            if (spec.scene.hasAlpha) {
                val key = BlurStyleController.alphaKey(spec.scene)
                SceneSlider(
                    titleRes = R.string.blur_alpha_title,
                    summaryRes = R.string.blur_alpha_summary,
                    key = key,
                    values = values,
                    range = range,
                )
            }
        }
    }

    Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
        TextButton(
            text = stringResource(R.string.blur_reset),
            onClick = {
                for (key in values.keys) values[key] = BlurStyleController.DEFAULT_SCALE.toFloat()
                removeAll(context, values.keys.toList())
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Text(
            text = stringResource(R.string.blur_hint),
            fontSize = 13.sp,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp),
        )
    }
}

@Composable
private fun SceneSlider(
    titleRes: Int,
    summaryRes: Int,
    key: String,
    values: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Float>,
    range: ClosedFloatingPointRange<Float>,
) {
    val context = LocalContext.current
    // 直接以共享 map 为状态源：拖动即时刷新显示，「恢复默认」改 map 即回弹
    val value = values[key] ?: BlurStyleController.DEFAULT_SCALE.toFloat()
    SliderPreference(
        title = stringResource(titleRes),
        summary = stringResource(summaryRes, value.roundToInt()),
        value = value,
        onValueChange = { values[key] = it },
        onValueChangeFinished = {
            writeScale(context, key, (values[key] ?: BlurStyleController.DEFAULT_SCALE.toFloat()).roundToInt())
        },
        valueRange = range,
    )
}

private fun writeScale(context: Context, key: String, value: Int) {
    val local = context.getSharedPreferences(HookEntry.HOOK_PREFS_NAME, Context.MODE_PRIVATE)
    local.edit().putInt(key, value).apply()
    // 必须经 service editor 写入：LSPosed remote prefs 存于 daemon 数据库并实时推送
    // hook 进程（32 分片路由由 XposedState.hookPreferences() 门面处理）
    val remote = XposedState.hookPreferences()
    if (remote == null) {
        Toast.makeText(context, context.getString(R.string.scope_service_unavailable), Toast.LENGTH_SHORT).show()
    } else {
        remote.edit().putInt(key, value).commit()
    }
}

/** 恢复默认：移除全部场景键与旧版全局键，回退到 100% */
private fun removeAll(context: Context, keys: List<String>) {
    val allKeys = keys + BlurStyleController.KEY_RADIUS_SCALE + BlurStyleController.KEY_ALPHA_SCALE
    val local = context.getSharedPreferences(HookEntry.HOOK_PREFS_NAME, Context.MODE_PRIVATE)
    val localEditor = local.edit()
    for (key in allKeys) localEditor.remove(key)
    localEditor.apply()
    val remote = XposedState.hookPreferences()
    if (remote == null) {
        Toast.makeText(context, context.getString(R.string.scope_service_unavailable), Toast.LENGTH_SHORT).show()
    } else {
        val editor = remote.edit()
        for (key in allKeys) editor.remove(key)
        editor.commit()
    }
}
