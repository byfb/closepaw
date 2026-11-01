package ai.closepaw.qa

import ai.closepaw.app.AuthStoreHolder
import ai.closepaw.app.MainActivity
import ai.closepaw.llm.LLMProvider
import ai.closepaw.voice.LiveCallClient
import ai.closepaw.voice.LiveConversation
import ai.closepaw.voice.LiveLanguage
import ai.closepaw.voice.LiveVoice
import ai.closepaw.voice.LiveVoicePreferences
import ai.closepaw.voice.WebRtcLiveTransport
import android.util.Log
import android.media.AudioManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in integration check using an existing device login; no microphone input is captured. */
@RunWith(AndroidJUnit4::class)
class GptLiveRemoteTest {
    @Test fun codexVoicesConnectAndPlayRemoteSpeech() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_remote") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val auth = withContext(Dispatchers.IO) { AuthStoreHolder.get(context) }
        assertTrue("An existing ChatGPT login is required", withContext(Dispatchers.IO) { auth.has(LLMProvider.OPENAI_CODEX) })
        val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                for (voice in listOf(LiveVoice.COVE, LiveVoice.JUNIPER)) withTimeout(45_000) {
                    val events = Channel<JsonObject>(128)
                    val audioPlayed = CompletableDeferred<Unit>()
                    val call = LiveCallClient(ai.closepaw.ui.theme.appResources(context), http, headers = {
                        withContext(Dispatchers.IO) { auth.codexHeaders(LLMProvider.OPENAI_CODEX) }
                    }, preferences = LiveVoicePreferences(voice, LiveLanguage.ENGLISH), preview = true)
                    val transport = WebRtcLiveTransport(context, call::create,
                        onEvent = { raw ->
                            (runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull())?.let {
                                Log.i("GptLiveRemoteTest", "${voice.id}: event=${it["type"]?.jsonPrimitive?.contentOrNull}")
                                events.trySend(it)
                            }
                        },
                        onDisconnected = { events.close(AssertionError("Remote voice disconnected")) },
                        captureMicrophone = false,
                        onPlaybackChanged = { speaking -> if (speaking) audioPlayed.complete(Unit) },
                    )
                    try {
                        transport.connect(UUID.randomUUID().toString())
                        awaitEvent(events, "session.started")
                        assertTrue("Preview must not open an Android recording stream",
                            context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
                        for (message in LiveConversation().append(null, "Say this sample once: Connection verified.", spoken = true)) {
                            assertTrue("The voice event channel must accept the sample", transport.send(message))
                        }
                        withTimeout(20_000) {
                            var response: JsonObject
                            do {
                                response = awaitEvent(events, "turn.done")
                            } while ((response["turn"] as? JsonObject)?.get("role")?.jsonPrimitive?.contentOrNull != "assistant")
                            val transcript = (response["turn"] as? JsonObject)?.get("transcript")?.jsonPrimitive?.contentOrNull
                            assertTrue("The server must produce a spoken response transcript", !transcript.isNullOrBlank())
                            audioPlayed.await()
                            assertTrue("Preview must not record during playback",
                                context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
                        }
                        Log.i("GptLiveRemoteTest", "${voice.id}: session started, transcript received, playback PCM active")
                    } finally {
                        withContext(NonCancellable) {
                            transport.send("""{"type":"session.close"}""")
                            transport.close()
                            events.close()
                        }
                    }
                }
            }
        } finally {
            http.dispatcher.cancelAll()
            http.connectionPool.evictAll()
        }
    }

    private suspend fun awaitEvent(events: Channel<JsonObject>, expected: String): JsonObject {
        while (true) {
            val event = events.receive()
            val type = event["type"]?.jsonPrimitive?.contentOrNull
            assertTrue("The voice service returned an error", type != "error")
            if (type == expected) return event
        }
    }
}
