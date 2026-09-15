package com.FucVivo.data.repository

interface SettingsRepository {
    var uiMode: String
    var checkUpdate: Boolean
    var checkModuleUpdate: Boolean
    var themeMode: Int
    var miuixMonet: Boolean
    var keyColor: Int
    var colorStyle: String
    var colorSpec: String
    var enablePredictiveBack: Boolean
    var enableBlur: Boolean
    var enableFloatingBottomBar: Boolean
    var enableFloatingBottomBarBlur: Boolean
    var enableNavigationBadge: Boolean
    var pageScale: Float
    var enableWebDebugging: Boolean
    var moduleSortEnabledFirst: Boolean
    var moduleSortActionFirst: Boolean
    var moduleRepoSortOrder: Int
    val intentToken: String

    fun isLkmMode(): Boolean
}
