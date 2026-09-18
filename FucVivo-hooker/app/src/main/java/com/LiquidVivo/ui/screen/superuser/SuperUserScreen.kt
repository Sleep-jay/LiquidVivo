package com.LiquidVivo.ui.screen.superuser

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import com.LiquidVivo.ui.navigation3.Navigator
import com.LiquidVivo.ui.screen.apphook.AppHookScreenMiuix
import com.LiquidVivo.xposed.hooks.SystemUIHooker

/** 应用页入口：直接进入系统界面模糊调节，无需再选手动作用域。 */
@Composable
fun SuperUserPager(
    navigator: Navigator,
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true,
    onOpenApp: (String) -> Unit = {},
) {
    AppHookScreenMiuix(
        hooker = SystemUIHooker,
        bottomInnerPadding = bottomInnerPadding,
        showScopeControls = false,
    )
}
