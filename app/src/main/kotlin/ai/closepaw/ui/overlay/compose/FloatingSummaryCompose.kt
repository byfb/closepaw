package ai.closepaw.ui.overlay.compose

import ai.closepaw.ui.theme.uiResources

import ai.closepaw.R

import ai.closepaw.ui.capsule.surface.StatusPawGlyph
import ai.closepaw.ui.overlay.model.CapsuleMode
import ai.closepaw.voice.LiveTaskStatus
import ai.closepaw.voice.LiveVoicePhase
import ai.closepaw.voice.LiveVoiceState
import ai.closepaw.voice.voiceStatus
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Readable progress without leaving the app the agent is working in. */
@Composable
internal fun FloatingSummaryCompose(
    mode: CapsuleMode,
    voice: LiveVoiceState,
    task: LiveTaskStatus,
    onMinimize: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenControls: () -> Unit,
    onMute: () -> Unit,
    onEndVoice: () -> Unit,
) {
    val strings = uiResources()
    val hasTask = mode !is CapsuleMode.Hidden
    val voiceVisible = voice.showInOverlay
    val title = when (mode) {
        is CapsuleMode.Running -> strings.getString(R.string.ui_working)
        is CapsuleMode.TakeoverPending -> strings.getString(R.string.ui_handing_over)
        is CapsuleMode.Takeover -> strings.getString(R.string.ui_paused)
        is CapsuleMode.Done -> when (task) {
            LiveTaskStatus.FAILED, LiveTaskStatus.STOPPED -> strings.getString(task.label)
            else -> strings.getString(R.string.ui_task_complete)
        }
        is CapsuleMode.Error -> strings.getString(R.string.ui_task_failed)
        is CapsuleMode.WaitingForInput, is CapsuleMode.WaitingForAction,
        is CapsuleMode.WaitingForApproval -> strings.getString(R.string.ui_needs_your_input)
        is CapsuleMode.Hidden -> if (voice.running) "GPT-Live" else voiceStatus(strings, voice, task)
    }
    val summary = when (mode) {
        is CapsuleMode.Running -> mode.thought
        is CapsuleMode.TakeoverPending -> mode.lastThought
        is CapsuleMode.Takeover -> mode.lastThought
        is CapsuleMode.Done -> mode.message
        is CapsuleMode.Error -> mode.message
        is CapsuleMode.WaitingForInput -> mode.question
        is CapsuleMode.WaitingForAction -> mode.instruction
        is CapsuleMode.WaitingForApproval -> strings.getString(R.string.ui_allow_closepaw_to_operate, mode.appLabel)
        is CapsuleMode.Hidden -> ""
    }
    Surface(
        modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = 12.dp).testTag("qa-floating-summary"),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPawGlyph(color = MaterialTheme.colorScheme.primary, size = 18.dp)
                Text(title, style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = onMinimize, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.KeyboardArrowUp, strings.getString(R.string.ui_minimize_summary))
                }
            }
            // Keep controls reachable in landscape and with enlarged system text.
            Column(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.28f).dp)
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (summary.isNotBlank()) Text(summary, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4, overflow = TextOverflow.Ellipsis)
                if (voiceVisible && !voice.error.isNullOrBlank()) Text(voice.error,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                if (voiceVisible && voice.transcript.isNotBlank()) Text(voice.transcript,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (voiceVisible && voice.running) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(voiceStatus(strings, voice, task), style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (voice.phase == LiveVoicePhase.Active) IconButton(onClick = onMute, modifier = Modifier.size(48.dp)) {
                    Icon(if (voice.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                        if (voice.muted) strings.getString(R.string.ui_unmute_gpt_live) else strings.getString(R.string.ui_mute_gpt_live))
                }
                IconButton(onClick = onEndVoice, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.CallEnd, strings.getString(R.string.ui_end_gpt_live), tint = MaterialTheme.colorScheme.error)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (hasTask && mode !is CapsuleMode.Done) TextButton(onClick = onOpenControls,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.getString(R.string.ui_controls)) }
                TextButton(onClick = onOpenChat, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.getString(R.string.ui_open_chat)) }
            }
        }
    }
}
