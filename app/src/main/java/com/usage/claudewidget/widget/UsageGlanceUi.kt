package com.usage.claudewidget.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.glance.LocalContext
import android.content.Intent
import com.usage.claudewidget.R
import com.usage.claudewidget.ui.MainActivity
import java.util.Locale
import kotlin.math.roundToInt

/** Immutable view state handed to the composable; assembled from Storage on each render. */
data class WidgetState(
    val needsLogin: Boolean,
    val hasData: Boolean,
    val fiveHourPct: Int,
    val fiveHourResets: String,
    val fiveHourRate: FiveHourRate?,
    val sevenDayPct: Int,
    val sevenDayResets: String,
    val weeklyPace: Pace?,
    /** Estimated fully-used 5H sessions the remaining weekly quota covers; null when unknown. */
    val sessionsLeft: Float?,
    val stale: Boolean,
)

private val COMPACT_MAX_WIDTH = 130.dp

// Colors come from resources so they auto-adapt to light/dark via values-night.
// Resolve them per composition; the res-id ColorProvider overload is Glance-internal.
@Composable
internal fun colorProvider(resId: Int): ColorProvider =
    ColorProvider(Color(LocalContext.current.getColor(resId)))

@Composable
fun UsageWidgetContent(state: WidgetState) {
    val size = LocalSize.current
    val compact = size.width < COMPACT_MAX_WIDTH
    val context = LocalContext.current

    val tap = if (state.needsLogin) {
        actionStartActivity(Intent(context, MainActivity::class.java))
    } else {
        actionRunCallback<RefreshAction>()
    }

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(20.dp)
            .padding(if (compact) 8.dp else 12.dp)
            .clickable(tap),
    ) {
        when {
            state.needsLogin -> SignInPrompt(compact)
            !state.hasData -> Loading(compact)
            compact -> CompactLayout(state)
            else -> FullLayout(state)
        }
        if (state.hasData && state.stale) StaleDot()
    }
}

@Composable
private fun FullLayout(s: WidgetState) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Lobster(22.dp)
            Spacer(GlanceModifier.width(6.dp))
            Text(
                "Claude usage",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
            Spacer(GlanceModifier.defaultWeight())
            val sessions = s.sessionsLeft
            if (sessions != null && sessions.isFinite() && sessions >= 0f) {
                Text(
                    formatSessionsLeft(sessions),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 8.sp,
                    ),
                    maxLines = 1,
                )
            }
        }
        Spacer(GlanceModifier.height(8.dp))
        FiveHourRow(
            pct = s.fiveHourPct,
            resets = s.fiveHourResets,
            rate = s.fiveHourRate,
            color = colorProvider(R.color.accent),
            softColor = colorProvider(R.color.accent_soft),
            stale = s.stale,
        )
        Spacer(GlanceModifier.height(6.dp))
        MeterRow(
            "1W",
            s.sevenDayPct,
            s.sevenDayResets,
            s.weeklyPace,
            colorProvider(R.color.accent),
        )
    }
}

@Composable
internal fun FiveHourRow(
    pct: Int,
    resets: String,
    rate: FiveHourRate?,
    color: ColorProvider,
    softColor: ColorProvider,
    stale: Boolean,
) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "5H",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontWeight = FontWeight.Bold),
                modifier = GlanceModifier.width(28.dp),
            )
            Text(
                "$pct%",
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold),
            )
            Spacer(GlanceModifier.defaultWeight())
            Text(
                resets,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
            )
        }
        Spacer(GlanceModifier.height(4.dp))

        val caps = rate?.capsInMinutes
        val ghostColor = when {
            caps == null -> softColor
            caps <= 30L -> colorProvider(R.color.pace_critical)
            else -> colorProvider(R.color.pace_over)
        }
        Bar(
            pct = pct,
            color = color,
            projectedPct = rate?.projectedPct,
            projectedColor = ghostColor,
        )

        Spacer(GlanceModifier.height(2.dp))
        RateLine(rate, stale)
    }
}

@Composable
private fun RateLine(rate: FiveHourRate?, stale: Boolean) {
    val caps = rate?.capsInMinutes
    val tint = when {
        caps == null -> GlanceTheme.colors.onSurfaceVariant
        caps <= 30L -> colorProvider(R.color.pace_critical)
        else -> colorProvider(R.color.pace_over)
    }
    val label = when {
        // "collecting" is honest only while the readings are still fresh.
        rate == null -> if (stale) "stale" else "collecting"
        rate.pctPerHour < 0.05f -> "idle"
        caps == null -> "${formatRate(rate.pctPerHour)}/h · fits reset"
        caps <= 0L -> "${formatRate(rate.pctPerHour)}/h · capped"
        else -> "${formatRate(rate.pctPerHour)}/h · caps in ${TimeFmt.minutesLabel(caps)}"
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            provider = ImageProvider(R.drawable.ic_rate_flame),
            contentDescription = null,
            modifier = GlanceModifier.size(10.dp),
        )
        Spacer(GlanceModifier.width(3.dp))
        Text(
            label,
            style = TextStyle(color = tint, fontSize = 9.sp),
            maxLines = 1,
        )
    }
}

private fun formatRate(rate: Float): String =
    if (rate < 10f) String.format(Locale.US, "%.1f", rate) else rate.roundToInt().toString()

/** One decimal is the maximum display precision; never truncated or floored to Int. */
private fun formatSessionsLeft(sessions: Float): String =
    "≈" + String.format(Locale.US, "%.1f", sessions) + "×5H"

@Composable
internal fun MeterRow(
    label: String,
    pct: Int,
    resets: String,
    pace: Pace?,
    color: ColorProvider,
) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontWeight = FontWeight.Bold),
                modifier = GlanceModifier.width(28.dp),
            )
            Text(
                "$pct%",
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold),
            )
            Spacer(GlanceModifier.defaultWeight())
            if (pace != null) {
                PaceIcon(pace)
                Spacer(GlanceModifier.width(4.dp))
            }
            Text(
                resets,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
            )
        }
        Spacer(GlanceModifier.height(4.dp))
        Bar(pct, color)
    }
}

@Composable
internal fun PaceIcon(pace: Pace) {
    Image(
        provider = ImageProvider(pace.iconRes),
        contentDescription = pace.label,
        modifier = GlanceModifier.size(14.dp),
    )
}

@Composable
private fun CompactLayout(s: WidgetState) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Lobster(16.dp)
        Spacer(GlanceModifier.height(6.dp))
        CompactMeter("5H", s.fiveHourPct, colorProvider(R.color.accent))
        Spacer(GlanceModifier.height(6.dp))
        CompactMeter("1W", s.sevenDayPct, colorProvider(R.color.accent))
    }
}

@Composable
internal fun CompactMeter(
    label: String,
    pct: Int,
    color: ColorProvider,
) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Text(
                label,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontWeight = FontWeight.Bold),
            )
            Spacer(GlanceModifier.defaultWeight())
            Text(
                "$pct%",
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold),
            )
        }
        Spacer(GlanceModifier.height(3.dp))
        Bar(pct, color)
    }
}

@Composable
internal fun Bar(
    pct: Int,
    color: ColorProvider,
    projectedPct: Int? = null,
    projectedColor: ColorProvider? = null,
) {
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(6.dp)
            .cornerRadius(3.dp)
            .background(colorProvider(R.color.bar_track)),
    ) {
        if (projectedPct != null && projectedColor != null && projectedPct > pct) {
            LinearProgressIndicator(
                progress = (projectedPct.coerceIn(0, 100)) / 100f,
                modifier = GlanceModifier.fillMaxWidth().height(6.dp).cornerRadius(3.dp),
                color = projectedColor,
                backgroundColor = ColorProvider(Color.Transparent),
            )
        }
        LinearProgressIndicator(
            progress = (pct.coerceIn(0, 100)) / 100f,
            modifier = GlanceModifier.fillMaxWidth().height(6.dp).cornerRadius(3.dp),
            color = color,
            backgroundColor = ColorProvider(Color.Transparent),
        )
    }
}

@Composable
private fun Lobster(s: androidx.compose.ui.unit.Dp) {
    Image(
        provider = ImageProvider(R.drawable.ic_lobster),
        contentDescription = "Claude",
        modifier = GlanceModifier.size(s),
    )
}

@Composable
private fun SignInPrompt(compact: Boolean) {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Lobster(if (compact) 18.dp else 24.dp)
        Spacer(GlanceModifier.height(6.dp))
        Text(
            if (compact) "Sign in" else "Tap to sign in",
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Medium),
        )
    }
}

@Composable
private fun Loading(compact: Boolean) {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("…", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant))
    }
}

@Composable
internal fun StaleDot() {
    Box(
        modifier = GlanceModifier.fillMaxSize().padding(2.dp),
        contentAlignment = Alignment.TopEnd,
    ) {
        Box(
            modifier = GlanceModifier
                .size(8.dp)
                .cornerRadius(4.dp)
                .background(colorProvider(R.color.stale)),
        ) {}
    }
}
