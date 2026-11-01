package ai.closepaw.ui.settings

import ai.closepaw.ui.theme.uiResources

import ai.closepaw.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.closepaw.BuildConfig
import ai.closepaw.ui.theme.Fleuron
import ai.closepaw.ui.theme.PageMastheadDrillDown
import ai.closepaw.ui.theme.closePaw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File

@Composable
internal fun PermissionsAdvancedSettingsPage(
    isAccessibilityEnabled: Boolean,
    isOverlayEnabled: Boolean,
    onAccessibilityClick: () -> Unit,
    onOverlayClick: () -> Unit,
    debugMode: Boolean,
    onDebugModeChange: (Boolean) -> Unit,
    traceEnabled: Boolean,
    onTraceEnabledChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit
) {
    val strings = uiResources()
    Column(modifier = Modifier.fillMaxWidth()) {
        PageMastheadDrillDown(title = strings.getString(R.string.ui_system_debug), onBack = onBack, onClose = onClose)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MaterialTheme.closePaw.spacing.lg)
        ) {
            SettingsSection(title = strings.getString(R.string.ui_permissions)) {
                Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.closePaw.spacing.md)) {
                    SettingsRow(
                        icon = Icons.Outlined.Settings,
                        title = strings.getString(R.string.ui_permission_accessibility),
                        isEnabled = isAccessibilityEnabled,
                        onClick = onAccessibilityClick
                    )
                    SettingsRow(
                        icon = Icons.Outlined.Layers,
                        title = strings.getString(R.string.ui_overlay_permission),
                        isEnabled = isOverlayEnabled,
                        onClick = onOverlayClick
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            SettingsSection(title = strings.getString(R.string.ui_debug)) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = MaterialTheme.closePaw.spacing.cardPadding, vertical = MaterialTheme.closePaw.spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.BugReport,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = strings.getString(R.string.ui_debug_mode),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = strings.getString(R.string.ui_enable_verbose_logging),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = debugMode,
                            onCheckedChange = onDebugModeChange,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            DataStorageSection(
                traceEnabled = traceEnabled,
                onTraceEnabledChange = onTraceEnabledChange
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = strings.getString(R.string.ui_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Fleuron()
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

private const val TRACE_DIR = "inspection-trace"
private const val SESSIONS_DIR = "sessions"

private sealed interface ClearDataState {
    data object Idle : ClearDataState
    data object Clearing : ClearDataState
    data object Cleared : ClearDataState
    data class Failed(val message: String) : ClearDataState
}

@Composable
private fun DataStorageSection(
    traceEnabled: Boolean,
    onTraceEnabledChange: (Boolean) -> Unit
) {
    val strings = uiResources()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var traceClearState by remember { mutableStateOf<ClearDataState>(ClearDataState.Idle) }
    var sessionClearState by remember { mutableStateOf<ClearDataState>(ClearDataState.Idle) }
    var showClearTracesConfirm by remember { mutableStateOf(false) }
    var showClearSessionsConfirm by remember { mutableStateOf(false) }

    fun clearTraces() {
        if (traceClearState is ClearDataState.Clearing) return
        traceClearState = ClearDataState.Clearing
        scope.launch {
            traceClearState = withContext(Dispatchers.IO) {
                clearDirectory(strings, context.getExternalFilesDir(TRACE_DIR), label = strings.getString(R.string.ui_traces))
            }
        }
    }

    fun clearSessions() {
        if (sessionClearState is ClearDataState.Clearing) return
        sessionClearState = ClearDataState.Clearing
        scope.launch {
            sessionClearState = withContext(Dispatchers.IO) {
                clearDirectory(strings, File(context.filesDir, SESSIONS_DIR), label = strings.getString(R.string.ui_session_history))
            }
        }
    }

    if (showClearTracesConfirm) {
        AlertDialog(
            onDismissRequest = { showClearTracesConfirm = false },
            title = { Text(strings.getString(R.string.ui_clear_traces)) },
            text = { Text(strings.getString(R.string.ui_clear_traces_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearTracesConfirm = false
                        clearTraces()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(strings.getString(R.string.ui_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearTracesConfirm = false }) { Text(strings.getString(R.string.ui_cancel)) }
            }
        )
    }

    if (showClearSessionsConfirm) {
        AlertDialog(
            onDismissRequest = { showClearSessionsConfirm = false },
            title = { Text(strings.getString(R.string.ui_clear_session_history)) },
            text = { Text(strings.getString(R.string.ui_clear_history_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearSessionsConfirm = false
                        clearSessions()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(strings.getString(R.string.ui_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearSessionsConfirm = false }) { Text(strings.getString(R.string.ui_cancel)) }
            }
        )
    }

    SettingsSection(title = strings.getString(R.string.ui_data_storage)) {
        Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.closePaw.spacing.md)) {
            // Trace toggle
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium
            ) {
                Column(modifier = Modifier.padding(horizontal = MaterialTheme.closePaw.spacing.cardPadding, vertical = MaterialTheme.closePaw.spacing.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Storage,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = strings.getString(R.string.ui_session_traces),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = strings.getString(R.string.ui_save_step_by_step_session_logs),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = traceEnabled,
                            onCheckedChange = onTraceEnabledChange,
                            modifier = Modifier.testTag("qa-session-traces-switch"),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                    if (traceEnabled) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = strings.getString(R.string.ui_traces_may_include_screenshots_and_inputs_turn_off_when_done),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = MaterialTheme.closePaw.spacing.md, vertical = MaterialTheme.closePaw.spacing.sm)
                            )
                        }
                    }
                }
            }

            // Clear traces button
            ClearDataButton(
                label = clearDataButtonLabel(traceClearState, idle = strings.getString(R.string.ui_clear_traces), clearing = strings.getString(R.string.ui_clearing_traces), cleared = strings.getString(R.string.ui_traces_cleared)),
                enabled = traceClearState !is ClearDataState.Clearing && traceClearState !is ClearDataState.Cleared,
                onClick = { showClearTracesConfirm = true }
            )
            ClearFailureAlert(state = traceClearState, onRetry = { clearTraces() })

            // Clear session history button
            ClearDataButton(
                label = clearDataButtonLabel(sessionClearState, idle = strings.getString(R.string.ui_clear_session_history), clearing = strings.getString(R.string.ui_clearing_sessions), cleared = strings.getString(R.string.ui_sessions_cleared)),
                enabled = sessionClearState !is ClearDataState.Clearing && sessionClearState !is ClearDataState.Cleared,
                onClick = { showClearSessionsConfirm = true }
            )
            ClearFailureAlert(state = sessionClearState, onRetry = { clearSessions() })
        }
    }
}

private fun clearDirectory(strings: android.content.res.Resources, directory: File?, label: String): ClearDataState {
    if (directory == null) {
        return ClearDataState.Failed(strings.getString(R.string.ui_could_not_access_storage_retry_from_settings, label))
    }
    return try {
        if (directory.deleteRecursively()) {
            ClearDataState.Cleared
        } else {
            ClearDataState.Failed(strings.getString(R.string.ui_could_not_clear_retry_from_settings, label))
        }
    } catch (e: SecurityException) {
        ClearDataState.Failed(strings.getString(R.string.ui_could_not_clear_because_storage_access_was_denied, label))
    }
}

private fun clearDataButtonLabel(
    state: ClearDataState,
    idle: String,
    clearing: String,
    cleared: String,
): String = when (state) {
    ClearDataState.Idle,
    is ClearDataState.Failed -> idle
    ClearDataState.Clearing -> "$clearing..."
    ClearDataState.Cleared -> cleared
}

@Composable
private fun ClearFailureAlert(state: ClearDataState, onRetry: () -> Unit) {
    val strings = uiResources()
    val failed = state as? ClearDataState.Failed ?: return
    SettingsAlertCard(
        message = failed.message,
        tone = AlertTone.Error,
        action = {
            TextButton(onClick = onRetry) {
                Text(strings.getString(R.string.ui_retry))
            }
        },
    )
}

@Composable
private fun ClearDataButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Icon(
            imageVector = Icons.Outlined.DeleteOutline,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.labelLarge)
    }
}
