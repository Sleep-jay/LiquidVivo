package com.LiquidVivo.ui.screen.about

import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.LiquidVivo.BuildConfig
import com.LiquidVivo.R
import com.LiquidVivo.ui.util.BlurredBar
import com.LiquidVivo.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import com.LiquidVivo.ui.theme.LocalEnableBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 关于页：结构/样式对照「主题设置」页（Scaffold + TopAppBar + 分区浅紫标签 + 卡片）。
 */
@Composable
fun AboutRainMiuix(onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.about),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(MiuixIcons.Back, contentDescription = stringResource(R.string.about_back))
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(horizontal = 12.dp),
            contentPadding = innerPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            overscrollEffect = null,
        ) {
        // 项目卡
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                val icon = remember {
                    try {
                        (context.packageManager.getApplicationIcon(context.packageName) as? BitmapDrawable)
                            ?.bitmap?.asImageBitmap()
                    } catch (t: Throwable) { null }
                }
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (icon != null) {
                        Image(icon, contentDescription = stringResource(R.string.app_name),
                            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)))
                    }
                    Text(stringResource(R.string.app_name), fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 10.dp))
                    Text(stringResource(R.string.xposed_description), fontSize = 14.sp,
                        color = colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 4.dp))
                    Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), fontSize = 14.sp,
                        color = colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }

        item { SectionLabel(R.string.about_section_project) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.about_project_intro), fontSize = 15.sp,
                    color = colorScheme.onSurface, modifier = Modifier.padding(16.dp))
            }
        }

        item { SectionLabel(R.string.about_section_features) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                FeatureRow(R.string.about_feature_hooks, R.string.about_feature_hooks_summary)
                FeatureRow(R.string.about_feature_scope, R.string.about_feature_scope_summary)
                FeatureRow(R.string.about_feature_interface, R.string.about_feature_interface_summary)
            }
        }

        item { SectionLabel(R.string.about_section_libraries) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                LibRow("libxposed", "102")
                LibRow("Miuix KMP", "0.9.3")
                LibRow("Jetpack Compose", "BOM 2026.08")
            }
        }

        item { SectionLabel(R.string.about_section_links) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                ArrowPreference(title = stringResource(R.string.about_link_author), summary = "",
                    onClick = { openLink(context, AUTHOR_URL) })
                ArrowPreference(title = stringResource(R.string.about_link_repository), summary = "",
                    onClick = { openLink(context, REPO_URL) })
                ArrowPreference(title = stringResource(R.string.about_link_telegram), summary = "", onClick = { })
            }
        }

        item { SectionLabel(R.string.about_section_disclaimer) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Disclaimer(R.string.about_disclaimer_free)
                Disclaimer(R.string.about_disclaimer_learning)
                Disclaimer(R.string.about_disclaimer_copyright)
                Disclaimer(R.string.about_disclaimer_resale)
                Disclaimer(R.string.about_disclaimer_liability)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
@Composable
private fun SectionLabel(resId: Int) {
    Text(
        text = stringResource(resId),
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = colorScheme.primary,
        modifier = Modifier.padding(start = 12.dp, top = 8.dp),
    )
}

@Composable
private fun FeatureRow(t: Int, s: Int) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(stringResource(t), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colorScheme.onSurface)
        Text(stringResource(s), fontSize = 14.sp, color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun LibRow(name: String, version: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(name, modifier = Modifier.weight(1f), fontSize = 16.sp, color = colorScheme.onSurface)
        Text(version, fontSize = 16.sp, color = colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun Disclaimer(resId: Int) {
    Text(stringResource(resId), fontSize = 13.sp, color = colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

private const val AUTHOR_URL = "https://www.coolapk.com/u/37362267"
private const val REPO_URL = "https://github.com/LuZe0y"

private fun openLink(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
