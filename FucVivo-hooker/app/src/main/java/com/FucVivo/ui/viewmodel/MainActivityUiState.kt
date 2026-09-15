package com.FucVivo.ui.viewmodel

import androidx.compose.runtime.Immutable
import com.FucVivo.ui.UiMode
import com.FucVivo.ui.theme.AppSettings

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
