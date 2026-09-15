package com.FucVivo.ui.util

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.FucVivo.ui.component.liquid.lens
import com.FucVivo.ui.component.liquid.vibrancy

@Composable
fun rememberBlurBackdrop(enableBlur: Boolean): LayerBackdrop? {
    if (!enableBlur || !isRenderEffectSupported()) return null
    val surfaceColor = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

@Composable
fun BlurredBar(
    backdrop: LayerBackdrop?,
    blurActive: Boolean = true,
    shape: Shape = RectangleShape,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = if (blurActive && backdrop != null) {
            Modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(10.dp.toPx(), 10.dp.toPx())
                    lens(
                        refractionHeight = 20.dp.toPx(),
                        refractionAmount = 18.dp.toPx(),
                        chromaticAberration = 0.35f,
                    )
                },
                onDrawSurface = {
                    drawRect(MiuixTheme.colorScheme.surface.copy(alpha = 0.72f))
                },
            )
        } else {
            Modifier
        },
    ) {
        content()
    }
}
