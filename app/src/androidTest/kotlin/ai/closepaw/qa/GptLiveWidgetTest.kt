package ai.closepaw.qa

import ai.closepaw.R
import ai.closepaw.voice.GptLiveWidget
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
            data class Case(val voice: LiveVoiceState, val setupReady: Boolean, val enabled: Boolean, val action: Int)
            val cases = listOf(
                Case(LiveVoiceState(), true, false, R.string.ui_start_voice),
                Case(active, true, true, R.string.ui_end_voice),
                Case(active.copy(speaking = true), true, true, R.string.ui_end_voice),
                Case(active.copy(phase = LiveVoicePhase.Connecting), true, false, R.string.ui_end_voice),
                Case(active.copy(muted = true), true, false, R.string.ui_unmute_gpt_live),
                Case(active.copy(audioInterrupted = true), true, false, R.string.ui_end_voice),
                Case(active.copy(preview = true), true, false, R.string.ui_stop_preview),
                Case(active, false, false, R.string.ui_end_voice),
                Case(LiveVoiceState(), false, false, R.string.ui_set_up_voice),
                Case(LiveVoiceState(phase = LiveVoicePhase.Error), true, false, R.string.ui_start_voice),
            )
            for ((voice, setupReady, enabled, action) in cases) {
                val button = GptLiveWidget.views(context, voice, setupReady).apply(context, AppWidgetHostView(context)) as Button
                val label = context.getString(if (enabled) R.string.ui_enabled else R.string.gpt_live_widget_preview)
                assertEquals(label, button.text.toString())
                assertTrue(button.hasOnClickListeners())
                assertEquals(context.getString(R.string.gpt_live_widget_accessibility, label, context.getString(action)), button.contentDescription)
                val background = context.getColor(if (enabled) R.color.live_listening else R.color.live_ready)
                assertEquals(background, button.backgroundTintList?.defaultColor)
                assertTrue(ColorUtils.calculateContrast(button.currentTextColor, background) >= 7.0)
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
        }
    }
}
