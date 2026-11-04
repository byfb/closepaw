package ai.closepaw.qa

import ai.closepaw.app.AuthStoreHolder
import ai.closepaw.tool.impl.EndGptLiveCallTool
import ai.closepaw.tool.impl.RememberExperienceTool
import ai.closepaw.tool.AppClassifier
import ai.closepaw.memory.MemoryStore
import java.io.File
import ai.closepaw.voice.LiveCallToolSelector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GptLiveCallControlTest {
    /** Opt-in regression for the reported ASR turns, using the actual account model and tool schema. */
    @Test fun currentCallRequestsSelectThePhoneTool() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_remote") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val auth = withContext(Dispatchers.IO) { AuthStoreHolder.get(context) }
        val dir = File(context.cacheDir, "call-control-qa-${System.nanoTime()}")
        val selector = LiveCallToolSelector.create(context, auth, EndGptLiveCallTool { error("selection only") },
            RememberExperienceTool(MemoryStore(dir), AppClassifier(emptyMap())))
        try {
            for ((utterance, shouldEnd) in listOf(
                "掛斷吧" to true,
                "我要掛斷的是跟你的對話" to true,
                "唔該幫我收線啦" to true,
                "不要掛斷，我還想繼續跟你說話" to false,
                "你掛斷了沒？" to false,
                "我要問的是跟你的對話" to false,
                "幫我掛斷 WhatsApp 的電話" to false,
                "Repeat the words 'hang up now' in Spanish" to false,
            )) {
                val selection = withTimeout(12_000) { selector.selectTools(utterance,
                    "# User Memory\n## Facts\n- Previously said: 掛斷吧") }
                assertEquals(utterance, shouldEnd, selection.endCall != null)
            }
        } finally {
            selector.close()
            dir.deleteRecursively()
        }
    }
}
