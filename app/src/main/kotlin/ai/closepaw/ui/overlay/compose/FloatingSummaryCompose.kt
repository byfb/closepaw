package ai.closepaw.ui.overlay.compose

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
    val hasTask = mode !is CapsuleMode.Hidden
    val voiceVisible = voice.showInOverlay
    val title = when (mode) {
        is CapsuleMode.Running -> "Working"
        is CapsuleMode.TakeoverPending -> "Handing over…"
        is CapsuleMode.Takeover -> "Paused"
        is CapsuleMode.Done -> when (task) {
            LiveTaskStatus.FAILED, LiveTaskStatus.STOPPED -> task.label
            else -> "Task complete"
        }
        is CapsuleMode.Error -> "Task failed"
        is CapsuleMode.WaitingForInput, is CapsuleMode.WaitingForAction,
        is CapsuleMode.WaitingForApproval -> "Needs your input"
        is CapsuleMode.Hidden -> if (voice.running) "GPT-Live" else voiceStatus(voice, task)
    }
    val summary = when (mode) {
        is CapsuleMode.Running -> mode.thought
        is CapsuleMode.TakeoverPending -> mode.lastThought
        is CapsuleMode.Takeover -> mode.lastThought
        is CapsuleMode.Done -> mode.message
        is CapsuleMode.Error -> mode.message
        is CapsuleMode.WaitingForInput -> mode.question
        is CapsuleMode.WaitingForAction -> mode.instruction
        is CapsuleMode.WaitingForApproval -> "Allow ClosePaw to operate ${mode.appLabel}?"
        is CapsuleMode.Hidden -> ""
    }
    Surface(
        modifier = Modifier.width(312.dp).testTag("qa-floating-summary"),
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
                    Icon(Icons.Rounded.KeyboardArrowUp, "Minimize summary")
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
                Text(voiceStatus(voice, task), style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (voice.phase == LiveVoicePhase.Active) IconButton(onClick = onMute, modifier = Modifier.size(48.dp)) {
                    Icon(if (voice.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                        if (voice.muted) "Unmute GPT-Live" else "Mute GPT-Live")
                }
                IconButton(onClick = onEndVoice, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.CallEnd, "End GPT-Live", tint = MaterialTheme.colorScheme.error)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (hasTask && mode !is CapsuleMode.Done) TextButton(onClick = onOpenControls,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Controls") }
                TextButton(onClick = onOpenChat, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open chat") }
            }
        }
    }
}
