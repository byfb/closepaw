package ai.closepaw.voice

import android.content.Context
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

/** One microphone-only call. Native resources are created and released off the UI thread. */
internal class WebRtcLiveTransport(
    private val context: Context,
    private val createCall: suspend (offer: String, sessionId: String) -> String,
    private val onEvent: (String) -> Unit,
    private val onDisconnected: () -> Unit,
    private val captureMicrophone: Boolean = true,
    private val onPlaybackChanged: (Boolean) -> Unit = {},
) {
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var source: AudioSource? = null
    private var track: AudioTrack? = null
    private var channel: DataChannel? = null
    private val channelReady = CompletableDeferred<Unit>()
    @Volatile private var closed = false
    private val playback = PlaybackActivity()

    suspend fun connect(sessionId: String) = withContext(Dispatchers.Default) {
        withTimeout(35_000) {
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
                    if (!closed && state in listOf(PeerConnection.IceConnectionState.FAILED,
                            PeerConnection.IceConnectionState.DISCONNECTED, PeerConnection.IceConnectionState.CLOSED)) {
                        onDisconnected()
                    }
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidate(candidate: IceCandidate?) {}
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
                        if (!closed && events.state() == DataChannel.State.CLOSED) onDisconnected()
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
            val answer = createCall(offer.description, sessionId)
            setDescription(connection, SessionDescription(SessionDescription.Type.ANSWER, answer), local = false)
            channelReady.await()
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
