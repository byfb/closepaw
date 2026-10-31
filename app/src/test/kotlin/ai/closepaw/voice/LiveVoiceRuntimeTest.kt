package ai.closepaw.voice

import ai.closepaw.protocol.*
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LiveVoiceRuntimeTest {
    private val session = SessionId("test")

    @Test fun `widget retains actual task outcome after voice ends and session closes`() {
        for ((outcome, expected) in listOf(TaskOutcome.GOAL_ACHIEVED to LiveTaskStatus.DONE,
            TaskOutcome.TASK_IMPOSSIBLE to LiveTaskStatus.FAILED, TaskOutcome.ERROR to LiveTaskStatus.FAILED,
            TaskOutcome.USER_STOPPED to LiveTaskStatus.STOPPED)) {
            LiveVoiceRuntime.onAgentEvent(TaskStarted(session, 1, "task", "Open settings"))
            assertThat(voiceStatus(LiveVoiceState(), LiveVoiceRuntime.task.value)).isEqualTo("Working")
            LiveVoiceRuntime.onAgentEvent(TaskCompleted(session, 2, "task", "Result", outcome))
            LiveVoiceRuntime.onAgentEvent(SessionCompleted(session, 3, SessionEndReason.IDLE_TIMEOUT))
            assertThat(LiveVoiceRuntime.task.value).isEqualTo(expected)
            assertThat(voiceStatus(LiveVoiceState(), LiveVoiceRuntime.task.value)).isEqualTo(expected.label)
        }
    }

    @Test fun `active call status reflects audio and task independently`() {
        val active = LiveVoiceState(phase = LiveVoicePhase.Active)
        assertThat(voiceStatus(active, LiveTaskStatus.DONE)).isEqualTo("Listening")
        assertThat(voiceStatus(active, LiveTaskStatus.RUNNING)).isEqualTo("Working")
        assertThat(voiceStatus(active.copy(speaking = true), LiveTaskStatus.RUNNING)).isEqualTo("Replying")
        assertThat(voiceStatus(active.copy(muted = true), LiveTaskStatus.RUNNING)).isEqualTo("Microphone muted")
        assertThat(voiceStatus(active.copy(preview = true), LiveTaskStatus.RUNNING)).isEqualTo("Previewing")
    }

    @Test fun `service loss clears running status without overwriting a finished outcome`() {
        LiveVoiceRuntime.onAgentEvent(TaskStarted(session, 1, "task", "Open settings"))
        LiveVoiceRuntime.onAgentUnavailable()
        assertThat(LiveVoiceRuntime.task.value).isEqualTo(LiveTaskStatus.STOPPED)
        LiveVoiceRuntime.onAgentEvent(TaskCompleted(session, 2, "task", "Done", TaskOutcome.GOAL_ACHIEVED))
        LiveVoiceRuntime.onAgentUnavailable()
        assertThat(LiveVoiceRuntime.task.value).isEqualTo(LiveTaskStatus.DONE)
    }

    @Test fun `playback activity ignores silence and holds short gaps without storing audio`() {
        val playback = PlaybackActivity()
        assertThat(playback.accept(byteArrayOf(0, 0, 0), 0)).isNull()
        assertThat(playback.accept(byteArrayOf(0, 16), 100)).isTrue()
        assertThat(playback.accept(byteArrayOf(0, 0), 500)).isNull()
        assertThat(playback.accept(byteArrayOf(0, 0), 900)).isFalse()
        assertThat(playback.accept(byteArrayOf(0, -128), 1000)).isTrue()
    }
}
