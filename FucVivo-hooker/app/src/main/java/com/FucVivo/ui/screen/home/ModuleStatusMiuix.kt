package com.FucVivo.ui.screen.home

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.FucVivo.BuildConfig
import com.FucVivo.R
import com.FucVivo.xposed.FrameworkStateListener
import com.FucVivo.xposed.HookerRegistry
import com.FucVivo.xposed.XposedFrameworkState
import com.FucVivo.xposed.XposedState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/** 订阅框架状态的通用封装 */
@Composable
fun rememberXposedState(): XposedFrameworkState {
    var state by remember { mutableStateOf<XposedFrameworkState>(XposedState.frameworkState) }
    val listener = remember { FrameworkStateListener { state = it } }
    DisposableEffect(Unit) {
        XposedState.addListener(listener)
        XposedState.refresh()
        onDispose { XposedState.removeListener(listener) }
    }
    return state
}

private data class StatusVisual(
    val bg: Color, val iconTint: Color, val icon: ImageVector,
    val title: String, val summary: String, val action: String,
    val contentColor: Color? = null,
)

/** 模块状态卡（对照 Rain：绿色大勾 + 标题/摘要/动作） */
@Composable
fun XposedStatusCard(state: XposedFrameworkState) {
    val dark = isSystemInDarkTheme()
    val visual = when (state) {
        is XposedFrameworkState.Active -> StatusVisual(
            bg = if (dark) Color(0xFF1E3D29) else Color(0xFFDFFAE4),
            iconTint = if (dark) Color(0xFF4ADE80) else Color(0xFF36D167),
            icon = Icons.Rounded.CheckCircleOutline,
            title = stringResource(R.string.module_status_working),
            summary = stringResource(R.string.module_status_connected_summary, state.frameworkName),
            action = stringResource(R.string.module_connected_action),
            contentColor = if (dark) Color(0xFFD6EBDD) else Color(0xFF1F4D2E),
        )
        XposedFrameworkState.Inactive -> StatusVisual(
            colorScheme.secondaryContainer, colorScheme.onSurfaceVariantSummary,
            Icons.Rounded.RemoveCircleOutline,
            stringResource(R.string.module_status_disconnected),
            stringResource(R.string.module_status_disconnected_summary),
            stringResource(R.string.module_disconnected_action),
        )
        XposedFrameworkState.Error -> StatusVisual(
            colorScheme.secondaryContainer, colorScheme.error, Icons.Rounded.ErrorOutline,
            stringResource(R.string.module_status_error),
            stringResource(R.string.module_status_error_summary),
            stringResource(R.string.module_error_action),
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = visual.bg),
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(150.dp)) {
            Box(Modifier.fillMaxSize().offset(27.dp, 31.dp), contentAlignment = Alignment.BottomEnd) {
                Icon(
                    imageVector = visual.icon,
                    contentDescription = null,
                    modifier = Modifier.size(150.dp),
                    tint = visual.iconTint,
                )
            }
            Box(Modifier.fillMaxSize().padding(16.dp, 12.dp), contentAlignment = Alignment.BottomStart) {
                Text(visual.action, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    color = visual.contentColor ?: colorScheme.onSurface)
            }
            Box(Modifier.fillMaxSize().padding(16.dp, 14.dp), contentAlignment = Alignment.TopStart) {
                Column {
                    Text(visual.title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                        color = visual.contentColor ?: colorScheme.onSurface)
                    Spacer(Modifier.height(1.dp))
                    Text(visual.summary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                        color = visual.contentColor ?: colorScheme.onSurfaceVariantSummary)
                }
            }
        }
    }
}

/** 已适配 / 已启用 统计卡 */
@Composable
fun XposedStatRow(state: XposedFrameworkState) {
    val active = state as? XposedFrameworkState.Active
    val adapted = HookerRegistry.hookers.size
    // 合并管理的多包 hooker 按「全部包都在作用域」计 1，避免重复计数
    val enabled = active?.scope?.let { scope ->
        HookerRegistry.hookers.count { h -> scope.containsAll(h.allPackageNames) }
    } ?: 0
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatCard(stringResource(R.string.module_adapted), adapted, Modifier.weight(1f))
        StatCard(stringResource(R.string.module_enabled), enabled, Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(label: String, value: Int, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(label, fontSize = 15.sp, color = colorScheme.onSurfaceVariantSummary)
            Text(value.toString(), fontSize = 32.sp, fontWeight = FontWeight.SemiBold, color = colorScheme.onSurface)
        }
    }
}

/** 信息卡（系统/应用/Android/LSPosed/构建时间/设备型号） */
@Composable
fun XposedInfoCard(state: XposedFrameworkState) {
    val frameworkValue = when (state) {
        is XposedFrameworkState.Active -> stringResource(
            R.string.info_framework_format, state.frameworkName, state.frameworkVersion,
            state.frameworkVersionCode.toInt(), state.apiVersion,
        )
        XposedFrameworkState.Inactive -> stringResource(R.string.info_framework_disconnected)
        XposedFrameworkState.Error -> stringResource(R.string.info_framework_error)
    }
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            InfoText(stringResource(R.string.info_system_version), Build.DISPLAY.ifEmpty { Build.VERSION.RELEASE })
            InfoText(stringResource(R.string.info_app_version),
                stringResource(R.string.info_app_version_format, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
            InfoText(stringResource(R.string.info_android_version),
                stringResource(R.string.info_android_format, Build.VERSION.RELEASE, Build.VERSION.SDK_INT))
            InfoText(stringResource(R.string.info_lsposed_version), frameworkValue)
            InfoText(stringResource(R.string.info_build_time), BuildConfig.BUILD_TIME)
            InfoText(stringResource(R.string.info_device_model), Build.MODEL, bottomPadding = 0.dp)
        }
    }
}

@Composable
private fun InfoText(title: String, content: String, bottomPadding: Dp = 24.dp) {
    Text(title, fontSize = MiuixTheme.textStyles.headline1.fontSize, fontWeight = FontWeight.Medium, color = colorScheme.onSurface)
    Text(content, fontSize = MiuixTheme.textStyles.body2.fontSize, color = colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(top = 2.dp, bottom = bottomPadding))
}
