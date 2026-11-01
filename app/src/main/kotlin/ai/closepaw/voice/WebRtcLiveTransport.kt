package ai.closepaw.voice

import ai.closepaw.R

import android.content.Context
import android.util.Log
import ai.closepaw.BuildConfig
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

/** One microphone-only call. Native resources are created and released off the UI thread. */
internal class WebRtcLiveTransport(
    private val context: Context,
    private val createCall: suspend (offer: String, sessionId: String) -> String,
    private val onEvent: (String) -> Unit,
    private val onDisconnected: (String) -> Unit,
    private val captureMicrophone: Boolean = true,
    private val onPlaybackChanged: (Boolean) -> Unit = {},
    private val onConnecting: (String) -> Unit = {},
    private val onMicrophoneActivity: (Boolean) -> Unit = {},
    private val initialMuted: Boolean = false,
) {
    private val strings get() = ai.closepaw.ui.theme.appResources(context)
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var source: AudioSource? = null
    private var track: AudioTrack? = null
    private var channel: DataChannel? = null
    private val channelReady = CompletableDeferred<Unit>()
    private val iceGathered = CompletableDeferred<Unit>()
    private val candidateReady = CompletableDeferred<Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var closed = false
    private val playback = PlaybackActivity()
    private val microphoneActivity = MicrophoneActivity()
    private val recovery = LiveConnectionRecovery(scope, onFailure = { message ->
        if (!closed) {
            channelReady.completeExceptionally(LiveVoiceException(message))
            onDisconnected(message)
        }
    })

    suspend fun connect(sessionId: String) = withContext(Dispatchers.Default) {
        withTimeout(65_000) {
            onConnecting(strings.getString(R.string.ui_preparing_audio))
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
            )
            val audio = JavaAudioDeviceModule.builder(context)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .setSamplesReadyCallback { samples ->
                    if (!closed && captureMicrophone && samples.audioFormat == android.media.AudioFormat.ENCODING_PCM_16BIT) {
                        microphoneActivity.accept(samples.data, android.os.SystemClock.elapsedRealtime())?.let(onMicrophoneActivity)
                    }
                }
                .setPlaybackSamplesReadyCallback { samples ->
                    if (!closed && samples.audioFormat == android.media.AudioFormat.ENCODING_PCM_16BIT) {
                        playback.accept(samples.data, android.os.SystemClock.elapsedRealtime())?.let(onPlaybackChanged)
                    }
                }
                .apply {
                    if (!captureMicrophone) setAudioBufferCallback { _, _, _, _, _, _ ->
                        // Without AudioRecord.read(), the SDK requires us to pace its
                        // zero-filled 10 ms buffers on the dedicated audio thread.
                        android.os.SystemClock.sleep(10)
                        System.nanoTime()
                    }
                }
                .createAudioDeviceModule().apply {
                    // An audio transceiver can start the ADM even without a microphone track.
                    // Preview uses the SDK's synthetic silence path and never opens AudioRecord.
                    setAudioRecordEnabled(captureMicrophone)
                }
            factory = try {
                PeerConnectionFactory.builder().setAudioDeviceModule(audio).createPeerConnectionFactory()
            } finally { audio.release() }
            val observer = object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    if (closed) return
                    if (BuildConfig.DEBUG) Log.d("GptLiveTransport", "ICE state: $state")
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> recovery.connected()
                        PeerConnection.IceConnectionState.DISCONNECTED -> recovery.disconnected()
                        PeerConnection.IceConnectionState.FAILED -> {
                            recovery.failed(strings.getString(R.string.ui_live_audio_connect_error))
                        }
                        PeerConnection.IceConnectionState.CLOSED -> recovery.failed(strings.getString(R.string.ui_voice_audio_connection_closed_tap_to_reconnect))
                        else -> Unit
                    }
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                    if (state == PeerConnection.IceGatheringState.COMPLETE) iceGathered.complete(Unit)
                }
                override fun onIceCandidate(candidate: IceCandidate?) {
                    if (candidate != null) candidateReady.complete(Unit)
                }
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(channel: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                    (receiver?.track() as? AudioTrack)?.setEnabled(true)
                }
            }
            val configuration = PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }
            val connection = factory!!.createPeerConnection(configuration, observer)
                ?: throw LiveVoiceException(strings.getString(R.string.ui_this_device_could_not_create_an_audio_connection))
            peer = connection
            if (captureMicrophone) {
                source = factory!!.createAudioSource(MediaConstraints())
                track = factory!!.createAudioTrack("closepaw-microphone", source).also {
                    it.setEnabled(false)
                    connection.addTrack(it, listOf("closepaw-audio"))
                }
            } else {
                // Codex v3 does not play speakable context for a recvonly offer. Negotiate
                // duplex media without a capture track; AudioRecord remains disabled above.
                connection.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV))
            }
            channel = connection.createDataChannel("oai-events", DataChannel.Init()).also { events ->
                events.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(previousAmount: Long) {}
                    override fun onStateChange() {
                        if (closed) return
                        if (events.state() == DataChannel.State.OPEN) channelReady.complete(Unit)
                        if (events.state() == DataChannel.State.CLOSED) recovery.failed(strings.getString(R.string.ui_gpt_live_s_event_connection_closed_tap_to_reconnect))
                    }
                    override fun onMessage(buffer: DataChannel.Buffer) {
                        if (closed || buffer.binary || buffer.data.remaining() > 256_000) return
                        val bytes = ByteArray(buffer.data.remaining())
                        buffer.data.get(bytes)
                        onEvent(bytes.toString(Charsets.UTF_8))
                    }
                })
            }
            val offer = createOffer(connection)
            setDescription(connection, offer, local = true)
            onConnecting(strings.getString(R.string.ui_finding_a_network_route))
            // This endpoint has no trickle-ICE exchange. Send the updated local SDP,
            // including candidates, rather than the original candidate-free offer.
            // Android's cold network monitor can report COMPLETE before its first candidate.
            val gathered = withTimeoutOrNull(8_000) {
                iceGathered.await()
                candidateReady.await()
                true
            } == true
            if (!gathered) throw LiveVoiceException(strings.getString(R.string.ui_live_route_missing))
            val localOffer = connection.localDescription?.description
                ?: throw LiveVoiceException(strings.getString(R.string.ui_could_not_prepare_the_voice_network_connection))
            onConnecting(strings.getString(R.string.ui_starting_gpt_live))
            val answer = createCall(localOffer, sessionId)
            if (BuildConfig.DEBUG) {
                val candidates = answer.lineSequence().filter { it.startsWith("a=candidate:") }.map {
                    val parts = it.split(' ')
                    "${parts.getOrNull(2)}:${parts.getOrNull(5)}:${parts.getOrNull(7)}"
                }.toList()
                Log.d("GptLiveTransport", "Remote candidates (protocol:port:type): $candidates; iceLite=${answer.contains("a=ice-lite")}")
            }
            setDescription(connection, SessionDescription(SessionDescription.Type.ANSWER, answer), local = false)
            onConnecting(strings.getString(R.string.ui_connecting_audio))
            if (withTimeoutOrNull(20_000) { channelReady.await(); true } != true) {
                throw LiveVoiceException(strings.getString(R.string.ui_live_media_handshake_error))
            }
            track?.setEnabled(!initialMuted)
        }
    }

    fun mute(muted: Boolean) { if (!closed) track?.setEnabled(!muted) }

    fun send(event: String): Boolean = !closed && channel?.let {
        it.state() == DataChannel.State.OPEN && it.bufferedAmount() < 256_000 &&
            it.send(DataChannel.Buffer(ByteBuffer.wrap(event.toByteArray(Charsets.UTF_8)), false))
    } == true

    suspend fun close() = withContext(Dispatchers.Default) {
        closed = true
        recovery.close()
        scope.cancel()
        track?.setEnabled(false)
        channel?.unregisterObserver()
        channel?.close()
        channel?.dispose(); channel = null
        peer?.close()
        peer?.dispose(); peer = null
        track?.dispose(); track = null
        source?.dispose(); source = null
        factory?.dispose(); factory = null
    }

    private suspend fun createOffer(connection: PeerConnection): SessionDescription =
        suspendCancellableCoroutine { continuation ->
            connection.createOffer(object : SdpAdapter() {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    if (continuation.isActive) continuation.resume(sdp)
                }
                override fun onCreateFailure(error: String?) {
                    if (continuation.isActive) continuation.resumeWithException(LiveVoiceException(strings.getString(R.string.ui_could_not_prepare_voice_audio)))
                }
            }, MediaConstraints())
        }

    private suspend fun setDescription(connection: PeerConnection, sdp: SessionDescription, local: Boolean) =
        suspendCancellableCoroutine<Unit> { continuation ->
            val observer = object : SdpAdapter() {
                override fun onSetSuccess() { if (continuation.isActive) continuation.resume(Unit) }
                override fun onSetFailure(error: String?) {
                    if (continuation.isActive) continuation.resumeWithException(LiveVoiceException(strings.getString(R.string.ui_could_not_negotiate_voice_audio)))
                }
            }
            if (local) connection.setLocalDescription(observer, sdp) else connection.setRemoteDescription(observer, sdp)
        }

    private open class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
}
