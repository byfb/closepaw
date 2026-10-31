package ai.closepaw.voice

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class LiveResponseWatchdogTest {
    private fun event(raw: String) = Json.parseToJsonElement(raw).jsonObject

    @Test fun `quiet listening and phone work do not time out`() {
        val watch = LiveResponseWatchdog()
        assertThat(watch.expired(3_600_000)).isFalse()
        watch.microphone(true, 0); watch.microphone(false, 1000)
        watch.event(event("""{"type":"delegation.created"}"""), 2000)
        assertThat(watch.expired(3_600_000)).isFalse()
    }

    @Test fun `speech without any server event has a bounded response wait`() {
        val watch = LiveResponseWatchdog()
        watch.microphone(true, 0)
        assertThat(watch.expired(60_000)).isFalse()
        watch.microphone(false, 60_000)
        assertThat(watch.expired(89_999)).isFalse()
        assertThat(watch.expired(90_000)).isTrue()
    }

    @Test fun `transcribed speech and absent reply also time out`() {
        val watch = LiveResponseWatchdog()
        watch.event(event("""{"type":"turn.done","turn":{"role":"user","transcript":"Hello"}}"""), 0)
        assertThat(watch.expired(30_000)).isTrue()
        watch.event(event("""{"type":"output_transcript.added","item":{"text":"Hi"}}"""), 30_001)
        assertThat(watch.expired(100_000)).isFalse()
    }

    @Test fun `playback and mute reset outstanding wait`() {
        val watch = LiveResponseWatchdog()
        watch.microphone(true, 0); watch.microphone(false, 1000)
        watch.playback()
        assertThat(watch.expired(100_000)).isFalse()
        watch.microphone(true, 100_000); watch.microphone(false, 101_000)
        watch.reset()
        assertThat(watch.expired(200_000)).isFalse()
    }

    @Test fun `short noise does not count as speech`() {
        val mic = MicrophoneActivity()
        val sound = byteArrayOf(0, 8).let { sample -> ByteArray(480) { sample[it % 2] } }
        assertThat(mic.accept(sound, 0)).isNull()
        assertThat(mic.accept(ByteArray(480), 1000)).isNull()
        assertThat(mic.accept(sound, 2000)).isNull()
        assertThat(mic.accept(sound, 2300)).isTrue()
        assertThat(mic.accept(ByteArray(480), 3200)).isFalse()
    }
}
