package ai.closepaw.ui.overlay.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CapsuleRenderSpecTest {
    @Test fun `voice question shows the question without a keyboard or a send action`() {
        val mode = CapsuleMode.WaitingForInput("Which station?", "c1", byVoice = true)
        val spec = CapsuleRenderSpec.from(strings, mode)
        assertThat(spec.input).isNull()
        assertThat(spec.expandedBody).isEqualTo("Which station?")
        assertThat(spec.buttons.stop).isNotNull()
    }

    private val strings = ai.closepaw.ui.resourceLabels()


    @Test
    fun `running mode shows stopping disabled when stop pending`() {
        val spec = CapsuleRenderSpec.from(strings,
            mode = CapsuleMode.Running("thinking"),
            previousMode = null,
            isStopPending = true,
        )

        val stop = spec.buttons.stop
        assertThat(stop).isNotNull()
        assertThat(stop?.text).isEqualTo(ai.closepaw.R.string.ui_stopping.toString())
        assertThat(stop?.enabled).isFalse()
    }

    @Test
    fun `error mode keeps close action even when stop pending`() {
        val spec = CapsuleRenderSpec.from(strings,
            mode = CapsuleMode.Error("oops"),
            previousMode = null,
            isStopPending = true,
        )

        val stop = spec.buttons.stop
        assertThat(stop).isNotNull()
        assertThat(stop?.text).isEqualTo(ai.closepaw.R.string.ui_close.toString())
        assertThat(stop?.enabled).isTrue()
    }

    @Test
    fun `entering WaitingForInput from a different mode sets clearInput true`() {
        val spec = CapsuleRenderSpec.from(strings,
            mode = CapsuleMode.WaitingForInput("question?", callId = "c1"),
            previousMode = CapsuleMode.Running("thinking"),
        )
        assertThat(spec.input?.clearDraft).isTrue()
    }

    @Test
    fun `re-entering WaitingForInput from itself leaves clearInput false`() {
        val spec = CapsuleRenderSpec.from(strings,
            mode = CapsuleMode.WaitingForInput("another?", callId = "c2"),
            previousMode = CapsuleMode.WaitingForInput("first?", callId = "c1"),
        )
        assertThat(spec.input?.clearDraft).isFalse()
    }

    @Test
    fun `waiting for approval renders app-level three-button prompt`() {
        val spec = CapsuleRenderSpec.from(strings,
            mode = CapsuleMode.WaitingForApproval(
                callId = "approval-1",
                description = "Tap OK",
                appLabel = "Chrome",
                packageName = "com.android.chrome",
                reason = "Unknown app requires approval",
            )
        )

        assertThat(spec.thought.text).isEqualTo("${ai.closepaw.R.string.ui_allow_closepaw_to_operate}:Chrome")
        assertThat(spec.expandedBody).isNull()
        assertThat(spec.buttons.primary?.text).isEqualTo(ai.closepaw.R.string.ui_always.toString())
        assertThat(spec.buttons.secondary?.text).isEqualTo(ai.closepaw.R.string.ui_session.toString())
        assertThat(spec.buttons.stop?.text).isEqualTo(ai.closepaw.R.string.ui_reject.toString())
    }
}
