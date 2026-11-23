package ai.closepaw.voice

import ai.closepaw.R

import ai.closepaw.protocol.*
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LiveVoicePhase { Idle, Connecting, Active, Error }

data class LiveVoiceState(
    val phase: LiveVoicePhase = LiveVoicePhase.Idle,
    val muted: Boolean = false,
    val transcript: String = "",
    val error: String? = null,
    val preview: Boolean = false,
    val speaking: Boolean = false,
    val audioInterrupted: Boolean = false,
    val connectionStage: String? = null,
) {
    val running: Boolean get() = phase == LiveVoicePhase.Connecting || phase == LiveVoicePhase.Active
    internal val showInOverlay: Boolean get() = !preview && (running || phase == LiveVoicePhase.Error)
}

enum class LiveTaskStatus(internal val status: LiveStatus) {
    NONE(LiveStatus.READY), RUNNING(LiveStatus.WORKING), WAITING(LiveStatus.WAITING),
    DONE(LiveStatus.DONE), FAILED(LiveStatus.FAILED), STOPPED(LiveStatus.STOPPED);

    @get:StringRes val label: Int get() = status.label
}

/** Only small in-memory state lives here; no Context, microphone or service reference. */
object LiveVoiceRuntime {
    internal val mutableState = MutableStateFlow(LiveVoiceState())
    val state = mutableState.asStateFlow()
    private val mutableTask = MutableStateFlow(LiveTaskStatus.NONE)
    val task = mutableTask.asStateFlow()

    fun onAgentUnavailable() {
        if (mutableTask.value in listOf(LiveTaskStatus.RUNNING, LiveTaskStatus.WAITING)) {
            mutableTask.value = LiveTaskStatus.STOPPED
        }
    }

    fun onAgentEvent(event: AgentEvent): Boolean {
        val next = when (event) {
            is SessionStarted -> LiveTaskStatus.NONE
            is TaskStarted -> LiveTaskStatus.RUNNING
            is AskUser, is ApprovalRequired, is SessionTakeover -> LiveTaskStatus.WAITING
            is SessionResumed -> LiveTaskStatus.RUNNING
            is TaskCompleted -> when (event.outcome) {
                TaskOutcome.GOAL_ACHIEVED -> LiveTaskStatus.DONE
                TaskOutcome.USER_STOPPED -> LiveTaskStatus.STOPPED
                TaskOutcome.TASK_IMPOSSIBLE, TaskOutcome.ERROR -> LiveTaskStatus.FAILED
            }
            is SessionError -> LiveTaskStatus.FAILED
            is ThoughtUpdate -> if (mutableTask.value == LiveTaskStatus.WAITING) LiveTaskStatus.RUNNING else return false
            is SessionCompleted -> if (mutableTask.value in listOf(LiveTaskStatus.RUNNING, LiveTaskStatus.WAITING)) LiveTaskStatus.STOPPED else return false
            else -> return false
        }
        if (next == mutableTask.value) return false
        mutableTask.value = next
        return true
    }
}

/** One state drives both the visible label and its high-contrast colors. */
internal enum class LiveStatus(@StringRes val label: Int, @ColorRes val background: Int,
                               @ColorRes val ink: Int = android.R.color.white) {
    READY(R.string.ui_ready, R.color.live_ready),
    SETUP(R.string.ui_set_up_voice, R.color.live_attention, R.color.live_dark_ink),
    CONNECTING(R.string.ui_connecting, R.color.live_connecting, R.color.live_dark_ink),
    LISTENING(R.string.ui_voice_listening, R.color.live_listening, R.color.live_dark_ink),
    REPLYING(R.string.ui_replying, R.color.live_replying),
    PREVIEW(R.string.ui_previewing, R.color.live_replying),
    MUTED(R.string.ui_microphone_muted, R.color.live_muted),
    INTERRUPTED(R.string.ui_audio_interrupted, R.color.live_attention, R.color.live_dark_ink),
    WORKING(R.string.ui_working, R.color.live_working),
    WAITING(R.string.ui_needs_your_input, R.color.live_attention, R.color.live_dark_ink),
    DONE(R.string.ui_task_complete, R.color.live_done),
    FAILED(R.string.ui_task_failed, R.color.live_error),
    STOPPED(R.string.ui_task_stopped, R.color.live_muted),
    DISCONNECTED(R.string.ui_voice_disconnected, R.color.live_error);
}

internal fun liveStatus(voice: LiveVoiceState, task: LiveTaskStatus): LiveStatus = when {
    voice.phase == LiveVoicePhase.Connecting -> LiveStatus.CONNECTING
    voice.phase == LiveVoicePhase.Error -> LiveStatus.DISCONNECTED
    voice.running && voice.audioInterrupted -> LiveStatus.INTERRUPTED
    voice.running && voice.preview -> LiveStatus.PREVIEW
    voice.running && voice.speaking -> LiveStatus.REPLYING
    voice.running && voice.muted -> LiveStatus.MUTED
    voice.running && task != LiveTaskStatus.RUNNING && task != LiveTaskStatus.WAITING -> LiveStatus.LISTENING
    else -> task.status
}

internal fun voiceStatus(strings: android.content.res.Resources, voice: LiveVoiceState, task: LiveTaskStatus): String =
    if (voice.phase == LiveVoicePhase.Connecting && voice.connectionStage != null) voice.connectionStage
    else strings.getString(liveStatus(voice, task).label)
