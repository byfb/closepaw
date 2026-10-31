package ai.closepaw.ui.settings

import ai.closepaw.llm.LLMProvider
import ai.closepaw.llm.ModelCatalog
import ai.closepaw.llm.ModelReasoning
import ai.closepaw.ui.theme.PageMastheadDrillDown
import ai.closepaw.ui.theme.SectionHeader
import ai.closepaw.ui.theme.closePaw
import ai.closepaw.voice.*
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun GptLiveSettingsPage(
    preferences: LiveVoicePreferences,
    onPreferencesChange: (LiveVoicePreferences) -> Unit,
    selectedModel: String,
    reasoningEfforts: Map<String, String>,
    catalog: ModelCatalog,
    signedIn: Boolean,
    accessibilityEnabled: Boolean,
    onAccessibilityClick: () -> Unit,
    onWorkerSettings: () -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val voice by LiveVoiceRuntime.state.collectAsStateWithLifecycle()
    var previewRequested by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val widgetManager = remember(context) { AppWidgetManager.getInstance(context) }
    DisposableEffect(context) {
        onDispose {
            if (previewRequested) context.startService(Intent(context, GptLiveService::class.java)
                .setAction(GptLiveService.ACTION_STOP_PREVIEW))
        }
    }
    Column(Modifier.fillMaxWidth()) {
        PageMastheadDrillDown(title = "GPT-Live", onBack = onBack, onClose = onClose)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = MaterialTheme.closePaw.spacing.lg), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!signedIn) {
                Button(onClick = onWorkerSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Sign in to ChatGPT")
                }
            } else if (!accessibilityEnabled) {
                OutlinedButton(onClick = onAccessibilityClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Enable Accessibility")
                }
            }
            SettingsDropdown(label = "Voice", value = preferences.voice.label, leadingIcon = Icons.Rounded.GraphicEq,
                options = LiveVoice.entries, isSelected = { it == preferences.voice },
                onOptionSelected = { onPreferencesChange(preferences.copy(voice = it)) }, optionText = { Text(it.label) })
            SettingsDropdown(label = "Reply language", value = preferences.language.label, leadingIcon = Icons.Rounded.Language,
                options = LiveLanguage.entries, isSelected = { it == preferences.language },
                onOptionSelected = { onPreferencesChange(preferences.copy(language = it)) }, optionText = { Text(it.label) })
            SettingsDropdown(label = "Tone", value = preferences.tone.label, leadingIcon = Icons.Rounded.RecordVoiceOver,
                options = LiveTone.entries, isSelected = { it == preferences.tone },
                onOptionSelected = { onPreferencesChange(preferences.copy(tone = it)) }, optionText = { Text(it.label) })
            Text("Applies to the next voice session.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = {
                localError = null
                try {
                    if (voice.preview && voice.running) {
                        context.startService(Intent(context, GptLiveService::class.java).setAction(GptLiveService.ACTION_STOP_PREVIEW))
                    } else {
                        previewRequested = true
                        ContextCompat.startForegroundService(context,
                            Intent(context, GptLiveService::class.java).setAction(GptLiveService.ACTION_PREVIEW))
                    }
                } catch (_: Exception) { localError = "Could not start the preview. Try again." }
            }, enabled = signedIn && (!voice.running || voice.preview),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("qa-live-preview")) {
                Text(if (voice.preview && voice.running) "Stop preview" else "Preview voice")
            }
            localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!voice.preview && (voice.running || signedIn && accessibilityEnabled)) {
                GptLiveControls(onRunningChanged = {})
            } else if (voice.error != null) {
                Text(voice.error.orEmpty(), color = MaterialTheme.colorScheme.error)
            }

            SectionHeader("Worker")
            val worker = catalog.resolveOrNull(selectedModel)?.takeIf { it.provider == LLMProvider.OPENAI_CODEX }
                ?: catalog.preferredModelFor(LLMProvider.OPENAI_CODEX)
            val effort = worker?.let { ModelReasoning.effectiveEffort(it, reasoningEfforts[it.name]) }
            val effortLabel = when (effort) {
                null -> "Default"
                "xhigh" -> "Extra high"
                else -> effort.replaceFirstChar { it.uppercase() }
            }
            SettingsNavigationRow(title = "Model & reasoning effort",
                subtitle = "${worker?.displayName ?: "ChatGPT"} · $effortLabel", onClick = onWorkerSettings)
            Text("Worker changes apply to new chats.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (widgetManager.isRequestPinAppWidgetSupported) {
                OutlinedButton(onClick = {
                    if (!widgetManager.requestPinAppWidget(ComponentName(context, GptLiveWidget::class.java), null, null)) {
                        localError = "Add GPT-Live from your launcher's Widgets menu."
                    }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Add to home screen") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
