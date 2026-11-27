package ai.closepaw.voice

import ai.closepaw.R
import ai.closepaw.app.AgentService
import ai.closepaw.app.AuthStoreHolder
import ai.closepaw.app.MainActivity
import ai.closepaw.llm.LLMProvider
import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** RemoteViews is sufficient for a single action and a short event-driven status. */
class GptLiveWidget : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH || intent.action == Intent.ACTION_LOCALE_CHANGED ||
            intent.action == "android.intent.action.APPLICATION_LOCALE_CHANGED") refresh(context)
        else super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    private fun refresh(context: Context) {
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try { withTimeoutOrNull(8000) { runCatching { updateAll(context.applicationContext) } } }
            finally { pending.finish(); scope.cancel() }
        }
    }

    companion object {
        const val EXTRA_OPEN_SETTINGS = "open_gpt_live_settings"
        private const val ACTION_REFRESH = "ai.closepaw.voice.WIDGET_REFRESH"
        private val updateMutex = Mutex()

        fun requestUpdate(context: Context) {
            context.sendBroadcast(Intent(context, GptLiveWidget::class.java).setAction(ACTION_REFRESH))
        }

        fun settingsIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_SETTINGS, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        suspend fun updateAll(context: Context) = withContext(Dispatchers.IO) { updateMutex.withLock {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, GptLiveWidget::class.java))
            if (ids.isEmpty()) return@withContext
            val ready = AgentService.instance != null &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                runCatching { AuthStoreHolder.get(context).has(LLMProvider.OPENAI_CODEX) }.getOrDefault(false)
            manager.updateAppWidget(ids, views(context, LiveVoiceRuntime.state.value, ready))
        } }

        internal fun views(context: Context, voice: LiveVoiceState, setupReady: Boolean): RemoteViews {
            val strings = ai.closepaw.ui.theme.appResources(context)
            val enabled = setupReady && voice.canReceiveSpeech
            val statusText = strings.getString(if (enabled) R.string.ui_enabled else R.string.gpt_live_widget_preview)
            val unmute = setupReady && voice.phase == LiveVoicePhase.Active && voice.muted && !voice.preview && !voice.audioInterrupted
            val label = strings.getString(when {
                unmute -> R.string.ui_unmute_gpt_live
                voice.running -> if (voice.preview) R.string.ui_stop_preview else R.string.ui_end_voice
                !setupReady -> R.string.ui_set_up_voice
                else -> R.string.ui_start_voice
            })
            val action = when {
                unmute -> PendingIntent.getService(context, 7307,
                    Intent(context, GptLiveService::class.java).setAction(GptLiveService.ACTION_MUTE), PendingIntent.FLAG_IMMUTABLE)
                voice.running -> PendingIntent.getService(context, 7305,
                    Intent(context, GptLiveService::class.java).setAction(GptLiveService.ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
                !setupReady -> PendingIntent.getActivity(context, 7303, settingsIntent(context),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                else -> PendingIntent.getForegroundService(context, 7306,
                    Intent(context, GptLiveService::class.java).setAction(GptLiveService.ACTION_START), PendingIntent.FLAG_IMMUTABLE)
            }
            val button = android.R.id.background
            return RemoteViews(context.packageName, R.layout.gpt_live_widget).apply {
                setTextViewText(button, statusText)
                setTextColor(button, context.getColor(if (enabled) R.color.live_dark_ink else android.R.color.white))
                setColorStateList(button, "setBackgroundTintList", if (enabled) R.color.live_listening else R.color.live_ready)
                setContentDescription(button, strings.getString(R.string.gpt_live_widget_accessibility, statusText, label))
                setOnClickPendingIntent(button, action)
            }
        }
    }
}
