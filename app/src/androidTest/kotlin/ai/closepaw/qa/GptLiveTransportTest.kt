package ai.closepaw.qa

import ai.closepaw.app.MainActivity
import ai.closepaw.voice.WebRtcLiveTransport
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

/** Native media/data-channel smoke test. All audio stays on the device; no account is used. */
@RunWith(AndroidJUnit4::class)
class GptLiveTransportTest {
    @Test fun nativeCallConnectsExchangesEventsAndCanRestart() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO").close()
        ActivityScenario.launch(MainActivity::class.java).use {
            repeat(2) {
                withTimeout(25_000) { callOnce(context) }
            }
        }
    }

    @Test fun previewNegotiatesReceiveOnlyAndDoesNotStartMicrophone() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(25_000) { callOnce(context, captureMicrophone = false) }
        }
    }

    private suspend fun callOnce(context: android.content.Context, captureMicrophone: Boolean = true) {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val audio = JavaAudioDeviceModule.builder(context).createAudioDeviceModule()
        val remoteFactory = try { PeerConnectionFactory.builder().setAudioDeviceModule(audio).createPeerConnectionFactory() }
            finally { audio.release() }
        val gathered = CompletableDeferred<Unit>()
        val candidateReady = CompletableDeferred<Unit>()
        val remoteEvents = Channel<String>(8)
        val localEvents = Channel<String>(8)
        val remoteChannelReady = CompletableDeferred<DataChannel>()
        var remoteChannel: DataChannel? = null
        val remote = remoteFactory.createPeerConnection(PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
            }
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate != null) candidateReady.complete(Unit)
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
            override fun onDataChannel(channel: DataChannel) {
                remoteChannel = channel
                channel.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(previousAmount: Long) {}
                    override fun onStateChange() {
                        if (channel.state() == DataChannel.State.OPEN) remoteChannelReady.complete(channel)
                    }
                    override fun onMessage(buffer: DataChannel.Buffer) {
                        val bytes = ByteArray(buffer.data.remaining()); buffer.data.get(bytes)
                        remoteEvents.trySend(bytes.toString(Charsets.UTF_8))
                    }
                })
                if (channel.state() == DataChannel.State.OPEN) remoteChannelReady.complete(channel)
            }
        })!!
        if (!captureMicrophone) {
            // Model the server's sending transceiver without a local capture track.
            remote.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY))
        }
        val transport = WebRtcLiveTransport(context, createCall = { offer, _ ->
            if (!captureMicrophone) {
                assertTrue(offer.contains("a=recvonly"))
                assertFalse(offer.contains("a=sendrecv"))
            }
            setSdp(remote, SessionDescription(SessionDescription.Type.OFFER, offer), false)
            val answer = suspendCancellableCoroutine<SessionDescription> { continuation ->
                remote.createAnswer(object : SdpAdapter() {
                    override fun onCreateSuccess(sdp: SessionDescription) { if (continuation.isActive) continuation.resume(sdp) }
                    override fun onCreateFailure(error: String?) { if (continuation.isActive) continuation.resumeWithException(AssertionError(error)) }
                }, MediaConstraints())
            }
            setSdp(remote, answer, true)
            gathered.await()
            // A cold Android network monitor can report COMPLETE before discovering
            // its first interface. This test has no trickle-ICE signaling, so its
            // single answer must include a usable candidate.
            candidateReady.await()
            remote.localDescription.description.also { assertTrue(it.contains("a=candidate:")) }
        }, onEvent = { localEvents.trySend(it) }, onDisconnected = {}, captureMicrophone = captureMicrophone)
        try {
            transport.connect("local-test")
            val channel = remoteChannelReady.await()
            val event = """{"type":"input_transcript.added","item":{"text":"Open Settings"}}"""
            assertTrue(channel.send(DataChannel.Buffer(ByteBuffer.wrap(event.toByteArray()), false)))
            assertEquals(event, localEvents.receive())
            val feedback = """{"type":"session.context.append","content":[{"type":"input_text","text":"Done"}]}"""
            assertTrue(transport.send(feedback))
            assertEquals(feedback, remoteEvents.receive())
            transport.mute(true)
            transport.mute(false)
            if (!captureMicrophone) {
                assertTrue(context.getSystemService(android.media.AudioManager::class.java).activeRecordingConfigurations.isEmpty())
            }
        } finally {
            withContext(NonCancellable) {
                transport.close()
                assertFalse(transport.send("{}"))
                transport.close()
                remoteChannel?.unregisterObserver(); remoteChannel?.close(); remoteChannel?.dispose()
                remote.close(); remote.dispose(); remoteFactory.dispose()
                remoteEvents.close(); localEvents.close()
            }
        }
    }

    private suspend fun setSdp(peer: PeerConnection, sdp: SessionDescription, local: Boolean) =
        suspendCancellableCoroutine<Unit> { continuation ->
            val observer = object : SdpAdapter() {
                override fun onSetSuccess() { if (continuation.isActive) continuation.resume(Unit) }
                override fun onSetFailure(error: String?) { if (continuation.isActive) continuation.resumeWithException(AssertionError(error)) }
            }
            if (local) peer.setLocalDescription(observer, sdp) else peer.setRemoteDescription(observer, sdp)
        }

    private open class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
}
