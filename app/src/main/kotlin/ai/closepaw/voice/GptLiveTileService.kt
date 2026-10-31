package ai.closepaw.voice

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class GptLiveTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        observer?.cancel()
        observer = scope.launch {
            LiveVoiceRuntime.state.collectLatest { voice ->
                qsTile?.apply {
                    state = if (voice.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                    label = "GPT-Live"
                    subtitle = if (voice.running) "End voice" else "Start voice"
                    contentDescription = "GPT-Live: $subtitle"
                    updateTile()
                }
            }
        }
    }

    override fun onStopListening() { observer?.cancel(); observer = null; super.onStopListening() }

    // The PendingIntent overload exists only on Android 14+. Older devices need Intent.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        if (LiveVoiceRuntime.state.value.running) {
            startService(Intent(this, GptLiveService::class.java).setAction(GptLiveService.ACTION_STOP))
        } else unlockAndRun {
            val intent = GptLiveShortcutActivity.startIntent(this)
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(
                this, 7310, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
