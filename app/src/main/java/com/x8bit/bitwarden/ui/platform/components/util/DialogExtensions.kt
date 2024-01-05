package com.x8bit.bitwarden.ui.platform.components.util

import android.content.res.Configuration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Provides the maximum height [Dp] common for all dialogs with a given [Configuration].
 */
val Configuration.maxDialogHeight: Dp
    get() = when (orientation) {
        Configuration.ORIENTATION_LANDSCAPE -> 312.dp
        Configuration.ORIENTATION_PORTRAIT -> 542.dp
        Configuration.ORIENTATION_UNDEFINED -> Dp.Unspecified
        Configuration.ORIENTATION_SQUARE -> Dp.Unspecified
        else -> Dp.Unspecified
    }
