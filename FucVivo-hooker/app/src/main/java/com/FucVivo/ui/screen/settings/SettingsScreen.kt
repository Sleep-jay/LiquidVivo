package com.FucVivo.ui.screen.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.FucVivo.ui.LocalUiMode
import com.FucVivo.ui.UiMode
import com.FucVivo.ui.navigation3.Navigator
import com.FucVivo.ui.navigation3.Route
import com.FucVivo.ui.viewmodel.SettingsViewModel

@Composable
fun SettingPager(
    navigator: Navigator,
    bottomInnerPadding: Dp
) {
    val viewModel = viewModel<SettingsViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    val actions = SettingsScreenActions(
        onSetCheckUpdate = viewModel::setCheckUpdate,
        onSetCheckModuleUpdate = viewModel::setCheckModuleUpdate,
        onOpenTheme = { navigator.push(Route.ColorPalette) },
        onSetUiModeIndex = { },
        onOpenProfileTemplate = { },
        onSetEnableWebDebugging = viewModel::setEnableWebDebugging,
        onOpenAbout = { navigator.push(Route.About) },
    )

    SettingPagerMiuix(uiState, actions, bottomInnerPadding)
}
