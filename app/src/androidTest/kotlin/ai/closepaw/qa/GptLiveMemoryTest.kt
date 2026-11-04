package ai.closepaw.qa

import ai.closepaw.app.AuthStoreHolder
import ai.closepaw.memory.MemoryRecaller
import ai.closepaw.memory.MemoryScope
import ai.closepaw.memory.MemoryStore
import ai.closepaw.tool.AppClassifier
import ai.closepaw.tool.ToolExecutionResult
import ai.closepaw.tool.impl.EndGptLiveCallTool
import ai.closepaw.tool.impl.RememberExperienceTool
import ai.closepaw.voice.LiveCallToolSelector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GptLiveMemoryTest {
    /** Uses the signed-in model and real tool without touching the user's memory directory. */
    @Test fun live_remembers_corrects_recalls_and_forgets() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_remote") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "live-memory-qa-${System.nanoTime()}")
        val store = MemoryStore(dir)
        val remember = RememberExperienceTool(store, AppClassifier(emptyMap()))
        val coreRecaller = MemoryRecaller(MemoryStore(dir))
        val auth = withContext(Dispatchers.IO) { AuthStoreHolder.get(context) }
        val selector = LiveCallToolSelector.create(context, auth, EndGptLiveCallTool { error("must stay connected") }, remember)
        try {
            suspend fun say(text: String, operation: String?) {
                val selection = withTimeout(12_000) { selector.selectTools(text, coreRecaller.recall(null)) }
                assertNull("Memory speech must not hang up", selection.endCall)
                if (operation == null) assertTrue(selection.memoryUpdates.isEmpty())
                else {
                    assertTrue("Pure memory speech should consume its handoff", selection.memoryHandlesTurn)
                    assertTrue("Expected memory function", selection.memoryUpdates.isNotEmpty())
                    assertTrue(selection.memoryUpdates.any { it.optString("operation", "append") == operation })
                    for (args in selection.memoryUpdates) assertTrue(remember.invoke(args, "ai.closepaw") is ToolExecutionResult.Success)
                }
            }
            say("請記住，我最喜歡的飲品是烏龍茶。", "append")
            assertTrue(coreRecaller.recall(null).orEmpty().contains("烏龍茶"))
            say("更正我剛才的飲品偏好，我最喜歡的是黑咖啡，不是烏龍茶。請更新記憶。", "replace")
            assertTrue(coreRecaller.recall(null).orEmpty().contains("黑咖啡"))
            assertFalse(coreRecaller.recall(null).orEmpty().contains("烏龍茶"))
            say("你記得我最喜歡哪種飲品嗎？", null)
            say("請忘記我喜歡甚麼飲品這條記憶。", "delete")
            assertFalse(store.read(MemoryScope.USER).orEmpty().contains("黑咖啡"))
            val mixed = withTimeout(12_000) { selector.selectTools("請記住我偏好繁體中文，然後幫我打開手機設定。", coreRecaller.recall(null)) }
            assertTrue(mixed.memoryUpdates.isNotEmpty())
            assertFalse("Mixed request must still reach the phone worker", mixed.memoryHandlesTurn)
        } finally {
            selector.close()
            withContext(Dispatchers.IO) { dir.deleteRecursively() }
        }
    }
}
