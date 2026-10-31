package ai.closepaw.ui.settings

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
    var live by rememberSaveable(liveInstructions) { mutableStateOf(liveInstructions) }
    var core by rememberSaveable(coreInstructions) { mutableStateOf(coreInstructions) }
    var confirmExit by remember { mutableStateOf<(() -> Unit)?>(null) }
    val changed = live != liveInstructions || core != coreInstructions
    fun leave(action: () -> Unit) { if (changed) confirmExit = action else action() }
    androidx.activity.compose.BackHandler { leave(onBack) }
    Column(Modifier.fillMaxWidth()) {
        PageMastheadDrillDown("Custom instructions", onBack = { leave(onBack) }, onClose = { leave(onClose) })
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = MaterialTheme.closePaw.spacing.lg), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(value = live, onValueChange = { live = it.take(CustomInstructions.MAX_LENGTH) },
                label = { Text("GPT-Live") }, minLines = 4, maxLines = 10, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Speaking style and conversation context. Applies to new calls.") })
            OutlinedTextField(value = core, onValueChange = { core = it.take(CustomInstructions.MAX_LENGTH) },
                label = { Text("Core Agent") }, minLines = 4, maxLines = 10, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Phone workflows and background knowledge. Applies to new chats.") })
            Button(onClick = { onSave(live, core) }, enabled = changed, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Save")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    confirmExit?.let { exit ->
        AlertDialog(onDismissRequest = { confirmExit = null }, title = { Text("Save changes?") },
            confirmButton = { TextButton(onClick = { onSave(live, core); confirmExit = null; exit() }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { confirmExit = null; exit() }) { Text("Discard") } })
    }
}
