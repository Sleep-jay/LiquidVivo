package com.LiquidVivo.ui.screen.apphook

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.LiquidVivo.R
import com.LiquidVivo.ui.screen.home.rememberXposedState
import com.LiquidVivo.ui.screen.home.feedbackRes
import com.LiquidVivo.ui.theme.LocalEnableBlur
import com.LiquidVivo.ui.util.BlurredBar
import com.LiquidVivo.ui.util.rememberBlurBackdrop
import com.LiquidVivo.xposed.AppHooker
import com.LiquidVivo.xposed.HookEntry
import io.github.libxposed.service.HookedTarget
import com.LiquidVivo.xposed.XposedFrameworkState
import com.LiquidVivo.xposed.XposedState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private data class TargetInfo(
    val installed: Boolean,
    val versionName: String?,
    val launchable: Boolean,
    val icon: ImageBitmap?,
)

@Composable
private fun rememberTargetInfo(context: Context, packageName: String): TargetInfo =
    remember(packageName) {
        val pm = context.packageManager
        val pi = try { pm.getPackageInfo(packageName, 0) } catch (t: Throwable) { null }
        val launchable = try { pm.getLaunchIntentForPackage(packageName) != null } catch (t: Throwable) { false }
        val icon = com.LiquidVivo.util.loadAppIcon(pm, packageName)
        TargetInfo(pi != null, pi?.versionName, launchable, icon)
    }

/** Hook/注入管理页：结构与主题设置页统一（BlurredBar + TopAppBar + 返回 + 滚动行为）。 */
@Composable
fun AppHookScreenMiuix(
    hooker: AppHooker,
    onBack: (() -> Unit)? = null,
    bottomInnerPadding: Dp = 0.dp,
    showScopeControls: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberXposedState()
    val info = rememberTargetInfo(context, hooker.packageName)

    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface

    val scopeActive = (state as? XposedFrameworkState.Active)
        ?.scope?.containsAll(hooker.allPackageNames) == true
    var scopeChecked by remember(scopeActive) { mutableStateOf(scopeActive) }

    // 目标进程是否还跑着旧模块代码（更新模块 APK 后未重启目标时为 STALE）
    val stale = XposedState.runningTargets().any {
        it.processName in hooker.allPackageNames && it.state == HookedTarget.State.STALE
    }

    val statusText = when {
        !info.installed -> stringResource(R.string.app_not_installed)
        info.versionName != null && !hooker.supportsVersion(info.versionName) ->
            stringResource(R.string.app_version_unsupported, info.versionName)
        !info.launchable && hooker.requiresLaunchability -> stringResource(R.string.app_not_launchable)
        else -> stringResource(R.string.app_installed, info.versionName.orEmpty())
    }

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(hooker.appNameRes),
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                val layoutDirection = LocalLayoutDirection.current
                                Icon(
                                    modifier = Modifier.graphicsLayer {
                                        if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                                    },
                                    imageVector = MiuixIcons.Back,
                                    contentDescription = null,
                                    tint = colorScheme.onBackground
                                )
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 12.dp),
                contentPadding = innerPadding,
                overscrollEffect = null,
            ) {
                item {
                    Spacer(modifier = Modifier.height(12.dp))

                    // 应用信息卡
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (info.icon != null) {
                                Image(
                                    info.icon,
                                    contentDescription = stringResource(hooker.appNameRes),
                                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                                )
                            } else {
                                Box(
                                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        MiuixIcons.Contacts,
                                        contentDescription = null,
                                        tint = colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
                                Text(
                                    stringResource(hooker.appNameRes),
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colorScheme.onSurface
                                )
                                Text(
                                    hooker.allPackageNames.joinToString("\n"),
                                    fontSize = 14.sp,
                                    color = colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                                Text(
                                    statusText,
                                    fontSize = 14.sp,
                                    color = colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }

                    // Hook 管理卡：作用域开关（应用页已改为静态作用域，默认不展示）
                    if (showScopeControls) Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.apphook_hook_title),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp),
                        )
                        SwitchPreference(
                            title = stringResource(R.string.apphook_scope_enable),
                            checked = scopeChecked,
                            onCheckedChange = { enable ->
                                when {
                                    XposedState.xposedServiceOrNull() == null ->
                                        Toast.makeText(context, context.getString(R.string.scope_service_unavailable), Toast.LENGTH_SHORT).show()
                                    enable && !info.installed ->
                                        Toast.makeText(context, context.getString(R.string.scope_app_not_installed), Toast.LENGTH_SHORT).show()
                                    else -> {
                                        scopeChecked = enable
                                        Toast.makeText(context, context.getString(R.string.scope_updating), Toast.LENGTH_SHORT).show()
                                        if (enable) {
                                            XposedState.requestScope(
                                                hooker.allPackageNames,
                                                onError = {
                                                    scopeChecked = false
                                                    Toast.makeText(context, context.getString(R.string.scope_request_failed), Toast.LENGTH_SHORT).show()
                                                },
                                                onApproved = {
                                                    XposedState.refresh()
                                                    if (XposedState.isSystemUiScope(hooker.allPackageNames)) {
                                                        Toast.makeText(
                                                            context,
                                                            context.getString(R.string.scope_restarting_systemui),
                                                            Toast.LENGTH_SHORT,
                                                        ).show()
                                                        scope.launch {
                                                            val r = XposedState.requestRestartSystemUI()
                                                            Toast.makeText(
                                                                context,
                                                                context.getString(r.feedbackRes()),
                                                                Toast.LENGTH_LONG,
                                                            ).show()
                                                        }
                                                    }
                                                },
                                            )
                                        } else {
                                            XposedState.removeScope(hooker.allPackageNames)
                                        }
                                        scope.launch {
                                            delay(800)
                                            XposedState.refresh()
                                        }
                                    }
                                }
                            }
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.apphook_status),
                                fontSize = 16.sp,
                                color = colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = stringResource(
                                    when {
                                        !scopeChecked -> R.string.apphook_hook_inactive
                                        stale -> R.string.apphook_hook_stale
                                        else -> R.string.apphook_hook_active
                                    }
                                ),
                                fontSize = 14.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                        }

                        // 打开应用（重启目标=最近任务划掉重开即可，无需强停按钮）；
                        // 无启动器入口的系统级目标（SystemUI）不显示
                        if (info.launchable) {
                            TextButton(
                                text = stringResource(R.string.apphook_open_app),
                                onClick = {
                                    val intent = context.packageManager.getLaunchIntentForPackage(hooker.packageName)
                                    if (intent != null) context.startActivity(intent)
                                    else Toast.makeText(
                                        context,
                                        context.getString(R.string.app_not_launchable),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                    }

                    // 功能说明卡
                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.apphook_feature),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp),
                        )
                        Text(
                            text = stringResource(hooker.featureSummaryRes),
                            fontSize = 15.sp,
                            color = colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.apphook_adapted_version),
                                fontSize = 16.sp,
                                color = colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = hooker.adaptedVersion
                                    ?: stringResource(R.string.apphook_adapted_all),
                                fontSize = 14.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }

                    // 功能开关卡（重新进入目标页面后生效）
                    val featureTitleRes = hooker.hookFeatureTitleRes
                    if (hooker.hookFeatures.isNotEmpty() && featureTitleRes != null) {
                        Card(
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(featureTitleRes),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp),
                            )
                            val hookPrefs = context.getSharedPreferences(
                                HookEntry.HOOK_PREFS_NAME, Context.MODE_PRIVATE
                            )
                            hooker.hookFeatures.forEach { feature ->
                                val prefKey = "enable_" + feature.key
                                var checked by remember(feature.key) {
                                    mutableStateOf(
                                        XposedState.hookPreferences()
                                            ?.getBoolean(prefKey, feature.defaultOn)
                                            ?: hookPrefs.getBoolean(prefKey, feature.defaultOn)
                                    )
                                }
                                SwitchPreference(
                                    title = feature.title,
                                    checked = checked,
                                    onCheckedChange = { enable ->
                                        checked = enable
                                        hookPrefs.edit().putBoolean(prefKey, enable).apply()
                                        // 必须经 service editor 写入：LSPosed remote prefs 存于
                                        // daemon 数据库并实时推送 hook 进程，本地文件写入无效
                                        val remote = XposedState.hookPreferences()
                                        if (remote == null) {
                                            Toast.makeText(context, context.getString(R.string.scope_service_unavailable), Toast.LENGTH_SHORT).show()
                                        } else {
                                            remote.edit().putBoolean(prefKey, enable).commit()
                                        }
                                    },
                                )
                            }
                            Text(
                                text = stringResource(R.string.apphook_feature_hint),
                                fontSize = 13.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                    }

                    // 目标专属附加 UI（如设置的插帧档位卡）
                    hooker.managementExtra?.invoke()

                    Spacer(modifier = Modifier.height(24.dp + bottomInnerPadding))
                }
            }
        }
    }
}
