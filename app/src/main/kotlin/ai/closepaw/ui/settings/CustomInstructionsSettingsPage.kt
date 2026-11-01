package ai.closepaw.ui.settings

import ai.closepaw.ui.theme.uiResources

import ai.closepaw.R

import ai.closepaw.protocol.CustomInstructions
import ai.closepaw.ui.theme.PageMastheadDrillDown
import ai.closepaw.ui.theme.closePaw
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun CustomInstructionsSettingsPage(
    liveInstructions: String,
    coreInstructions: String,
    onSave: (String, String) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val strings = uiResources()
    var live by rememberSaveable(liveInstructions) { mutableStateOf(liveInstructions) }
    var core by rememberSaveable(coreInstructions) { mutableStateOf(coreInstructions) }
    var confirmExit by remember { mutableStateOf<(() -> Unit)?>(null) }
    val changed = live != liveInstructions || core != coreInstructions
    fun leave(action: () -> Unit) { if (changed) confirmExit = action else action() }
    androidx.activity.compose.BackHandler { leave(onBack) }
    Column(Modifier.fillMaxWidth()) {
        PageMastheadDrillDown(strings.getString(R.string.ui_custom_instructions), onBack = { leave(onBack) }, onClose = { leave(onClose) })
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = MaterialTheme.closePaw.spacing.lg), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(value = live, onValueChange = { live = it.take(CustomInstructions.MAX_LENGTH) },
                label = { Text("GPT-Live") }, minLines = 4, maxLines = 10, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text(strings.getString(R.string.ui_speaking_style_and_conversation_context_applies_to_new_calls)) })
            OutlinedTextField(value = core, onValueChange = { core = it.take(CustomInstructions.MAX_LENGTH) },
                label = { Text(strings.getString(R.string.ui_core_agent)) }, minLines = 4, maxLines = 10, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text(strings.getString(R.string.ui_phone_workflows_and_background_knowledge_applies_to_new_chats)) })
            Button(onClick = { onSave(live, core) }, enabled = changed, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(strings.getString(R.string.ui_save))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    confirmExit?.let { exit ->
        AlertDialog(onDismissRequest = { confirmExit = null }, title = { Text(strings.getString(R.string.ui_save_changes)) },
            confirmButton = { TextButton(onClick = { onSave(live, core); confirmExit = null; exit() }) { Text(strings.getString(R.string.ui_save)) } },
            dismissButton = { TextButton(onClick = { confirmExit = null; exit() }) { Text(strings.getString(R.string.ui_discard)) } })
    }
}
