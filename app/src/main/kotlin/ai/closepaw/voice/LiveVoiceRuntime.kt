package ai.closepaw.voice

import ai.closepaw.R

import ai.closepaw.protocol.*
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

enum class LiveTaskStatus(@androidx.annotation.StringRes val label: Int) {
    NONE(R.string.ui_ready), RUNNING(R.string.ui_working), WAITING(R.string.ui_needs_your_input),
    DONE(R.string.ui_task_complete), FAILED(R.string.ui_task_failed), STOPPED(R.string.ui_task_stopped);
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

internal fun voiceStatus(strings: android.content.res.Resources, voice: LiveVoiceState, task: LiveTaskStatus): String = when {
    voice.phase == LiveVoicePhase.Connecting -> voice.connectionStage ?: strings.getString(R.string.ui_connecting)
    voice.audioInterrupted && voice.running -> strings.getString(R.string.ui_audio_interrupted)
    voice.preview && voice.running -> strings.getString(R.string.ui_previewing)
    voice.speaking && voice.running -> strings.getString(R.string.ui_replying)
    voice.muted && voice.running -> strings.getString(R.string.ui_microphone_muted)
    voice.running && task in listOf(LiveTaskStatus.RUNNING, LiveTaskStatus.WAITING) -> strings.getString(task.label)
    voice.running -> strings.getString(R.string.ui_voice_listening)
    task != LiveTaskStatus.NONE -> strings.getString(task.label)
    voice.phase == LiveVoicePhase.Error -> strings.getString(R.string.ui_voice_disconnected)
    else -> strings.getString(task.label)
}
