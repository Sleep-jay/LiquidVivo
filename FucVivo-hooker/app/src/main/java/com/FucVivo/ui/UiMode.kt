package com.FucVivo.ui

import androidx.compose.runtime.staticCompositionLocalOf

enum class UiMode(val value: String) {
    Miuix("miuix");

    companion object {
        fun fromValue(value: String): UiMode = Miuix

        val DEFAULT_VALUE = Miuix.value
    }
}

val LocalUiMode = staticCompositionLocalOf { UiMode.Miuix }
