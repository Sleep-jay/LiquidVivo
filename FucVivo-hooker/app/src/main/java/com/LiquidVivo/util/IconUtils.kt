package com.LiquidVivo.util

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * 加载应用图标为 Compose ImageBitmap。
 * 不能直接 (as? BitmapDrawable)：自适应图标/矢量图标都不是 BitmapDrawable，
 * 强转会静默失败导致回退默认占位图。统一用 Canvas 渲染成位图。
 */
fun loadAppIcon(pm: PackageManager, packageName: String): ImageBitmap? = runCatching {
    val d = pm.getApplicationIcon(packageName)
    if (d is BitmapDrawable) return@runCatching d.bitmap?.asImageBitmap()
    val w = d.intrinsicWidth.coerceAtLeast(1)
    val h = d.intrinsicHeight.coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    d.setBounds(0, 0, w, h)
    d.draw(Canvas(bmp))
    bmp.asImageBitmap()
}.getOrNull()
