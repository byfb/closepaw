package ai.closepaw.qa

import ai.closepaw.R
import ai.closepaw.ui.chat.components.EmptyState
import ai.closepaw.ui.theme.ClosePawTheme
import ai.closepaw.ui.theme.appResources
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatEmptyStateTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val strings by lazy { appResources(compose.activity) }
    private fun expectedSuggestions() = listOf(
        strings.getString(R.string.ui_get_me) to strings.getString(R.string.ui_a_ride_home),
        strings.getString(R.string.ui_catch_me_up) to strings.getString(R.string.ui_on_emails_and_messages),
        strings.getString(R.string.ui_search) to strings.getString(R.string.ui_for_nearby_restaurants),
    )

    @Test fun shows_three_suggestion_chips() {
        compose.setContent { ClosePawTheme { EmptyState(onSuggestionClick = {}) } }

        for ((verb, gloss) in expectedSuggestions()) {
            compose.onNode(hasClickAction() and hasText(verb) and hasText(gloss)).assertIsDisplayed()
        }
        compose.onAllNodes(hasClickAction()).assertCountEquals(3)
    }

    @Test fun tapping_suggestion_fires_callback_with_text() {
        val requests = mutableListOf<String>()
        compose.setContent { ClosePawTheme { EmptyState(onSuggestionClick = { requests += it }) } }

        val suggestions = expectedSuggestions()
        for ((verb, _) in suggestions) {
            compose.onNode(hasClickAction() and hasText(verb)).performClick()
        }
        val expected = suggestions.map { (verb, gloss) -> strings.getString(R.string.suggested_request, verb, gloss) }
        compose.runOnIdle { assertEquals(expected, requests) }
    }
}
