package ai.closepaw.qa

import ai.closepaw.R
import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanguageResourcesTest {
    @Test fun regionalSystemLanguagesResolveTheCorrectScriptAndKeepDynamicText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for ((tag, expected) in listOf("en" to "Settings", "zh-HK" to "設定", "zh-TW" to "設定", "zh-CN" to "设置")) {
            val configuration = Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(tag))
            }
            val strings = context.createConfigurationContext(configuration).resources
            assertEquals(tag, expected, strings.getString(R.string.ui_settings))
            if (tag != "en") {
                val prompt = if (tag == "zh-CN") "允许 ClosePaw 操作「Maps」吗？" else "允許 ClosePaw 操作「Maps」嗎？"
                assertEquals(tag, prompt, strings.getString(R.string.ui_allow_closepaw_to_operate, "Maps"))
            }
        }
    }
}
