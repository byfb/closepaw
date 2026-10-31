package ai.closepaw.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** ICE DISCONNECTED is recoverable, for example while a phone changes networks. */
internal class LiveConnectionRecovery(
    private val scope: CoroutineScope,
    private val onFailure: (String) -> Unit,
    private val graceMs: Long = 12_000,
) {
    private var timeout: Job? = null
    private var finished = false

    @Synchronized fun disconnected() {
        if (finished || timeout != null) return
        timeout = scope.launch {
            delay(graceMs)
            failed("Voice lost its network connection. Check Wi-Fi, mobile data or VPN, then reconnect.")
        }
    }

    @Synchronized fun connected() {
        timeout?.cancel()
        timeout = null
    }

    @Synchronized fun failed(message: String) {
        if (finished) return
        finished = true
        timeout?.cancel()
        timeout = null
        onFailure(message)
    }

    @Synchronized fun close() {
        finished = true
        timeout?.cancel()
        timeout = null
    }
}
