package ai.closepaw.voice

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
) {
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
    private val recovery = LiveConnectionRecovery(scope, onFailure = { message ->
        if (!closed) {
            channelReady.completeExceptionally(LiveVoiceException(message))
            onDisconnected(message)
        }
    })

    suspend fun connect(sessionId: String) = withContext(Dispatchers.Default) {
        withTimeout(65_000) {
            onConnecting("Preparing audio…")
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
            )
            val audio = JavaAudioDeviceModule.builder(context)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .setPlaybackSamplesReadyCallback { samples ->
                    if (!closed && samples.audioFormat == android.media.AudioFormat.ENCODING_PCM_16BIT) {
                        playback.accept(samples.data, android.os.SystemClock.elapsedRealtime())?.let(onPlaybackChanged)
                    }
                }
                .createAudioDeviceModule()
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
                            recovery.failed("Voice audio could not connect. Check your network or VPN, then reconnect.")
                        }
                        PeerConnection.IceConnectionState.CLOSED -> recovery.failed("Voice audio connection closed. Tap to reconnect.")
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
                ?: throw LiveVoiceException("This device could not create an audio connection.")
            peer = connection
            if (captureMicrophone) {
                source = factory!!.createAudioSource(MediaConstraints())
                track = factory!!.createAudioTrack("closepaw-microphone", source).also {
                    it.setEnabled(false)
                    connection.addTrack(it, listOf("closepaw-audio"))
                }
            } else {
                connection.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
            }
            channel = connection.createDataChannel("oai-events", DataChannel.Init()).also { events ->
                events.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(previousAmount: Long) {}
                    override fun onStateChange() {
                        if (closed) return
                        if (events.state() == DataChannel.State.OPEN) channelReady.complete(Unit)
                        if (events.state() == DataChannel.State.CLOSED) recovery.failed("GPT-Live's event connection closed. Tap to reconnect.")
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
            onConnecting("Finding a network route…")
            // This endpoint has no trickle-ICE exchange. Send the updated local SDP,
            // including candidates, rather than the original candidate-free offer.
            // Android's cold network monitor can report COMPLETE before its first candidate.
            val gathered = withTimeoutOrNull(8_000) {
                iceGathered.await()
                candidateReady.await()
                true
            } == true
            if (!gathered) throw LiveVoiceException("Could not find a voice network route. Check your connection and reconnect.")
            val localOffer = connection.localDescription?.description
                ?: throw LiveVoiceException("Could not prepare the voice network connection.")
            onConnecting("Starting GPT-Live…")
            val answer = createCall(localOffer, sessionId)
            if (BuildConfig.DEBUG) {
                val candidates = answer.lineSequence().filter { it.startsWith("a=candidate:") }.map {
                    val parts = it.split(' ')
                    "${parts.getOrNull(2)}:${parts.getOrNull(5)}:${parts.getOrNull(7)}"
                }.toList()
                Log.d("GptLiveTransport", "Remote candidates (protocol:port:type): $candidates; iceLite=${answer.contains("a=ice-lite")}")
            }
            setDescription(connection, SessionDescription(SessionDescription.Type.ANSWER, answer), local = false)
            onConnecting("Connecting audio…")
            if (withTimeoutOrNull(20_000) { channelReady.await(); true } != true) {
                throw LiveVoiceException("GPT-Live accepted the call, but audio could not connect. Check your network or VPN, then reconnect.")
            }
            track?.setEnabled(true)
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
                    if (continuation.isActive) continuation.resumeWithException(LiveVoiceException("Could not prepare voice audio."))
                }
            }, MediaConstraints())
        }

    private suspend fun setDescription(connection: PeerConnection, sdp: SessionDescription, local: Boolean) =
        suspendCancellableCoroutine<Unit> { continuation ->
            val observer = object : SdpAdapter() {
                override fun onSetSuccess() { if (continuation.isActive) continuation.resume(Unit) }
                override fun onSetFailure(error: String?) {
                    if (continuation.isActive) continuation.resumeWithException(LiveVoiceException("Could not negotiate voice audio."))
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
