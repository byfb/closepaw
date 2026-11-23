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
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
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
            val strings = ai.closepaw.ui.theme.appResources(context)
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, GptLiveWidget::class.java))
            if (ids.isEmpty()) return@withContext
            val setup = when {
                !runCatching { AuthStoreHolder.get(context).has(LLMProvider.OPENAI_CODEX) }.getOrDefault(false) -> strings.getString(R.string.ui_sign_in_to_chatgpt)
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> strings.getString(R.string.ui_allow_microphone_access)
                AgentService.instance == null -> strings.getString(R.string.ui_enable_accessibility)
                else -> null
            }
            manager.updateAppWidget(ids, views(context, LiveVoiceRuntime.state.value, LiveVoiceRuntime.task.value, setup))
        } }

        internal fun views(context: Context, voice: LiveVoiceState, task: LiveTaskStatus, setup: String?): RemoteViews {
            val strings = ai.closepaw.ui.theme.appResources(context)
            val needsSetup = !voice.running && setup != null
            val status = if (needsSetup) LiveStatus.SETUP else liveStatus(voice, task)
            val statusText = if (needsSetup) requireNotNull(setup) else strings.getString(status.label)
            val label = strings.getString(when {
                voice.running -> if (voice.preview) R.string.ui_stop_preview else R.string.ui_end_voice
                needsSetup -> R.string.ui_set_up_voice
                task == LiveTaskStatus.WAITING_FOR_VOICE -> R.string.ui_answer_by_voice
                status == LiveStatus.DISCONNECTED -> R.string.ui_retry
                else -> R.string.ui_start_voice
            })
            val action = when {
                voice.running -> PendingIntent.getService(context, 7305,
                    Intent(context, GptLiveService::class.java).setAction(GptLiveService.ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
                needsSetup -> PendingIntent.getActivity(context, 7303, settingsIntent(context),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                else -> PendingIntent.getForegroundService(context, 7306,
                    Intent(context, GptLiveService::class.java).setAction(GptLiveService.ACTION_START), PendingIntent.FLAG_IMMUTABLE)
            }
            val button = android.R.id.background
            return RemoteViews(context.packageName, R.layout.gpt_live_widget).apply {
                val text = if (needsSetup) label else SpannableStringBuilder(statusText).append('\n')
                    .append(label, RelativeSizeSpan(0.78f), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setTextViewText(button, text)
                setTextColor(button, context.getColor(status.ink))
                setColorStateList(button, "setBackgroundTintList", status.background)
                setContentDescription(button, strings.getString(R.string.gpt_live_widget_accessibility, statusText, label))
                setOnClickPendingIntent(button, action)
            }
        }
    }
}
