package com.usage.claudewidget.widget

import com.usage.claudewidget.R

/**
 * Where the current utilisation is heading relative to the window's reset.
 * Colors live on the drawables (@color/pace_*), so this stays resource-light.
 */
enum class Pace(
    /** Short human label, also used as the icon's content description. */
    val label: String,
    val iconRes: Int,
) {
    UNDER("Under pace, use more", R.drawable.ic_pace_under),
    ON_TRACK("On pace", R.drawable.ic_pace_ok),
    OVER("Over pace, slow down", R.drawable.ic_pace_over),
    CRITICAL("Critical pace, stop", R.drawable.ic_pace_critical),
}
