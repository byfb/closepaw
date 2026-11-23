package ai.closepaw.qa

import ai.closepaw.R
import ai.closepaw.voice.GptLiveWidget
import ai.closepaw.voice.LiveStatus
import ai.closepaw.voice.LiveTaskStatus
import ai.closepaw.voice.LiveVoicePhase
import ai.closepaw.voice.LiveVoiceState
import android.appwidget.AppWidgetHostView
import android.content.res.Configuration
import android.view.View.MeasureSpec
import android.widget.Button
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GptLiveWidgetTest {
    @Test fun oneAccessibleButtonFillsTheWidgetAtBothSizesAndLargeFontScale() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        instrumentation.runOnMainSync {
            val config = Configuration(target.resources.configuration).apply { fontScale = 2f }
            val context = target.createConfigurationContext(config)
            val active = LiveVoiceState(phase = LiveVoicePhase.Active)
            data class Case(val voice: LiveVoiceState, val task: LiveTaskStatus, val status: LiveStatus, val action: Int)
            val cases = listOf(
                Case(LiveVoiceState(), LiveTaskStatus.NONE, LiveStatus.READY, R.string.ui_start_voice),
                Case(active, LiveTaskStatus.NONE, LiveStatus.LISTENING, R.string.ui_end_voice),
                Case(active.copy(phase = LiveVoicePhase.Connecting), LiveTaskStatus.NONE, LiveStatus.CONNECTING, R.string.ui_end_voice),
                Case(active.copy(muted = true), LiveTaskStatus.NONE, LiveStatus.MUTED, R.string.ui_end_voice),
                Case(active.copy(audioInterrupted = true), LiveTaskStatus.NONE, LiveStatus.INTERRUPTED, R.string.ui_end_voice),
                Case(active, LiveTaskStatus.RUNNING, LiveStatus.WORKING, R.string.ui_end_voice),
                Case(active, LiveTaskStatus.WAITING, LiveStatus.WAITING, R.string.ui_end_voice),
                Case(active, LiveTaskStatus.WAITING_FOR_VOICE, LiveStatus.YOUR_TURN, R.string.ui_end_voice),
                Case(active.copy(speaking = true), LiveTaskStatus.RUNNING, LiveStatus.REPLYING, R.string.ui_end_voice),
                Case(active.copy(preview = true), LiveTaskStatus.NONE, LiveStatus.PREVIEW, R.string.ui_stop_preview),
                Case(LiveVoiceState(), LiveTaskStatus.WAITING_FOR_VOICE, LiveStatus.YOUR_TURN, R.string.ui_answer_by_voice),
                Case(LiveVoiceState(), LiveTaskStatus.NONE, LiveStatus.SETUP, R.string.ui_set_up_voice),
                Case(LiveVoiceState(phase = LiveVoicePhase.Error), LiveTaskStatus.DONE, LiveStatus.DISCONNECTED, R.string.ui_retry),
                Case(LiveVoiceState(), LiveTaskStatus.DONE, LiveStatus.DONE, R.string.ui_start_voice),
                Case(LiveVoiceState(), LiveTaskStatus.FAILED, LiveStatus.FAILED, R.string.ui_start_voice),
                Case(LiveVoiceState(), LiveTaskStatus.STOPPED, LiveStatus.STOPPED, R.string.ui_start_voice),
            )
            for ((voice, task, status, action) in cases) {
                val setup = context.getString(R.string.ui_allow_microphone_access).takeIf { status == LiveStatus.SETUP }
                val button = GptLiveWidget.views(context, voice, task, setup).apply(context, AppWidgetHostView(context)) as Button
                val label = setup ?: context.getString(status.label)
                assertTrue(button.hasOnClickListeners())
                assertEquals(context.getString(R.string.gpt_live_widget_accessibility, label, context.getString(action)), button.contentDescription)
                assertEquals(context.getColor(status.background), button.backgroundTintList?.defaultColor)
                for (height in listOf(110, 180)) {
                    fun px(dp: Int) = (context.resources.displayMetrics.density * dp).roundToInt()
                    button.measure(MeasureSpec.makeMeasureSpec(px(180), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(px(height), MeasureSpec.EXACTLY))
                    button.layout(0, 0, button.measuredWidth, button.measuredHeight)
                    assertEquals(0, button.layout.getEllipsisCount(button.lineCount - 1))
                    assertTrue("Clipped ${button.text} at ${height}dp",
                        button.layout.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
                }
            }
            for (status in LiveStatus.entries) {
                assertTrue(status.name, ColorUtils.calculateContrast(context.getColor(status.ink), context.getColor(status.background)) >= 7.0)
            }
        }
    }
}
