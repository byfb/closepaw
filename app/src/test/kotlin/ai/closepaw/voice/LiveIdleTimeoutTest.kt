package ai.closepaw.voice

import ai.closepaw.voice.LiveIdleTimeout.Action.*
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class LiveIdleTimeoutTest {
    private fun event(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test fun `fresh silent call reminds once at forty seconds and ends at sixty`() {
        val idle = LiveIdleTimeout()
        assertThat(idle.poll(100_000)).isEqualTo(NONE)
        idle.start(1000)
        assertThat(idle.poll(40_999)).isEqualTo(NONE)
        assertThat(idle.poll(41_000)).isEqualTo(REMIND)
        assertThat(idle.poll(45_000)).isEqualTo(NONE)
        assertThat(idle.poll(60_999)).isEqualTo(NONE)
        assertThat(idle.poll(61_000)).isEqualTo(END)
    }

    @Test fun `reminder playback and transcript do not extend the countdown`() {
        val idle = LiveIdleTimeout().apply { start(0) }
        assertThat(idle.poll(40_000)).isEqualTo(REMIND)
        idle.playback(true, 41_000)
        idle.event(event("""{"type":"output_transcript.added","item":{"text":"I will hang up shortly"}}"""), 42_000)
        idle.playback(false, 45_000)
        idle.event(event("""{"type":"turn.done","turn":{"role":"assistant","transcript":"I will hang up shortly"}}"""), 46_000)
        assertThat(idle.poll(60_000)).isEqualTo(END)
    }

    @Test fun `user speaking before the deadline cancels hangup and restarts from the reply`() {
        val idle = LiveIdleTimeout().apply { start(0) }
        idle.poll(40_000)
        idle.microphone(true, 59_000)
        assertThat(idle.poll(65_000)).isEqualTo(NONE)
        idle.microphone(false, 66_000)
        idle.event(event("""{"type":"turn.done","turn":{"role":"user","transcript":"I have another question"}}"""), 67_000)
        idle.playback(true, 68_000)
        assertThat(idle.poll(120_000)).isEqualTo(NONE)
        idle.playback(false, 121_000)
        assertThat(idle.poll(160_999)).isEqualTo(NONE)
        assertThat(idle.poll(161_000)).isEqualTo(REMIND)
    }

    @Test fun `partial speech resets even when audio detection is unavailable`() {
        val idle = LiveIdleTimeout().apply { start(0) }
        idle.poll(40_000)
        idle.event(event("""{"type":"input_transcript.added","item":{"text":"等等"}}"""), 59_000)
        assertThat(idle.poll(60_000)).isEqualTo(NONE)
        assertThat(idle.poll(99_000)).isEqualTo(REMIND)
    }

    @Test fun `phone work or audio interruption pauses idle and reconnect starts fresh`() {
        val idle = LiveIdleTimeout().apply { start(0) }
        idle.poll(40_000)
        assertThat(idle.poll(200_000, busy = true)).isEqualTo(NONE)
        assertThat(idle.poll(239_999)).isEqualTo(NONE)
        assertThat(idle.poll(240_000)).isEqualTo(REMIND)
        idle.start(250_000)
        assertThat(idle.poll(260_000)).isEqualTo(NONE)
        assertThat(idle.poll(290_000)).isEqualTo(REMIND)
    }

    @Test fun `delayed timer gives a full grace period after the reminder`() {
        val idle = LiveIdleTimeout().apply { start(0) }
        assertThat(idle.poll(70_000)).isEqualTo(REMIND)
        assertThat(idle.poll(89_999)).isEqualTo(NONE)
        assertThat(idle.poll(90_000)).isEqualTo(END)
    }

    @Test fun `initial reminder uses app language until the user establishes one`() {
        val preferences = LiveVoicePreferences()
        assertThat(preferences.idleReminderInstructions("Reminder", false, "en")).contains("Speak English")
        assertThat(preferences.idleReminderInstructions("提醒", false, "zh")).contains("Speak Mandarin Chinese")
        assertThat(preferences.idleReminderInstructions("Reminder", true, "en")).contains("most recent utterance")
        assertThat(preferences.copy(language = LiveLanguage.CANTONESE)
            .idleReminderInstructions("Reminder", false, "en")).contains("Speak Hong Kong Cantonese")
    }
}
