package com.LiquidVivo.ui.viewmodel

import androidx.compose.runtime.Immutable
import com.LiquidVivo.ui.UiMode
import com.LiquidVivo.ui.theme.AppSettings

@Immutable
data class MainActivityUiState(
    val appSettings: AppSettings,
    val pageScale: Float,
    val enableBlur: Boolean,
    val enableFloatingBottomBar: Boolean,
    val enableFloatingBottomBarBlur: Boolean,
    val enableNavigationBadge: Boolean,
    val uiMode: UiMode,
)
