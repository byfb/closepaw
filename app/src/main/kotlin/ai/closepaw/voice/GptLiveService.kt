package ai.closepaw.voice

import ai.closepaw.app.AgentService
import ai.closepaw.app.AuthStoreHolder
import ai.closepaw.app.MainActivity
import ai.closepaw.llm.LLMProvider
import ai.closepaw.protocol.*
import ai.closepaw.session.AgentSession
import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient

/** Started by a visible Activity or an explicit home-widget tap. */
class GptLiveService : Service() {
    companion object {
        const val ACTION_START = "ai.closepaw.voice.START"
        const val ACTION_STOP = "ai.closepaw.voice.STOP"
        const val ACTION_PREVIEW = "ai.closepaw.voice.PREVIEW"
        const val ACTION_STOP_PREVIEW = "ai.closepaw.voice.STOP_PREVIEW"
        const val ACTION_MUTE = "ai.closepaw.voice.MUTE"
        private const val CHANNEL = "gpt_live"
        private const val NOTIFICATION = 7301
    }

    inner class LocalBinder : Binder() { val service: GptLiveService get() = this@GptLiveService }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = LiveVoiceRuntime.mutableState
    val state: StateFlow<LiveVoiceState> = mutableState.asStateFlow()
    private var callJob: Job? = null
    private var pendingStart: Boolean? = null
    private var transport: WebRtcLiveTransport? = null
    private var agentObserver: Job? = null
    private var observedSession: AgentSession? = null
    private var pendingQuestion: AskUser? = null
    private var delegationId: String? = null
    private var conversation = LiveConversation()
    private var generation = 0
    private var focus: AudioFocusRequest? = null
    private var previousAudioMode: Int? = null
    private var previousAudioDevice: AudioDeviceInfo? = null
    private var recordingMonitor: AudioManager.AudioRecordingCallback? = null
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            combine(state, LiveVoiceRuntime.task) { voice, task ->
                // Captions never leave the app or trigger widget redraws.
                voice.copy(transcript = "") to task
            }.distinctUntilChanged().collect { (voice, _) ->
                runCatching { GptLiveWidget.updateAll(applicationContext) }
                if (voice.running) updateNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCall(preview = false)
            ACTION_PREVIEW -> startCall(preview = true)
            ACTION_STOP_PREVIEW -> if (state.value.preview || callJob == null) stopCall()
            ACTION_MUTE -> toggleMute()
            else -> stopCall()
        }
        return START_NOT_STICKY
    }

    private fun startCall(preview: Boolean) {
        if (callJob != null) {
            if (!state.value.running) pendingStart = preview
            return
        }
        if (!preview && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("Allow microphone access to start GPT-Live."); return
        }
        mutableState.value = LiveVoiceState(phase = LiveVoicePhase.Connecting, preview = preview)
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "GPT-Live", NotificationManager.IMPORTANCE_LOW)
            )
            ServiceCompat.startForeground(this, NOTIFICATION, notification(),
                if (preview) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (_: Exception) {
            fail("Open ClosePaw to start the microphone."); return
        }
        val run = ++generation
        AgentService.instance?.setLiveVoiceActive(!preview)
        conversation = LiveConversation()
        val delegations = Channel<LiveConversation.Delegation>(16)
        callJob = scope.launch {
            var connection: WebRtcLiveTransport? = null
            try {
                val auth = withContext(Dispatchers.IO) { AuthStoreHolder.get(applicationContext) }
                val signedIn = withContext(Dispatchers.IO) { auth.has(LLMProvider.OPENAI_CODEX) }
                if (!signedIn) throw LiveVoiceException("Sign in to ChatGPT in Settings to use GPT-Live.")
                if (!preview) {
                    val ready = withTimeoutOrNull(3000) {
                        while (AgentService.instance == null) delay(100)
                        true
                    } == true
                    if (!ready) throw LiveVoiceException("Enable ClosePaw Accessibility in Settings first.")
                    AgentService.instance?.setLiveVoiceActive(true)
                }
                val preferences = withContext(Dispatchers.IO) { ai.closepaw.app.AppSettingsStore(applicationContext).loadLiveVoice() }
                acquireAudio()
                if (!preview) {
                    recordingMonitor = object : AudioManager.AudioRecordingCallback() {
                        override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>?) {
                            if (configs?.any { it.isClientSilenced } == true) scope.launch {
                                if (run == generation && state.value.running) {
                                    fail("Android silenced the microphone. Check microphone access and other recording apps.")
                                }
                            }
                        }
                    }.also { audioManager.registerAudioRecordingCallback(it, android.os.Handler(mainLooper)) }
                }
                connection = WebRtcLiveTransport(applicationContext,
                    LiveCallClient(http, headers = { withContext(Dispatchers.IO) { auth.codexHeaders(LLMProvider.OPENAI_CODEX) } },
                        preferences = preferences, preview = preview)::create,
                    onEvent = { raw -> scope.launch {
                        if (run == generation && state.value.running) receive(raw, delegations)
                    } },
                    onDisconnected = { scope.launch {
                        if (run == generation && state.value.running) fail("GPT-Live disconnected. Tap to reconnect.")
                    } },
                    captureMicrophone = !preview,
                    onPlaybackChanged = { speaking -> scope.launch {
                        if (run == generation && state.value.running) mutableState.value = state.value.copy(speaking = speaking)
                    } },
                )
                transport = connection
                connection.connect(UUID.randomUUID().toString())
                mutableState.value = state.value.copy(phase = LiveVoicePhase.Active)
                updateNotification()
                if (preview) {
                    append(null, "Say this sample once: ${preferences.language.sample}", spoken = true)
                }
                coroutineScope {
                    if (!preview) launch { for (request in delegations) delegate(request) }
                    // A transcript is not proof that audio has finished playing.
                    // Previews have an explicit Stop control and a bounded playback window.
                    if (preview) launch { delay(15_000); stopCall() }
                    launch {
                        while (isActive) {
                            delay(2000)
                            if (!preview && AgentService.instance == null) throw LiveVoiceException("Accessibility disconnected. Start GPT-Live again after reconnecting.")
                            if (!withContext(Dispatchers.IO) { auth.has(LLMProvider.OPENAI_CODEX) })
                                throw LiveVoiceException("ChatGPT signed out. Sign in again to use GPT-Live.")
                        }
                    }
                    awaitCancellation()
                }
            } catch (e: TimeoutCancellationException) {
                fail("GPT-Live connection timed out. Try again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail((e as? LiveVoiceException)?.message ?: "GPT-Live could not connect. Check ChatGPT sign-in and try again.")
            } finally {
                generation++
                delegations.cancel()
                agentObserver?.cancel(); agentObserver = null
                observedSession = null; pendingQuestion = null; delegationId = null
                transport = null
                try {
                    withContext(NonCancellable) {
                        connection?.send("""{"type":"session.close"}""")
                        connection?.close()
                    }
                } finally {
                    releaseAudio()
                    AgentService.instance?.setLiveVoiceActive(false)
                    conversation = LiveConversation()
                    if (state.value.running) mutableState.value = LiveVoiceState()
                    GptLiveWidget.requestUpdate(applicationContext)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    callJob = null
                    val restart = pendingStart
                    pendingStart = null
                    if (restart != null && scope.isActive) startCall(restart) else stopSelf()
                }
            }
        }
    }

    fun toggleMute() {
        if (state.value.phase != LiveVoicePhase.Active || state.value.preview) return
        val muted = !state.value.muted
        transport?.mute(muted)
        mutableState.value = state.value.copy(muted = muted)
        updateNotification()
    }

    fun stopCall() {
        pendingStart = null
        mutableState.value = LiveVoiceState()
        generation++
        transport?.mute(true)
        callJob?.cancel()
        if (callJob == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }

    private fun fail(message: String) {
        pendingStart = null
        mutableState.value = LiveVoiceState(phase = LiveVoicePhase.Error, error = message)
        GptLiveWidget.requestUpdate(applicationContext)
        transport?.mute(true)
        callJob?.cancel()
        if (callJob == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }

    private fun receive(raw: String, requests: Channel<LiveConversation.Delegation>) {
        val event = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return
        when (event.string("type")) {
            "error" -> { fail("GPT-Live reported a session error. End the call and try again."); return }
            "session.closed", "session.ended" -> { fail("GPT-Live session ended. Tap to reconnect."); return }
        }
        val request = conversation.receive(raw)
        mutableState.value = state.value.copy(transcript = conversation.transcript)
        // Preview never reaches the worker, even if the server emits a delegation.
        if (state.value.preview) return
        if (request != null && !requests.trySend(request).isSuccess)
            append(request.id, "The phone agent is busy. Please wait and repeat the request.", spoken = true)
    }

    private suspend fun delegate(request: LiveConversation.Delegation) {
        if (request.text.isBlank()) {
            append(request.id, "The task arrived without its text. Ask the user to repeat the instruction.", true)
            return
        }
        try {
            val service = AgentService.instance ?: throw LiveVoiceException("Accessibility is disconnected.")
            val session = service.getOrCreateLiveSession()
            if (observedSession !== session) observe(session)
            delegationId = request.id
            val question = pendingQuestion?.takeIf { session.getServices().userResponseChannel.hasPending }
            if (question == null) pendingQuestion = null
            when {
                question != null -> {
                    session.submit(Op.UserResponse(question.callId, request.text))
                    pendingQuestion = null
                }
                session.state.value == SessionState.Running -> session.submit(Op.Supplement(request.text))
                session.state.value == SessionState.Paused || session.state.value == SessionState.TakeoverPending -> {
                    append(request.id, "The user has taken control. Ask them to tap Resume in ClosePaw before continuing.", true)
                    return
                }
                else -> session.submit(Op.UserInput(request.text))
            }
            append(request.id, "The phone agent received the request. Wait for its result before claiming completion.", false)
        } catch (e: TimeoutCancellationException) {
            append(request.id, "The phone task took too long to start. Please try again.", true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            append(request.id, (e as? LiveVoiceException)?.message ?: "The phone task could not start. Check ClosePaw settings.", true)
        }
    }

    private fun observe(session: AgentSession) {
        agentObserver?.cancel()
        observedSession = session
        pendingQuestion = null
        val replay = session.events.replayCache.size
        agentObserver = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.events.drop(replay).collect { event ->
                val id = delegationId ?: return@collect
                when (event) {
                    is ThoughtUpdate -> append(id, event.compact, false)
                    is AskUser -> { pendingQuestion = event; append(id, event.message, true) }
                    is ApprovalRequired -> append(id, "Approval needed in ClosePaw: ${event.description}. Ask the user to use the approval buttons.", true)
                    is TaskCompleted -> { pendingQuestion = null; append(id, "Task outcome: ${event.outcome}. ${event.result.orEmpty()}", true) }
                    is SessionError -> append(id, "The phone agent encountered an error: ${event.message}", true)
                    is SessionCompleted -> { pendingQuestion = null; append(id, "Phone agent session ended: ${event.reason}.", false) }
                    else -> Unit
                }
            }
        }
    }

    private fun append(id: String?, text: String, spoken: Boolean) {
        // Bound task context; no screenshots, accessibility trees, or full tool traces enter voice.
        for (event in conversation.append(id, text.take(1800), spoken)) {
            if (transport?.send(event) != true) {
                fail("GPT-Live could not receive the task update. Reconnect to continue.")
                break
            }
        }
    }

    private fun acquireAudio() {
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { change ->
                if (change < 0) scope.launch { fail("Voice ended because another app needs audio. Tap to reconnect.") }
            }.build()
        if (audioManager.requestAudioFocus(focus!!) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            throw LiveVoiceException("Microphone audio is in use by another app.")
        previousAudioMode = audioManager.mode
        previousAudioDevice = audioManager.communicationDevice
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        // Keep a connected headset. Use the speaker when the phone would choose the earpiece.
        if (previousAudioDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE || previousAudioDevice == null) {
            audioManager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { audioManager.setCommunicationDevice(it) }
        }
    }

    private fun releaseAudio() {
        recordingMonitor?.let { runCatching { audioManager.unregisterAudioRecordingCallback(it) } }
        recordingMonitor = null
        previousAudioMode?.let { mode ->
            runCatching {
                val device = previousAudioDevice?.takeIf { old -> audioManager.availableCommunicationDevices.any { it.id == old.id } }
                if (device != null) audioManager.setCommunicationDevice(device) else audioManager.clearCommunicationDevice()
            }
            runCatching { audioManager.mode = mode }
        }
        previousAudioDevice = null; previousAudioMode = null
        focus?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }; focus = null
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, GptLiveService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("GPT-Live").setContentText(voiceStatus(state.value, LiveVoiceRuntime.task.value))
            .setContentIntent(open).setOngoing(true).setSilent(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "End voice", stop).build()
    }

    private fun updateNotification() { getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification()) }

    override fun onDestroy() {
        generation++
        scope.cancel()
        http.dispatcher.cancelAll()
        http.connectionPool.evictAll()
        if (state.value.running) mutableState.value = LiveVoiceState()
        GptLiveWidget.requestUpdate(applicationContext)
        super.onDestroy()
    }
}
