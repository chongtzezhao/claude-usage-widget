package com.usage.claudewidget.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import com.usage.claudewidget.data.AuthState
import com.usage.claudewidget.data.Provider
import com.usage.claudewidget.data.Storage
import kotlin.math.roundToInt

class CodexWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(60.dp, 60.dp),    // Compact (~1x1)
            DpSize(180.dp, 110.dp),  // Full
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                CodexWidgetContent(readState(context))
            }
        }
    }

    private fun readState(context: Context): CodexWidgetState {
        val s = Storage.get(context, Provider.CODEX)
        val now = System.currentTimeMillis()
        return CodexWidgetState(
            needsLogin = !s.isLoggedIn || s.authState == AuthState.NEEDS_LOGIN,
            hasData = s.hasSnapshot,
            fiveHourMissing = s.fiveHourMissing,
            fiveHourPct = s.fiveHourUtil.coerceAtLeast(0f).roundToInt(),
            fiveHourResets = TimeFmt.resetsIn(s.fiveHourReset, now),
            weeklyMissing = s.sevenDayMissing,
            weeklyPct = s.sevenDayUtil.coerceAtLeast(0f).roundToInt(),
            weeklyResets = TimeFmt.resetsIn(s.sevenDayReset, now),
            weeklyLabel = TimeFmt.windowLabel(s.weeklyWindowSeconds),
            stale = TimeFmt.isStale(s.fetchedAt, now),
        )
    }

    companion object {
        suspend fun updateAll(context: Context) = CodexWidget().updateAll(context)
    }
}
