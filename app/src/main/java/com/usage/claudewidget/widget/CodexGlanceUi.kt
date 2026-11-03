package com.usage.claudewidget.widget

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.background
import androidx.glance.appwidget.cornerRadius
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.usage.claudewidget.R
import com.usage.claudewidget.ui.MainActivity

data class CodexWidgetState(
    val needsLogin: Boolean,
    val hasData: Boolean,
    val fiveHourMissing: Boolean,
    val fiveHourPct: Int,
    val fiveHourResets: String,
    val fiveHourRate: FiveHourRate?,
    val weeklyMissing: Boolean,
    val weeklyPct: Int,
    val weeklyResets: String,
    val weeklyPace: Pace?,
    val weeklyLabel: String,
    val stale: Boolean,
)

private val COMPACT_MAX_WIDTH = 130.dp

@Composable
fun CodexWidgetContent(state: CodexWidgetState) {
    val size = LocalSize.current
    val compact = size.width < COMPACT_MAX_WIDTH
    val context = LocalContext.current

    val tap = if (state.needsLogin) {
        actionStartActivity(Intent(context, MainActivity::class.java))
    } else {
        actionRunCallback<CodexRefreshAction>()
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
            state.needsLogin -> CodexSignInPrompt(compact)
            !state.hasData -> CodexLoading()
            compact -> CodexCompactLayout(state)
            else -> CodexFullLayout(state)
        }
        if (state.hasData && state.stale) StaleDot()
    }
}

@Composable
private fun CodexFullLayout(s: CodexWidgetState) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CodexGlyph(22.dp)
            Spacer(GlanceModifier.width(6.dp))
            Text(
                "Codex usage",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
        Spacer(GlanceModifier.height(8.dp))

        if (s.fiveHourMissing && s.weeklyMissing) {
            Text(
                "No usage limits",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
            )
            return@Column
        }

        if (!s.fiveHourMissing) {
            FiveHourRow(
                pct = s.fiveHourPct,
                resets = s.fiveHourResets,
                rate = s.fiveHourRate,
                color = colorProvider(R.color.codex_accent),
                softColor = colorProvider(R.color.codex_accent_soft),
            )
        }
        if (!s.fiveHourMissing && !s.weeklyMissing) {
            Spacer(GlanceModifier.height(6.dp))
        }
        if (!s.weeklyMissing) {
            MeterRow(
                s.weeklyLabel,
                s.weeklyPct,
                s.weeklyResets,
                s.weeklyPace,
                colorProvider(R.color.codex_accent),
            )
        }
        if (s.fiveHourMissing && !s.weeklyMissing) {
            Spacer(GlanceModifier.height(4.dp))
            Text(
                if (s.weeklyLabel == "1W") "Weekly-only plan" else "Single window",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
            )
        }
    }
}

@Composable
private fun CodexCompactLayout(s: CodexWidgetState) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        CodexGlyph(16.dp)
        Spacer(GlanceModifier.height(6.dp))
        if (s.fiveHourMissing && s.weeklyMissing) {
            Text(
                "No limits",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
            )
        }
        if (!s.fiveHourMissing) {
            CompactMeter("5H", s.fiveHourPct, colorProvider(R.color.codex_accent))
        }
        if (!s.fiveHourMissing && !s.weeklyMissing) {
            Spacer(GlanceModifier.height(6.dp))
        }
        if (!s.weeklyMissing) {
            CompactMeter(s.weeklyLabel, s.weeklyPct, colorProvider(R.color.codex_accent))
        }
    }
}

@Composable
private fun CodexSignInPrompt(compact: Boolean) {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CodexGlyph(if (compact) 18.dp else 24.dp)
        Spacer(GlanceModifier.height(6.dp))
        Text(
            if (compact) "Sign in" else "Tap to sign in",
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Medium),
        )
    }
}

@Composable
private fun CodexLoading() {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("…", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant))
    }
}

@Composable
private fun CodexGlyph(size: androidx.compose.ui.unit.Dp) {
    Image(
        provider = ImageProvider(R.drawable.ic_codex),
        contentDescription = "Codex",
        modifier = GlanceModifier.size(size),
    )
}
