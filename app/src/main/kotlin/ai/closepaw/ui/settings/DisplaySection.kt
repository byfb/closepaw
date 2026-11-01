package ai.closepaw.ui.settings

import ai.closepaw.ui.theme.uiResources

import ai.closepaw.R

import ai.closepaw.platform.virtualdisplay.ShizukuClient
import ai.closepaw.platform.virtualdisplay.ShizukuRuntimeGateway
import ai.closepaw.protocol.PlatformMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch

/**
 * Display Mode section for Agent Behavior settings. Renders a single [ToolSettingsCard]
 * "Virtual Display" toggle driven by [virtualDisplayCardState].
 *
 * Toggle ON routes through [VirtualDisplayToggleGate] (Shizuku availability + permission gate);
 * toggle OFF is unconditional. Deep-link writes via `MainActivityIntentApplier` bypass the gate
 * and a [LaunchedEffect] wipes any stale gate error once `persistedMode` flips to VD.
 */
@Composable
internal fun DisplaySection(
    persistedMode: PlatformMode,
    effectiveMode: PlatformMode?,
    onPlatformModeChange: (PlatformMode) -> Unit,
) {
    val strings = uiResources()
    val client = remember { ShizukuClient() }
    val shizukuStatus by rememberShizukuStatus(client)
    val scope = rememberCoroutineScope()
    var permissionRequestPending by remember { mutableStateOf(false) }

    val gate = rememberVirtualDisplayToggleGate { enabled ->
        onPlatformModeChange(
            if (enabled) PlatformMode.VIRTUAL_DISPLAY else PlatformMode.ACCESSIBILITY
        )
    }

    LaunchedEffect(persistedMode) {
        if (persistedMode == PlatformMode.VIRTUAL_DISPLAY) gate.clearError()
    }

    val cardState = virtualDisplayCardState(
        persistedMode = persistedMode,
        effectiveMode = effectiveMode,
        shizukuStatus = shizukuStatus,
        gatePending = gate.pending,
        gateError = gate.error,
    )

    val rowAction: (() -> Unit)? = if (permissionRequestPending) {
        null
    } else when (cardState.rowAction) {
        VirtualDisplayRowAction.RetryEnable -> {
            {
                gate.clearError()
                gate.setEnabled(true)
            }
        }
        VirtualDisplayRowAction.RequestPermission -> {
            {
                permissionRequestPending = true
                scope.launch {
                    try {
                        ShizukuRuntimeGateway().requestPermissionAndAwait()
                    } finally {
                        permissionRequestPending = false
                    }
                }
            }
        }
        null -> null
    }

    val rowClickLabel = if (rowAction == null) null else when (cardState.rowAction) {
        VirtualDisplayRowAction.RetryEnable -> strings.getString(R.string.ui_retry_virtual_display_setup)
        VirtualDisplayRowAction.RequestPermission -> strings.getString(R.string.ui_grant_shizuku_permission)
        null -> null
    }

    SettingsSection(title = strings.getString(R.string.ui_display_mode)) {
        ToolSettingsCard(
            title = strings.getString(R.string.ui_virtual_display),
            status = cardState.status,
            switchChecked = cardState.switchChecked,
            switchEnabled = cardState.switchEnabled,
            onSwitchChange = { value ->
                gate.clearError()
                gate.setEnabled(value)
            },
            onRowClick = rowAction,
            onRowClickLabel = rowClickLabel,
            switchModifier = Modifier.testTag("display-mode-switch"),
        )
    }
}
