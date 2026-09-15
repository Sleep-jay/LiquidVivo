package com.FucVivo.ui.screen.apphook

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.FucVivo.ui.navigation3.LocalNavigator
import com.FucVivo.xposed.HookerRegistry

/** 目标应用 Hook/注入管理页入口（行为与主题设置页统一）。 */
@Composable
fun AppHookScreen(packageName: String) {
    val navigator = LocalNavigator.current
    val hooker = remember(packageName) { HookerRegistry.find(packageName) }

    if (hooker == null) {
        LaunchedEffect(packageName) { navigator.pop() }
        return
    }

    AppHookScreenMiuix(
        hooker = hooker,
        onBack = { navigator.pop() },
    )
}
