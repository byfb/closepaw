package ai.closepaw.voice

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.*
import org.junit.Test

class LiveVoiceCommandsTest {
    private fun turn(text: String, role: String = "user", type: String = "turn.done") = buildJsonObject {
        put("type", type)
        putJsonObject("turn") { put("role", role); put("transcript", text) }
    }

    @Test fun `explicit completed hangup commands work across supported languages`() {
        listOf("掛斷 GPT-Live。", "請結束語音", "结束通话", "收線啦！", "Please hang up.", "End the call, please.")
            .forEach { assertThat(LiveVoiceCommands.isHangup(turn(it))).isTrue() }
    }

    @Test fun `negations questions quotations partials and assistant speech cannot hang up`() {
        listOf("不要掛斷", "Don't hang up", "How do I hang up?", "Can GPT-Live hang up?", "He said hang up", "Stop the phone task")
            .forEach { assertThat(LiveVoiceCommands.isHangup(turn(it))).isFalse() }
        assertThat(LiveVoiceCommands.isHangup(turn("hang up", role = "assistant"))).isFalse()
        assertThat(LiveVoiceCommands.isHangup(turn("hang up", type = "input_transcript.added"))).isFalse()
    }
}
