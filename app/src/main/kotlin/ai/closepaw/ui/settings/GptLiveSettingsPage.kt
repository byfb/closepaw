package ai.closepaw.ui.settings

import ai.closepaw.ui.theme.uiResources

import ai.closepaw.R

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

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
    val strings = uiResources()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var contextCleared by remember { mutableStateOf(false) }
    val voice by LiveVoiceRuntime.state.collectAsStateWithLifecycle()
    LaunchedEffect(voice.running) { if (voice.running) contextCleared = false }
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
                    Text(strings.getString(R.string.ui_sign_in_to_chatgpt))
                }
            } else if (!accessibilityEnabled) {
                OutlinedButton(onClick = onAccessibilityClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(strings.getString(R.string.ui_enable_accessibility))
                }
            }
            SettingsDropdown(label = strings.getString(R.string.ui_voice), value = preferences.voice.label, leadingIcon = Icons.Rounded.GraphicEq,
                options = LiveVoice.entries, isSelected = { it == preferences.voice },
                onOptionSelected = { onPreferencesChange(preferences.copy(voice = it)) }, optionText = { Text(it.label) })
            SettingsDropdown(label = strings.getString(R.string.ui_reply_language), value = strings.getString(preferences.language.label), leadingIcon = Icons.Rounded.Language,
                options = LiveLanguage.entries, isSelected = { it == preferences.language },
                onOptionSelected = { onPreferencesChange(preferences.copy(language = it)) }, optionText = { Text(strings.getString(it.label)) })
            SettingsDropdown(label = strings.getString(R.string.ui_tone), value = strings.getString(preferences.tone.label), leadingIcon = Icons.Rounded.RecordVoiceOver,
                options = LiveTone.entries, isSelected = { it == preferences.tone },
                onOptionSelected = { onPreferencesChange(preferences.copy(tone = it)) }, optionText = { Text(strings.getString(it.label)) })
            Text(strings.getString(R.string.ui_applies_to_the_next_voice_session), style = MaterialTheme.typography.bodySmall,
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
                } catch (_: Exception) { localError = strings.getString(R.string.ui_could_not_start_the_preview_try_again) }
            }, enabled = signedIn && (!voice.running || voice.preview),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("qa-live-preview")) {
                Text(if (voice.preview && voice.running) strings.getString(R.string.ui_stop_preview) else strings.getString(R.string.ui_preview_voice))
            }
            localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!voice.preview && (voice.running || signedIn && accessibilityEnabled)) {
                GptLiveControls(onRunningChanged = {})
            } else if (voice.error != null) {
                Text(voice.error.orEmpty(), color = MaterialTheme.colorScheme.error)
            }

            SectionHeader(strings.getString(R.string.ui_worker))
            val worker = catalog.resolveOrNull(selectedModel)?.takeIf { it.provider == LLMProvider.OPENAI_CODEX }
                ?: catalog.preferredModelFor(LLMProvider.OPENAI_CODEX)
            val effort = worker?.let { ModelReasoning.effectiveEffort(it, reasoningEfforts[it.name]) }
            val effortLabel = effortLabel(effort, strings)
            SettingsNavigationRow(title = strings.getString(R.string.ui_model_reasoning_effort),
                subtitle = "${worker?.displayName ?: "ChatGPT"} · $effortLabel", onClick = onWorkerSettings)
            Text(strings.getString(R.string.ui_worker_changes_apply_to_new_chats), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            SectionHeader(strings.getString(R.string.ui_conversation))
            Text(strings.getString(R.string.ui_live_context_explanation),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { LiveContextStore(context).clear() }
                    contextCleared = true
                }
            }, enabled = !voice.running, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (contextCleared) strings.getString(R.string.ui_voice_context_cleared) else strings.getString(R.string.ui_clear_voice_context))
            }

            SectionHeader(strings.getString(R.string.ui_shortcuts))
            val shortcuts = remember(context) { context.getSystemService(android.content.pm.ShortcutManager::class.java) }
            if (shortcuts.isRequestPinShortcutSupported) {
                OutlinedButton(onClick = {
                    val shortcut = android.content.pm.ShortcutInfo.Builder(context, "gpt_live")
                        .setShortLabel(strings.getString(R.string.ui_start_gpt_live))
                        .setIcon(android.graphics.drawable.Icon.createWithResource(context, ai.closepaw.R.drawable.ic_live_microphone))
                        .setIntent(GptLiveShortcutActivity.startIntent(context)).build()
                    if (!shortcuts.requestPinShortcut(shortcut, null)) localError = strings.getString(R.string.ui_long_press_closepaw_to_add_the_voice_shortcut)
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(strings.getString(R.string.ui_add_voice_shortcut)) }
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                OutlinedButton(onClick = {
                    context.getSystemService(android.app.StatusBarManager::class.java).requestAddTileService(
                        ComponentName(context, GptLiveTileService::class.java), "GPT-Live",
                        android.graphics.drawable.Icon.createWithResource(context, ai.closepaw.R.drawable.ic_live_microphone),
                        context.mainExecutor,
                    ) { result ->
                        if (result != android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED &&
                            result != android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED)
                            localError = strings.getString(R.string.ui_add_gpt_live_from_quick_settings_edit)
                    }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(strings.getString(R.string.ui_add_quick_settings_tile)) }
            }
            if (widgetManager.isRequestPinAppWidgetSupported) {
                OutlinedButton(onClick = {
                    if (!widgetManager.requestPinAppWidget(ComponentName(context, GptLiveWidget::class.java), null, null)) {
                        localError = strings.getString(R.string.ui_add_gpt_live_from_your_launcher_s_widgets_menu)
                    }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(strings.getString(R.string.ui_add_voice_widget)) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
