package ai.closepaw.ui.settings

import ai.closepaw.ui.theme.uiResources

import ai.closepaw.R

import ai.closepaw.app.MemoryEditGate
import ai.closepaw.memory.MemoryScope
import ai.closepaw.memory.MemoryStore
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.closepaw.ui.theme.PageMastheadDrillDown
import ai.closepaw.ui.theme.closePaw
import androidx.compose.material3.MaterialTheme

internal const val MEMORY_SETTINGS_BANNER_TAG = "memory-settings-banner"
internal const val MEMORY_SETTINGS_USER_ROW_TAG = "memory-settings-user-row"
internal const val MEMORY_SETTINGS_DEVICE_ROW_TAG = "memory-settings-device-row"

/**
 * Settings → Memory. Lists User and Device memory entries. Per-app memory
 * is reached from App Access, not here.
 *
 * Internal navigation swaps between the list and [MemoryFileEditorPage]; Back
 * collapses the editor before exiting the page so callers see a single
 * `onBack` event per tap-out.
 */
@Composable
internal fun MemorySettingsPage(
    memoryStore: MemoryStore,
    gate: MemoryEditGate,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val strings = uiResources()
    var openScopeWire by rememberSaveable { mutableStateOf<String?>(null) }
    val openScope = openScopeWire?.let { MemoryScope.fromWireValue(it) }

    BackHandler(enabled = openScope != null) { openScopeWire = null }

    if (openScope != null) {
        MemoryFileEditorPage(
            title = openScope.pageTitle(strings),
            memoryStore = memoryStore,
            scope = openScope,
            packageName = null,
            gate = gate,
            onBack = { openScopeWire = null },
            onClose = onClose,
        )
        return
    }

    val locked by gate.memoryEditLocked.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxWidth()) {
        PageMastheadDrillDown(title = strings.getString(R.string.ui_memory), onBack = onBack, onClose = onClose)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MaterialTheme.closePaw.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.closePaw.spacing.sm),
        ) {
            if (locked) {
                SettingsAlertCard(
                    message = strings.getString(MEMORY_EDIT_LOCKED_BANNER),
                    tone = AlertTone.Error,
                    modifier = Modifier.testTag(MEMORY_SETTINGS_BANNER_TAG),
                )
            }

            SettingsNavigationRow(
                title = strings.getString(R.string.ui_user_memory),
                subtitle = strings.getString(R.string.ui_facts_and_preferences_shared_across_every_session),
                onClick = { openScopeWire = MemoryScope.USER.wireValue },
                modifier = Modifier.testTag(MEMORY_SETTINGS_USER_ROW_TAG),
            )
            SettingsNavigationRow(
                title = strings.getString(R.string.ui_device_memory),
                subtitle = strings.getString(R.string.ui_facts_pitfalls_and_verification_notes_about_this_device),
                onClick = { openScopeWire = MemoryScope.DEVICE.wireValue },
                modifier = Modifier.testTag(MEMORY_SETTINGS_DEVICE_ROW_TAG),
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun MemoryScope.pageTitle(strings: android.content.res.Resources): String = when (this) {
    MemoryScope.USER -> strings.getString(R.string.ui_user_memory)
    MemoryScope.DEVICE -> strings.getString(R.string.ui_device_memory)
    MemoryScope.APP -> strings.getString(R.string.ui_app_memory)
}
