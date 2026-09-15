package com.FucVivo.ui.navigation3

import android.content.Intent
import androidx.compose.runtime.Composable
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * 模块模板无深链分发，保留空实现以兼容 MainActivity。
 */
@Composable
fun IntentDispatcher(intentChannel: ReceiveChannel<Intent>) {
    // no-op
}
