package com.FucVivo.ui.screen.superuser

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import com.FucVivo.ui.navigation3.Navigator

/** 应用页入口：直接渲染作用域列表（Miuix）。 */
@Composable
fun SuperUserPager(
    navigator: Navigator,
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true,
    onOpenApp: (String) -> Unit = {},
) {
    AppsScopePagerMiuix(bottomInnerPadding, onOpenApp)
}
