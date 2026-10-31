/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import app.opentv.core.AppSettings
import coil.request.ImageRequest

/**
 * An image request decoded at the size it is actually drawn, not the file's full resolution.
 * A 140 dp poster must not become a multi-megabyte bitmap. LITE mode asks for a software bitmap
 * so [android.content.ComponentCallbacks2.onTrimMemory] can drop it; otherwise Coil may keep a
 * hardware bitmap, which draws cheaper on a roomy device.
 */
@Composable
fun rememberSizedImage(data: Any?, width: Dp, height: Dp): ImageRequest {
    val context = LocalContext.current
    val settings = remember { AppSettings.get(context) }
    val lite by settings.liteMode.collectAsState()
    val density = LocalDensity.current
    val widthPx = with(density) { width.roundToPx() }.coerceAtLeast(1)
    val heightPx = with(density) { height.roundToPx() }.coerceAtLeast(1)
    return remember(data, widthPx, heightPx, lite) {
        ImageRequest.Builder(context)
            .data(data)
            .size(widthPx, heightPx)
            .allowHardware(!lite)
            .build()
    }
}
