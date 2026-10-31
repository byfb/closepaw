package ai.closepaw.voice

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

enum class LiveTaskStatus(val label: String) {
    NONE("Ready"), RUNNING("Working"), WAITING("Needs your input"),
    DONE("Task complete"), FAILED("Task failed"), STOPPED("Task stopped");
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

internal fun voiceStatus(voice: LiveVoiceState, task: LiveTaskStatus): String = when {
    voice.phase == LiveVoicePhase.Connecting -> voice.connectionStage ?: "Connecting…"
    voice.audioInterrupted && voice.running -> "Audio interrupted"
    voice.preview && voice.running -> "Previewing"
    voice.speaking && voice.running -> "Replying"
    voice.muted && voice.running -> "Microphone muted"
    voice.running && task in listOf(LiveTaskStatus.RUNNING, LiveTaskStatus.WAITING) -> task.label
    voice.running -> "Listening"
    task != LiveTaskStatus.NONE -> task.label
    voice.phase == LiveVoicePhase.Error -> "Voice disconnected"
    else -> task.label
}
