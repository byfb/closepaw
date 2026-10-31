package ai.closepaw.voice

import ai.closepaw.auth.CodexHeaders
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test

class LiveCallClientTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient.Builder().followRedirects(false).build()
    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { http.dispatcher.cancelAll(); server.shutdown() }
    private fun client(headers: suspend () -> CodexHeaders = { CodexHeaders("phone-token", "phone-account", null) }) =
        LiveCallClient(http, headers, server.url("/backend-api/codex/realtime/calls?intent=quicksilver&architecture=avas").toString())

    @Test fun `call carries phone ChatGPT auth and client delegation configuration`() = runBlocking {
        server.enqueue(MockResponse().setBody("v=0\r\nanswer"))
        assertThat(client().create("v=0\r\noffer", "voice-session")).startsWith("v=0")
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer phone-token")
        assertThat(request.getHeader("ChatGPT-Account-ID")).isEqualTo("phone-account")
        assertThat(request.getHeader("OpenAI-Alpha")).isEqualTo("quicksilver=v2")
        assertThat(request.path).contains("intent=quicksilver&architecture=avas")
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertThat(body.string("sdp")).isEqualTo("v=0\r\noffer")
        assertThat(body.obj("session")?.string("model")).isEqualTo("gpt-live-1-codex")
        assertThat(body.obj("session")?.obj("audio")?.obj("output")?.string("voice")).isEqualTo("cove")
        assertThat(body.obj("session")?.obj("delegation")?.string("type")).isEqualTo("client")
        assertThat(body.toString()).doesNotContain("phone-token")
    }

    @Test fun `reconnect obtains current credentials instead of caching bearer`() = runBlocking {
        var token = "first"
        val client = client { CodexHeaders(token, "account", null) }
        repeat(2) { server.enqueue(MockResponse().setBody("v=0")) }
        client.create("v=0", "one"); token = "refreshed"; client.create("v=0", "two")
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer first")
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer refreshed")
    }

    @Test fun `voice preferences configure the call without changing delegation or worker instructions`() = runBlocking {
        server.enqueue(MockResponse().setBody("v=0"))
        LiveCallClient(http, { CodexHeaders("token", "account", null) }, server.url("/calls").toString(),
            LiveVoicePreferences(LiveVoice.JUNIPER, LiveLanguage.CANTONESE, LiveTone.CALM, "Use short sentences.")).create("v=0", "voice")
        val session = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.obj("session")!!
        assertThat(session.obj("audio")?.obj("output")?.string("voice")).isEqualTo("juniper")
        assertThat(session.string("instructions")).contains("Hong Kong Cantonese")
        assertThat(session.string("instructions")).contains("calm, steady")
        assertThat(session.string("instructions")).contains("Use short sentences.")
        assertThat(session.string("instructions")).contains("Do not claim an action succeeded")
        assertThat(session.obj("delegation")?.string("type")).isEqualTo("client")
    }

    @Test fun `preview asks for a sample without phone operation instructions`() = runBlocking {
        server.enqueue(MockResponse().setBody("v=0"))
        LiveCallClient(http, { CodexHeaders("token", "account", null) }, server.url("/calls").toString(),
            LiveVoicePreferences(language = LiveLanguage.MANDARIN, tone = LiveTone.LIVELY), preview = true)
            .create("v=0", "preview")
        val session = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.obj("session")!!
        assertThat(session.string("instructions")).contains("Do not delegate or perform tasks")
        assertThat(session.string("instructions")).doesNotContain("run Android accessibility tools")
        assertThat(session.string("instructions")).contains("Mandarin Chinese")
    }

    @Test fun `account access rejection is actionable and does not expose response body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("private phone-token backend trace"))
        val error = runCatching { client().create("v=0", "voice") }.exceptionOrNull()!!
        assertThat(error.message).contains("not available")
        assertThat(error.message).doesNotContain("phone-token")
        assertThat(error.message).doesNotContain("backend trace")
    }

    @Test fun `unexpected successful response does not masquerade as a valid voice answer`() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>private backend response</html>"))
        val error = runCatching { client().create("v=0", "voice") }.exceptionOrNull()!!
        assertThat(error.message).contains("invalid audio connection")
        assertThat(error.message).doesNotContain("private backend")
    }

    @Test fun `DNS failure identifies network setup without disclosing the exception`() = runBlocking {
        val dnsClient = http.newBuilder().dns { throw java.net.UnknownHostException("private network details") }.build()
        val calls = LiveCallClient(dnsClient, { CodexHeaders("token", "account", null) }, "https://unresolvable.example/calls")
        try {
            val error = runCatching { calls.create("v=0", "voice") }.exceptionOrNull()!!
            assertThat(error.message).contains("DNS")
            assertThat(error.message).doesNotContain("private network")
        } finally { dnsClient.dispatcher.cancelAll() }
    }

    @Test fun `missing account rejects before sending a request`() = runBlocking {
        val error = runCatching { client { CodexHeaders("token", null, null) }.create("v=0", "voice") }.exceptionOrNull()!!
        assertThat(error).isInstanceOf(LiveVoiceException::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `ending a connecting call cancels the network request`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = async { client().create("v=0", "voice") }
        // Await outside the coroutine's thread so the async request can start.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS)!! }
        job.cancelAndJoin()
        assertThat(job.isCancelled).isTrue()
    }
}
