package com.usage.claudewidget.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.usage.claudewidget.auth.LoginActivity
import com.usage.claudewidget.data.CodexUsageRepository
import com.usage.claudewidget.data.FetchResult
import com.usage.claudewidget.data.Provider
import com.usage.claudewidget.data.Storage
import com.usage.claudewidget.data.UsageRepository
import com.usage.claudewidget.widget.CodexWidget
import com.usage.claudewidget.widget.TimeFmt
import com.usage.claudewidget.widget.UsageWidget
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SetupScreen()
                }
            }
        }
    }
}

@Composable
private fun SetupScreen() {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Usage Widgets", style = MaterialTheme.typography.headlineSmall)

        ProviderSection(Provider.CLAUDE)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        ProviderSection(Provider.CODEX)

        OutlinedButton(onClick = { requestBatteryExemption(context) }) {
            Text("Disable battery optimization")
        }
    }
}

@Composable
private fun ProviderSection(provider: Provider) {
    val context = LocalContext.current
    val storage = remember(provider) { Storage.get(context, provider) }
    val scope = rememberCoroutineScope()

    var status by remember(provider) { mutableStateOf(describe(storage, provider)) }
    var debug by remember(provider) { mutableStateOf("") }

    val loginLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        status = describe(storage, provider)
    }

    Text(provider.displayName, style = MaterialTheme.typography.titleLarge)
    Text(status, style = MaterialTheme.typography.bodyMedium)

    Button(onClick = { loginLauncher.launch(LoginActivity.start(context, provider)) }) {
        Text(
            when {
                !storage.isLoggedIn && provider == Provider.CODEX -> "Sign in to ChatGPT"
                !storage.isLoggedIn -> "Sign in"
                provider == Provider.CODEX -> "Re-sign in to ChatGPT"
                else -> "Re-sign in"
            }
        )
    }

    OutlinedButton(onClick = {
        scope.launch {
            debug = "Fetching…"
            val result = when (provider) {
                Provider.CLAUDE -> UsageRepository(context).refresh()
                Provider.CODEX -> CodexUsageRepository(context).refresh()
            }
            updateWidget(context, provider)
            debug = when (result) {
                is FetchResult.Success ->
                    "5H: ${result.snapshot.fiveHour.utilization}%   " +
                        "1W: ${result.snapshot.sevenDay.utilization}%"
                is FetchResult.CodexSuccess -> buildString {
                    val five = result.snapshot.fiveHour
                    val week = result.snapshot.weekly
                    append("5H: ").append(five?.let { "${it.utilization}%" } ?: "—")
                    append("  ")
                        .append(TimeFmt.windowLabel(result.snapshot.weeklyWindowSeconds))
                        .append(": ")
                    append(week?.let { "${it.utilization}%" } ?: "—")
                    result.snapshot.planType.takeIf { it.isNotBlank() }
                        ?.let { append("  plan ").append(it) }
                }
                is FetchResult.NeedsLogin -> "Session expired. Sign in again."
                is FetchResult.Soft -> "Transient: ${result.reason}"
            }
            status = describe(storage, provider)
        }
    }) { Text("Test fetch now") }

    if (debug.isNotBlank()) {
        Text(debug, style = MaterialTheme.typography.titleMedium)
    }
}

private suspend fun updateWidget(context: Context, provider: Provider) {
    when (provider) {
        Provider.CLAUDE -> UsageWidget.updateAll(context)
        Provider.CODEX -> CodexWidget.updateAll(context)
    }
}

private fun describe(s: Storage, provider: Provider): String = buildString {
    append(if (s.isLoggedIn) "Signed in ✓" else "Not signed in")
    when (provider) {
        Provider.CLAUDE -> {
            append("\norg: ").append(s.orgId ?: "-")
            if (s.hasSnapshot) {
                append("\nlast: 5H ").append(s.fiveHourUtil.toInt()).append("%  1W ")
                    .append(s.sevenDayUtil.toInt()).append("%")
            }
        }
        Provider.CODEX -> {
            if (s.planType.isNotBlank()) append("\nplan: ").append(s.planType)
            if (s.hasSnapshot) {
                append("\nlast: 5H ")
                    .append(if (s.fiveHourMissing) "—" else "${s.fiveHourUtil.toInt()}%")
                append("  ").append(TimeFmt.windowLabel(s.weeklyWindowSeconds)).append(" ")
                    .append(if (s.sevenDayMissing) "—" else "${s.sevenDayUtil.toInt()}%")
            }
        }
    }
}

private fun requestBatteryExemption(context: Context) {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )
        context.startActivity(intent)
    }
}
