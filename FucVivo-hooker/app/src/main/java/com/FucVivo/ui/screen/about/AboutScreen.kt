package com.FucVivo.ui.screen.about

import androidx.compose.runtime.Composable
import com.FucVivo.ui.navigation3.LocalNavigator

@Composable
fun AboutScreen() {
    val navigator = LocalNavigator.current
    AboutRainMiuix(onBack = { navigator.pop() })
}
