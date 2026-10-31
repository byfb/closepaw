package ai.closepaw.qa

import ai.closepaw.ui.overlay.compose.FloatingSummaryCompose
import ai.closepaw.ui.overlay.model.CapsuleMode
import ai.closepaw.ui.theme.ClosePawTheme
import ai.closepaw.voice.LiveTaskStatus
import ai.closepaw.voice.LiveVoicePhase
import ai.closepaw.voice.LiveVoiceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FloatingSummaryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun progressAndCaptionsUpdateWithoutLosingControls() {
        val mode = mutableStateOf<CapsuleMode>(CapsuleMode.Running("Opening Chrome"))
        val voice = mutableStateOf(LiveVoiceState(phase = LiveVoicePhase.Active, transcript = "I am checking the page."))
        var opened = 0
        var minimized = 0
        var controls = 0
        var muted = 0
        var ended = 0
        compose.setContent {
            ClosePawTheme {
                FloatingSummaryCompose(mode.value, voice.value, LiveTaskStatus.RUNNING,
                    onMinimize = { minimized++ }, onOpenChat = { opened++ },
                    onOpenControls = { controls++ }, onMute = { muted++ }, onEndVoice = { ended++ })
            }
        }
        compose.onNodeWithText("Opening Chrome").assertIsDisplayed()
        compose.onNodeWithText("I am checking the page.").assertIsDisplayed()
        compose.onNodeWithText("Controls").performClick()
        compose.onNodeWithText("Open chat").performClick()
        compose.onNodeWithContentDescription("Mute GPT-Live").performClick()
        compose.runOnIdle {
            mode.value = CapsuleMode.Running("Reading the results")
            voice.value = voice.value.copy(muted = true, transcript = "The page has loaded.")
        }
        compose.onNodeWithText("Reading the results").assertIsDisplayed()
        compose.onNodeWithText("The page has loaded.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Unmute GPT-Live").assertIsDisplayed()
        compose.onNodeWithContentDescription("End GPT-Live").performClick()
        compose.onNodeWithContentDescription("Minimize summary").performClick()
        compose.runOnIdle {
            assertEquals(1, opened)
            assertEquals(1, minimized)
            assertEquals(1, controls)
            assertEquals(1, muted)
            assertEquals(1, ended)
        }
    }

    @Test fun textOnlyWorkDoesNotShowVoiceControls() {
        compose.setContent {
            ClosePawTheme {
                FloatingSummaryCompose(CapsuleMode.Running("Reading Chrome"), LiveVoiceState(),
                    LiveTaskStatus.RUNNING, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("Reading Chrome").assertIsDisplayed()
        compose.onNodeWithText("Controls").assertIsDisplayed()
        compose.onNodeWithContentDescription("Mute GPT-Live").assertDoesNotExist()
        compose.onNodeWithContentDescription("End GPT-Live").assertDoesNotExist()
    }

    @Test fun voiceFailureShowsItsReasonAndKeepsChatReachable() {
        var opened = 0
        compose.setContent {
            ClosePawTheme {
                FloatingSummaryCompose(CapsuleMode.Hidden,
                    LiveVoiceState(phase = LiveVoicePhase.Error, error = "Audio could not connect on this network."),
                    LiveTaskStatus.NONE, {}, { opened++ }, {}, {}, {})
            }
        }
        compose.onNodeWithText("Audio could not connect on this network.").assertIsDisplayed()
        compose.onNodeWithText("Controls").assertDoesNotExist()
        compose.onNodeWithText("Open chat").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }
}
