package com.FucVivo.ui.screen.settings

import androidx.compose.runtime.Immutable
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.FucVivo.ui.UiMode

@Immutable
data class SettingsUiState(
    val uiMode: String = UiMode.DEFAULT_VALUE,
    val checkUpdate: Boolean = true,
    val checkModuleUpdate: Boolean = true,
    val themeMode: Int = 0,
    val miuixMonet: Boolean = false,
    val keyColor: Int = 0,
    val colorStyle: String = PaletteStyle.TonalSpot.name,
    val colorSpec: String = ColorSpec.SpecVersion.SPEC_2025.name,
    val enablePredictiveBack: Boolean = false,
    val enableBlur: Boolean = true,
    val enableFloatingBottomBar: Boolean = false,
    val enableFloatingBottomBarBlur: Boolean = false,
    val enableNavigationBadge: Boolean = true,
    val pageScale: Float = 1.0f,
    val enableWebDebugging: Boolean = false,

    val isLkmMode: Boolean = false,
    val isLateLoadMode: Boolean = false,
)

@Immutable
data class SettingsScreenActions(
    val onSetCheckUpdate: (Boolean) -> Unit,
    val onSetCheckModuleUpdate: (Boolean) -> Unit,
    val onOpenTheme: () -> Unit,
    val onSetUiModeIndex: (Int) -> Unit,
    val onOpenProfileTemplate: () -> Unit,
    val onSetEnableWebDebugging: (Boolean) -> Unit,
    val onOpenAbout: () -> Unit,
)
