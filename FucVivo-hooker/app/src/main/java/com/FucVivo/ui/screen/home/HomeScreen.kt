package com.FucVivo.ui.screen.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import com.FucVivo.ui.navigation3.Navigator

/** 模块页入口。 */
@Composable
fun HomePager(
    navigator: Navigator,
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true,
) {
    HomePagerMiuix(bottomInnerPadding)
}
