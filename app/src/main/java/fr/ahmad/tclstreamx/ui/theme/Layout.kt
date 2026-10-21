package fr.ahmad.tclstreamx.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Phone in portrait (and small phones in landscape): stacked layouts, top tab bar instead of the rail. */
@Composable
fun isCompactWidth(): Boolean = LocalConfiguration.current.screenWidthDp < COMPACT_MAX_DP

/** Below this width the live screen drops its preview monitor (phones in landscape, small tablets). */
@Composable
fun hasRoomForMonitor(): Boolean = LocalConfiguration.current.screenWidthDp >= MONITOR_MIN_DP

private const val COMPACT_MAX_DP = 600
private const val MONITOR_MIN_DP = 900

/** Outer padding of the scrolling screens: generous on a TV (overscan-safe), tight on a phone. */
@Composable
fun screenPadding(end: Dp = 40.dp): PaddingValues =
    if (isCompactWidth()) PaddingValues(start = 16.dp, end = if (end == 0.dp) 0.dp else 16.dp, top = 12.dp, bottom = 24.dp)
    else PaddingValues(start = 40.dp, end = end, top = 30.dp, bottom = 40.dp)

/**
 * True on Android TV / Google TV. Initial focus is only requested there: on a phone, requesting
 * focus scrolls rows to bring the item into view, which crops the first cards.
 */
@Composable
fun isTv(): Boolean =
    (LocalConfiguration.current.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
        android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
