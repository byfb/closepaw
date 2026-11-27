package ai.closepaw.session

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select

/** A session can serve consecutive tasks from different entry points. */
class TaskQuestionRouting(
    private val screenEnabled: () -> Boolean = { true },
    private val screenChanges: Flow<Boolean> = MutableStateFlow(true),
) {
    @Volatile var fromLiveVoice: Boolean = false
    val handsFree: Boolean get() = fromLiveVoice && !screenEnabled()

    /** Cancel the outstanding screen wait if the user switches this voice task to spoken input. */
    suspend fun <T : Any> onScreen(block: suspend () -> T): T? {
        if (!fromLiveVoice) return block()
        if (handsFree) return null
        return coroutineScope {
            val switch = async(start = CoroutineStart.UNDISPATCHED) { screenChanges.first { !it } }
            val screen = async { if (handsFree) null else block() }
            try {
                select {
                    switch.onAwait { null }
                    screen.onAwait { it }
                }
            } finally {
                switch.cancel()
                screen.cancel()
            }
        }
    }
}
