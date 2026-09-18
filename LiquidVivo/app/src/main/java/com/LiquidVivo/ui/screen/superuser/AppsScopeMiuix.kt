package com.LiquidVivo.ui.screen.superuser

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.LiquidVivo.R
import com.LiquidVivo.ui.theme.LocalEnableBlur
import com.LiquidVivo.ui.util.BlurredBar
import com.LiquidVivo.ui.util.rememberBlurBackdrop
import com.LiquidVivo.xposed.AppHooker
import com.LiquidVivo.xposed.HookerRegistry
import com.LiquidVivo.xposed.XposedFrameworkState
import com.LiquidVivo.xposed.XposedState
import com.LiquidVivo.ui.screen.home.rememberXposedState
import com.LiquidVivo.ui.screen.home.feedbackRes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private data class TargetAppInfo(
    val installed: Boolean,
    val versionName: String?,
    val launchable: Boolean,
    val icon: ImageBitmap?,
)

/** 应用页：顶栏「作用域」+ 每应用卡片（图标/名/包名/状态 + 作用域开关）。 */
@Composable
fun AppsScopePagerMiuix(bottomInnerPadding: Dp, onOpenApp: (String) -> Unit = {}) {
    val state = rememberXposedState()
    val scope = rememberCoroutineScope()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (blurActive) Modifier else Modifier.background(barColor))
                        .statusBarsPadding()
                        .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.apps_scope_title),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Medium,
                        color = colorScheme.onBackground,
                    )
                    Text(
                        text = stringResource(R.string.apps_intro_summary),
                        fontSize = 13.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .scrollEndHaptic()
                .overScrollVertical()
                .padding(horizontal = 12.dp),
            contentPadding = innerPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            overscrollEffect = null,
        ) {
            items(HookerRegistry.hookers, key = { it.packageName }) { hooker ->
                AppCard(
                    hooker = hooker,
                    scopeActive = (state as? XposedFrameworkState.Active)
                        ?.scope?.containsAll(hooker.allPackageNames) == true,
                    onOpenApp = { onOpenApp(hooker.packageName) },
                    onScopeChanged = {
                        scope.launch {
                            delay(800)
                            XposedState.refresh()
                        }
                    },
                )
            }
            item { Spacer(Modifier.height(bottomInnerPadding)) }
        }
    }
}

@Composable
private fun AppCard(
    hooker: AppHooker,
    scopeActive: Boolean,
    onOpenApp: () -> Unit,
    onScopeChanged: () -> Unit,
) {
    val context = LocalContext.current
    val info = rememberTargetInfo(context, hooker.packageName)
    val restartScope = rememberCoroutineScope()
    // 合并管理的附加包（如 SystemUIPlugin）也要装齐才允许入域
    val extrasInstalled = remember(hooker.packageName) {
        hooker.extraPackageNames.all { pkg ->
            try { context.packageManager.getPackageInfo(pkg, 0) } catch (t: Throwable) { null } != null
        }
    }
    var checked by remember(scopeActive) { mutableStateOf(scopeActive) }

    val statusText = when {
        !info.installed || !extrasInstalled -> stringResource(R.string.app_not_installed)
        info.versionName != null && !hooker.supportsVersion(info.versionName) ->
            stringResource(R.string.app_version_unsupported, info.versionName)
        !info.launchable && hooker.requiresLaunchability -> stringResource(R.string.app_not_launchable)
        else -> stringResource(R.string.app_installed, info.versionName.orEmpty())
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clickable(onClick = onOpenApp)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (info.icon != null) {
                Image(info.icon, contentDescription = stringResource(hooker.appNameRes),
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
            } else {
                Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(MiuixIcons.Contacts, contentDescription = null,
                        tint = colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(32.dp))
                }
            }
            Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
                Text(stringResource(hooker.appNameRes), fontSize = 17.sp, fontWeight = FontWeight.Medium, color = colorScheme.onSurface)
                Text(hooker.allPackageNames.joinToString("\n"), fontSize = 14.sp, color = colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 2.dp))
                Text(statusText, fontSize = 14.sp, color = colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 2.dp))
            }
            Switch(
                checked = checked,
                onCheckedChange = { enable ->
                    when {
                        XposedState.xposedServiceOrNull() == null ->
                            Toast.makeText(context, context.getString(R.string.scope_service_unavailable), Toast.LENGTH_SHORT).show()
                        enable && (!info.installed || !extrasInstalled) ->
                            Toast.makeText(context, context.getString(R.string.scope_app_not_installed), Toast.LENGTH_SHORT).show()
                        else -> {
                            checked = enable
                            Toast.makeText(context, context.getString(R.string.scope_updating), Toast.LENGTH_SHORT).show()
                            if (enable) {
                                XposedState.requestScope(
                                    hooker.allPackageNames,
                                    onError = {
                                        checked = false
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
                                            restartScope.launch {
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
                            onScopeChanged()
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun rememberTargetInfo(context: Context, packageName: String): TargetAppInfo =
    remember(packageName) {
        val pm = context.packageManager
        val pi = try { pm.getPackageInfo(packageName, 0) } catch (t: Throwable) { null }
        val launchable = try { pm.getLaunchIntentForPackage(packageName) != null } catch (t: Throwable) { false }
        val icon = com.LiquidVivo.util.loadAppIcon(pm, packageName)
        TargetAppInfo(pi != null, pi?.versionName, launchable, icon)
    }
