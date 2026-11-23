package ai.closepaw.voice

import ai.closepaw.R

import ai.closepaw.app.AgentService
import ai.closepaw.app.AuthStoreHolder
import ai.closepaw.app.MainActivity
import ai.closepaw.llm.LLMProvider
import ai.closepaw.protocol.*
import ai.closepaw.session.AgentSession
import ai.closepaw.tool.impl.EndGptLiveCallTool
import ai.closepaw.tool.impl.RememberExperienceTool
import ai.closepaw.tool.AppClassifierHolder
import ai.closepaw.tool.ToolExecutionResult
import ai.closepaw.tool.ToolName
import ai.closepaw.memory.MemoryStore
import ai.closepaw.memory.MemoryRecaller
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
    private var callControl: LiveCallControl? = null
    private data class PendingDelegation(
        val generation: Int, val request: LiveConversation.Delegation, val allowed: Deferred<Boolean>,
    )
    private var agentObserver: Job? = null
    private var observedSession: AgentSession? = null
    private val voiceQuestions = LiveVoiceQuestions(
        pendingPrompt = { observedSession?.getServices()?.userResponseChannel?.pendingPrompt },
        speak = { connectionReady && !state.value.preview && sendContext(delegationId, it, spoken = true) },
        submit = { prompt, answer -> observedSession?.submit(Op.UserResponse(prompt.callId, answer)) },
    )
    private var delegationId: String? = null
    private var conversation = LiveConversation()
    private var generation = 0
    private var connectionReady = false
    private var connectionFailure: CompletableDeferred<String>? = null
    private var responseWatchdog: LiveResponseWatchdog? = null
    private var idleTimeout: LiveIdleTimeout? = null
    private val pendingUpdates = ArrayDeque<Pair<String, Boolean>>()
    private var focus: AudioFocusRequest? = null
    private var previousAudioMode: Int? = null
    private var previousAudioDevice: AudioDeviceInfo? = null
    private data class ConversationMemory(val brief: String, val turns: List<Pair<String, String>>)
    private val contextStore by lazy { LiveContextStore(applicationContext) }
    private val memoryStore by lazy { MemoryStore(java.io.File(filesDir, "memory")) }
    private val memoryRecaller by lazy { MemoryRecaller(memoryStore) }
    private var contextEpoch: Long? = null
    private var contextUpdates: Channel<ConversationMemory>? = null
    private var audioGeneration = 0
    private var recordingMonitor: AudioManager.AudioRecordingCallback? = null
    private val strings get() = ai.closepaw.ui.theme.appResources(this)
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            combine(state, LiveVoiceRuntime.task) { voice, task ->
                // Captions never enter the notification or trigger widget redraws.
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
            ACTION_MUTE -> if (state.value.running) toggleMute() else stopSelf()
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
            fail(strings.getString(R.string.ui_allow_microphone_access_to_start_gpt_live)); return
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
            fail(strings.getString(R.string.ui_open_closepaw_to_start_the_microphone)); return
        }
        AgentService.instance?.setLiveVoiceActive(!preview)
        conversation = LiveConversation()
        pendingUpdates.clear()
        contextEpoch = null
        contextUpdates = if (preview) null else Channel(Channel.CONFLATED)
        val delegations = Channel<PendingDelegation>(16)
        callJob = scope.launch {
            val owner = coroutineContext.job
            var selector: LiveCallToolSelector? = null
            var contextWriter: Job? = null
            var memoryObserver: Job? = null
            var resumeContext = false
            try {
                val auth = withContext(Dispatchers.IO) { AuthStoreHolder.get(applicationContext) }
                if (!withContext(Dispatchers.IO) { auth.has(LLMProvider.OPENAI_CODEX) })
                    throw LiveVoiceException(strings.getString(R.string.ui_sign_in_to_chatgpt_in_settings_to_use_gpt_live))
                if (!preview) {
                    val tool = EndGptLiveCallTool { endVoiceFromTool(owner) }
                    val remember = withContext(Dispatchers.IO) {
                        RememberExperienceTool(memoryStore, AppClassifierHolder.get(applicationContext))
                    }
                    callControl = LiveCallControl(scope,
                        captureVoiceAnswer = voiceQuestions::captureAnswer,
                        selectTools = { utterance ->
                            val activeSelector = selector ?: LiveCallToolSelector.create(applicationContext, auth, tool, remember)
                                .also { selector = it }
                            activeSelector.selectTools(utterance, recallMemory())
                        },
                        executeEndCall = { arguments -> tool.invoke(arguments) },
                        executeMemory = { updates ->
                            var succeeded = true
                            for (arguments in updates) {
                                if (callJob !== owner || !state.value.running) { succeeded = false; break }
                                val result = remember.invoke(arguments, currentMemoryPackage())
                                if (result !is ToolExecutionResult.Success) succeeded = false
                                val message = when (result) {
                                    is ToolExecutionResult.Success -> result.output
                                    is ToolExecutionResult.Failure -> result.error
                                    is ToolExecutionResult.Cancelled -> result.reason
                                }
                                append(null, "Memory tool result: $message", false)
                            }
                            append(null, recallMemory(), false)
                            succeeded
                        },
                        unavailable = {
                            android.util.Log.w("GptLiveService", "Voice call control unavailable for this turn")
                            append(null, "Voice call control is unavailable for this turn. If the user asked to disconnect, " +
                                "tell them the call is still open and offer the End voice notification action. " +
                                "Memory updates were not confirmed; do not claim they were saved.", false)
                        },
                    )
                    val ready = withTimeoutOrNull(3000) {
                        while (AgentService.instance == null) delay(100)
                        true
                    } == true
                    if (!ready) throw LiveVoiceException(strings.getString(R.string.ui_enable_closepaw_accessibility_in_settings_first))
                    AgentService.instance?.setLiveVoiceActive(true)
                    val active = AgentService.instance?.getActiveSession()?.takeUnless { it.state.value == SessionState.Shutdown }
                    val saved = withContext(Dispatchers.IO) { contextStore.begin(active?.sessionId?.value) }
                    contextEpoch = saved.epoch
                    conversation.restore(saved.snapshot)
                    resumeContext = saved.snapshot.hasContext || active != null
                    if (active != null) {
                        observe(active)
                        withContext(Dispatchers.IO) { active.events.replayCache.forEach(contextStore::recordAgentEvent) }
                    }
                    val updates = contextUpdates!!
                    contextWriter = launch(Dispatchers.IO) {
                        for (memory in updates) contextStore.saveVoice(saved.epoch, memory.brief, memory.turns)
                    }
                    memoryObserver = launch {
                        memoryStore.revision.drop(1).collect {
                            if (connectionReady) append(null, recallMemory(), false)
                        }
                    }
                }
                val preferences = withContext(Dispatchers.IO) { ai.closepaw.app.AppSettingsStore(applicationContext).loadLiveVoice() }
                acquireAudio()
                if (!preview) {
                    recordingMonitor = object : AudioManager.AudioRecordingCallback() {
                        override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>?) {
                            if (configs?.any { it.isClientSilenced } == true) scope.launch {
                                if (state.value.running) fail(strings.getString(R.string.ui_live_microphone_silenced))
                            }
                        }
                    }.also { audioManager.registerAudioRecordingCallback(it, android.os.Handler(mainLooper)) }
                }
                coroutineScope {
                    if (!preview) launch {
                        AgentService.instance?.activeSession?.collect { session ->
                            if (session !== observedSession) observe(session)
                        }
                    }
                    if (!preview) launch {
                        for ((origin, request, allowed) in delegations) {
                            val proceed = withTimeoutOrNull(LiveCallControl.HANDOFF_TIMEOUT_MS) { allowed.await() }
                            if (origin == generation && state.value.running) {
                                if (proceed != false) delegate(request, origin)
                                else if (callControl?.handledMemory(allowed) == true)
                                    append(request.id, "The memory request has already completed successfully on the phone. " +
                                        "Confirm the result without repeating the operation.", true)
                            }
                        }
                    }
                    launch {
                        while (isActive) {
                            delay(2000)
                            if (!preview && AgentService.instance == null)
                                throw LiveVoiceException(strings.getString(R.string.ui_live_accessibility_disconnected))
                            if (!withContext(Dispatchers.IO) { auth.has(LLMProvider.OPENAI_CODEX) })
                                throw LiveVoiceException(strings.getString(R.string.ui_chatgpt_signed_out_sign_in_again_to_use_gpt_live))
                        }
                    }
                    var reconnects = 0
                    var everConnected = false
                    while (isActive) {
                        val run = ++generation
                        val restoredContext = if (reconnects > 0 || resumeContext) {
                            conversation.reconnectContext() // Reject replayed delegations before replacement-session callbacks.
                            val memory = ConversationMemory(conversation.initialBrief, conversation.historySnapshot())
                            val workerState = observedSession?.state?.value?.toString() ?: "No active worker; earlier task information is historical"
                            withContext(Dispatchers.IO) {
                                (contextStore.load() ?: LiveContextSnapshot()).copy(brief = memory.brief, turns = memory.turns).prompt(workerState)
                            }
                        } else null
                        val lost = CompletableDeferred<String>()
                        val sessionStarted = CompletableDeferred<Unit>()
                        val watchdog = LiveResponseWatchdog()
                        val idle = LiveIdleTimeout()
                        connectionFailure = lost
                        responseWatchdog = watchdog
                        idleTimeout = idle
                        val connection = WebRtcLiveTransport(applicationContext,
                            LiveCallClient(strings, http, headers = { withContext(Dispatchers.IO) { auth.codexHeaders(LLMProvider.OPENAI_CODEX) } },
                                preferences = preferences, preview = preview,
                                memoryContext = if (preview) null else recallMemory())::create,
                            onEvent = { raw -> scope.launch {
                                if (run == generation && state.value.running) {
                                    val event = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                                    if (event?.string("type") == "session.started") sessionStarted.complete(Unit)
                                    if (event != null) {
                                        val now = android.os.SystemClock.elapsedRealtime()
                                        watchdog.event(event, now)
                                        idle.event(event, now)
                                    }
                                    receive(raw, delegations)
                                }
                            } },
                            onDisconnected = { message -> scope.launch {
                                if (run == generation && state.value.running) lost.complete(message)
                            } },
                            captureMicrophone = !preview,
                            initialMuted = state.value.muted || state.value.audioInterrupted,
                            onPlaybackChanged = { speaking -> scope.launch {
                                if (run == generation && state.value.running) {
                                    if (speaking) {
                                        watchdog.playback()
                                        voiceQuestions.heardSpeech()
                                    }
                                    idle.playback(speaking, android.os.SystemClock.elapsedRealtime())
                                    mutableState.value = state.value.copy(speaking = speaking)
                                }
                            } },
                            onMicrophoneActivity = { active -> scope.launch {
                                if (run == generation && state.value.phase == LiveVoicePhase.Active && !preview) {
                                    if (!active || !state.value.muted && !state.value.audioInterrupted && !state.value.speaking) {
                                        val now = android.os.SystemClock.elapsedRealtime()
                                        watchdog.microphone(active, now)
                                        idle.microphone(active, now)
                                    }
                                }
                            } },
                            onConnecting = { stage -> scope.launch {
                                if (run == generation && state.value.phase == LiveVoicePhase.Connecting) {
                                    mutableState.value = state.value.copy(connectionStage =
                                        if (reconnects > 0) strings.getString(R.string.ui_live_reconnect_stage, reconnects, stage) else stage)
                                }
                            } },
                        )
                        transport = connection
                        val failure = try {
                            connection.connect(UUID.randomUUID().toString())
                            if (withTimeoutOrNull(10_000) { sessionStarted.await(); true } != true)
                                throw LiveVoiceException(strings.getString(R.string.ui_gpt_live_did_not_become_ready_reconnect_to_try_again))
                            everConnected = true
                            connectionReady = true
                            idle.start(android.os.SystemClock.elapsedRealtime())
                            mutableState.value = state.value.copy(phase = LiveVoicePhase.Active, connectionStage = null)
                            connection.mute(state.value.muted || state.value.audioInterrupted)
                            if (!preview) append(null, recallMemory(), false)
                            if (restoredContext != null) {
                                delegationId = null // IDs belong to the old remote session; worker execution continues.
                                append(null, restoredContext, false)
                            }
                            val updates = pendingUpdates.toList()
                            pendingUpdates.clear()
                            updates.forEach { (text, spoken) -> append(null, text, spoken) }
                            if (reconnects > 0 && !voiceQuestions.waiting) append(null,
                                "Tell the user voice has reconnected and previous conversation context is restored. Running phone tasks continue.", true)
                            voiceQuestions.announce()
                            updateNotification()
                            if (preview) {
                                append(null, "Say this sample once: ${preferences.language.sample}", true)
                                delay(15_000)
                                stopCall()
                                awaitCancellation()
                            }
                            while (!lost.isCompleted) {
                                delay(1000)
                                val now = android.os.SystemClock.elapsedRealtime()
                                if (!state.value.audioInterrupted) voiceQuestions.checkPlayback {
                                    lost.complete(strings.getString(R.string.ui_gpt_live_could_not_receive_the_task_update))
                                }
                                val workerBusy = observedSession?.state?.value == SessionState.Running &&
                                    !LiveVoiceRuntime.task.value.waiting
                                if (state.value.muted || state.value.audioInterrupted) idle.microphone(false, now)
                                when (idle.poll(now, busy = workerBusy || voiceQuestions.waiting || state.value.audioInterrupted)) {
                                    LiveIdleTimeout.Action.REMIND -> {
                                        android.util.Log.i("GptLiveService", "Idle voice reminder after 40 seconds")
                                        append(null, preferences.idleReminderInstructions(
                                            strings.getString(R.string.ui_live_idle_reminder),
                                            hasUserSpeech = conversation.historySnapshot().any { it.first == "user" },
                                            appLanguage = strings.configuration.locales[0].language,
                                        ), true)
                                    }
                                    LiveIdleTimeout.Action.END -> {
                                        android.util.Log.i("GptLiveService", "Ending idle voice call after 60 seconds")
                                        stopCall()
                                        awaitCancellation()
                                    }
                                    LiveIdleTimeout.Action.NONE -> Unit
                                }
                                if (state.value.muted || state.value.audioInterrupted) watchdog.reset()
                                else if (watchdog.expired(now))
                                    lost.complete(strings.getString(R.string.ui_gpt_live_stopped_responding_to_speech))
                            }
                            lost.await()
                        } catch (e: TimeoutCancellationException) {
                            strings.getString(R.string.ui_gpt_live_connection_timed_out)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            (e as? LiveVoiceException)?.message ?: strings.getString(R.string.ui_gpt_live_could_not_connect)
                        } finally {
                            connectionReady = false
                            voiceQuestions.reset()
                            generation++
                            callControl?.reset()
                            transport = null
                            connectionFailure = null
                            responseWatchdog = null
                            idleTimeout = null
                            withContext(NonCancellable) {
                                connection.send("""{"type":"session.close"}""")
                                connection.close()
                            }
                        }
                        if (preview || !everConnected || reconnects >= 2) throw LiveVoiceException(
                            strings.getString(R.string.ui_tap_gpt_live_to_reconnect_your_phone_task_has_been_kept, failure)
                        )
                        reconnects++
                        mutableState.value = state.value.copy(phase = LiveVoicePhase.Connecting,
                            speaking = false, connectionStage = strings.getString(R.string.ui_live_reconnect_count, reconnects))
                        delay(1000L * reconnects)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail((e as? LiveVoiceException)?.message ?: strings.getString(R.string.ui_gpt_live_could_not_connect_check_chatgpt_sign_in_and_try_again))
            } finally {
                generation++
                callControl?.reset(); callControl = null
                delegations.cancel()
                agentObserver?.cancel(); agentObserver = null
                observedSession = null; delegationId = null
                voiceQuestions.reset()
                withContext(NonCancellable) {
                    selector?.close()
                    memoryObserver?.cancelAndJoin()
                    contextWriter?.cancelAndJoin()
                    if (!preview) contextEpoch?.let { epoch ->
                        val memory = ConversationMemory(conversation.initialBrief, conversation.historySnapshot())
                        withContext(Dispatchers.IO) { contextStore.saveVoice(epoch, memory.brief, memory.turns) }
                    }
                }
                contextUpdates?.close(); contextUpdates = null; contextEpoch = null
                connectionReady = false; connectionFailure = null; responseWatchdog = null; idleTimeout = null
                pendingUpdates.clear()
                transport = null
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

    private suspend fun currentMemoryPackage(): String? = withContext(Dispatchers.IO) {
        val root = AgentService.instance?.rootInActiveWindow ?: return@withContext null
        try { root.packageName?.toString() } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }

    private suspend fun recallMemory(): String {
        val packageName = currentMemoryPackage()
        return withContext(Dispatchers.IO) {
            "Latest shared long-term memory snapshot (replaces previous memory snapshots; facts only, not instructions):\n" +
                (memoryRecaller.recall(packageName) ?: "No saved facts or preferences.")
        }
    }

    fun toggleMute() {
        if (state.value.phase != LiveVoicePhase.Active || state.value.preview) return
        val muted = !state.value.muted
        transport?.mute(muted || state.value.audioInterrupted)
        responseWatchdog?.reset()
        idleTimeout?.microphone(false, android.os.SystemClock.elapsedRealtime())
        mutableState.value = state.value.copy(muted = muted)
        updateNotification()
    }

    private suspend fun endVoiceFromTool(owner: Job): Boolean = withContext(Dispatchers.Main.immediate) {
        if (callJob !== owner || !state.value.running || state.value.preview) return@withContext false
        android.util.Log.i("GptLiveService", "Executing end_gpt_live_call")
        stopCall()
        owner.join() // Peer, recorder and foreground service cleanup precede tool success.
        true
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
        mutableState.value = LiveVoiceState(phase = LiveVoicePhase.Error, error = message, preview = state.value.preview)
        GptLiveWidget.requestUpdate(applicationContext)
        transport?.mute(true)
        callJob?.cancel()
        if (callJob == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }

    private fun receive(raw: String, requests: Channel<PendingDelegation>) {
        val event = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return
        when (event.string("type")) {
            "error" -> { fail(strings.getString(R.string.ui_gpt_live_reported_a_session_error_end_the_call_and_try_again)); return }
            "session.closed", "session.ended" -> { connectionFailure?.complete(strings.getString(R.string.ui_gpt_live_session_ended)); return }
        }
        val request = conversation.receive(raw)
        if (!state.value.preview && event.string("type") == "turn.done") {
            contextUpdates?.trySend(ConversationMemory(conversation.initialBrief, conversation.historySnapshot()))
        }
        mutableState.value = state.value.copy(transcript = conversation.transcript)
        // Preview never reaches the worker, even if the server emits a delegation.
        if (state.value.preview) return
        val control = callControl ?: return
        when (event.string("type")) {
            "input_transcript.added", "output_transcript.added" ->
                if (!event.obj("item")?.string("text").isNullOrBlank()) voiceQuestions.heardSpeech()
            "turn.done" -> if (!event.obj("turn")?.string("transcript").isNullOrBlank()) voiceQuestions.heardSpeech()
        }
        control.receive(event)
        if (voiceQuestions.waiting) return
        if (request != null && !requests.trySend(PendingDelegation(generation, request, control.handoffAllowed)).isSuccess)
            append(request.id, "The phone agent is busy. Please wait and repeat the request.", spoken = true)
    }

    private suspend fun delegate(request: LiveConversation.Delegation, origin: Int) {
        if (request.text.isBlank()) {
            append(request.id.takeIf { origin == generation && connectionReady }, "The task arrived without its text. Ask the user to repeat the instruction.", true)
            return
        }
        try {
            val service = AgentService.instance ?: throw LiveVoiceException("Accessibility is disconnected.")
            val session = service.getOrCreateLiveSession()
            if (observedSession !== session) observe(session)
            contextEpoch?.let { epoch -> withContext(Dispatchers.IO) { contextStore.attachChat(epoch, session.sessionId.value) } }
            if (origin != generation || !connectionReady || service.getActiveSession() !== session) return
            val prompt = session.getServices().userResponseChannel.pendingPrompt
            if (prompt?.type == AskUserType.VOICE_QUESTION) return
            delegationId = request.id
            when {
                prompt?.type == AskUserType.ACTION && session.getServices().toolRegistry.contains(ToolName.AskByVoice.raw) -> {
                    append(request.id, "The user must complete the physical step and tap Done in ClosePaw.", true)
                    return
                }
                prompt != null -> session.submit(Op.UserResponse(prompt.callId, request.text))
                session.state.value == SessionState.Running -> session.submit(Op.Supplement(request.text))
                session.state.value == SessionState.Paused || session.state.value == SessionState.TakeoverPending -> {
                    append(request.id.takeIf { origin == generation && connectionReady }, "The user has taken control. Ask them to tap Resume in ClosePaw before continuing.", true)
                    return
                }
                else -> session.submit(Op.UserInput(request.text))
            }
            append(request.id.takeIf { origin == generation && connectionReady }, "The phone agent received the request. Wait for its result before claiming completion.", false)
        } catch (e: TimeoutCancellationException) {
            append(request.id.takeIf { origin == generation && connectionReady }, "The phone task took too long to start. Please try again.", true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            append(request.id.takeIf { origin == generation && connectionReady }, (e as? LiveVoiceException)?.message ?: "The phone task could not start. Check ClosePaw settings.", true)
        }
    }

    private fun observe(session: AgentSession?) {
        agentObserver?.cancel()
        observedSession = session
        delegationId = null
        voiceQuestions.reset()
        if (session == null) return
        val replay = session.events.replayCache.size
        agentObserver = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.events.drop(replay).collect { event ->
                val id = delegationId
                when (event) {
                    is ThoughtUpdate -> append(id, event.compact, false)
                    is AskUser -> if (event.type == AskUserType.VOICE_QUESTION) voiceQuestions.announce()
                        else append(id, event.message, true)
                    is ApprovalRequired -> append(id, "Approval needed in ClosePaw: ${event.description}. Ask the user to use the approval buttons.", true)
                    is TaskCompleted -> append(id, "Task outcome: ${event.outcome}. ${event.result.orEmpty()}", true)
                    is SessionError -> append(id, "The phone agent encountered an error: ${event.message}", true)
                    is SessionCompleted -> append(id, "Phone agent session ended: ${event.reason}.", false)
                    else -> Unit
                }
            }
        }
        voiceQuestions.announce()
    }

    private fun append(id: String?, text: String, spoken: Boolean) {
        // Only bounded text enters voice; no screen captures or full tool traces.
        val bounded = text.take(if (id == null && !spoken) MemoryStore.DEFAULT_MAX_FILE_BYTES * 3 + 1024 else 1800)
        if (!connectionReady || !sendContext(id, bounded, spoken)) {
            pendingUpdates.addLast(bounded to spoken)
            while (pendingUpdates.size > 12) pendingUpdates.removeFirst()
        }
    }

    private fun sendContext(id: String?, text: String, spoken: Boolean): Boolean {
        for (event in conversation.append(id, text, spoken)) {
            if (transport?.send(event) != true) {
                connectionFailure?.complete(strings.getString(R.string.ui_gpt_live_could_not_receive_the_task_update))
                return false
            }
        }
        return true
    }

    private fun acquireAudio() {
        val audioRun = ++audioGeneration
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { change ->
                scope.launch {
                    if (audioRun != audioGeneration || !state.value.running) return@launch
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS -> fail(strings.getString(R.string.ui_voice_ended_because_another_app_needs_audio_tap_to_reconnect))
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            transport?.mute(true)
                            responseWatchdog?.reset()
                            mutableState.value = state.value.copy(audioInterrupted = true)
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            transport?.mute(state.value.muted)
                            mutableState.value = state.value.copy(audioInterrupted = false)
                        }
                    }
                }
            }.build()
        if (audioManager.requestAudioFocus(focus!!) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            throw LiveVoiceException(strings.getString(R.string.ui_microphone_audio_is_in_use_by_another_app))
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
        audioGeneration++
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
            .setContentTitle("GPT-Live").setContentText(voiceStatus(ai.closepaw.ui.theme.appResources(this), state.value, LiveVoiceRuntime.task.value))
            .setContentIntent(open).setOngoing(true).setSilent(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, strings.getString(R.string.ui_end_voice), stop).build()
    }

    private fun updateNotification() { getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification()) }

    override fun onDestroy() {
        generation++
        scope.cancel()
        // Closing an idle TLS socket can write a close alert. Keep that work off Main,
        // and capture only the client so cleanup does not retain the destroyed Service.
        val client = http
        client.dispatcher.executorService.execute {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
        if (state.value.running) mutableState.value = LiveVoiceState()
        GptLiveWidget.requestUpdate(applicationContext)
        super.onDestroy()
    }
}
