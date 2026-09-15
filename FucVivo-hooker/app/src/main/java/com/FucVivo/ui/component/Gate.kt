package com.FucVivo.ui.component

import androidx.compose.runtime.Composable

@Composable
fun Gate(
    content: @Composable () -> Unit
) {
    // 模块模板下统一渲染（无特性门控）。
    content()
}
