package ai.closepaw.llm

import ai.closepaw.auth.AuthCredential
import ai.closepaw.auth.AuthStore
import ai.closepaw.auth.FakeSharedPreferences
import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Test

class ReasoningWireTest {
    @Test fun `factory sends selected effort using each provider request format`() = runBlocking {
        val server = MockWebServer()
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.start()
        mockkObject(InsecureSslConfig)
        try {
            every { InsecureSslConfig.trustManager } returns clientTls.trustManager
            every { InsecureSslConfig.sslSocketFactory } returns clientTls.sslSocketFactory()
            for (provider in listOf(LLMProvider.OPENAI_API, LLMProvider.OTHER)) {
                val api = if (provider == LLMProvider.OPENAI_API) "response" else "chat"
                val catalog = ModelCatalog.fromJson("""{"model":{
                    "display_name":"Test","provider":"${provider.name}","api":"$api",
                    "model_id":"gpt-test","base_url":"${server.url("/v1")}",
                    "supported_reasoning_efforts":["low","high"]}}""")
                val prefs = FakeSharedPreferences()
                val store = AuthStore(mockk<Context>(relaxed = true), prefsProvider = { prefs })
                store.set(provider, AuthCredential.ApiKey("fixture-key"))
                for (effort in listOf("high", null, "unsupported")) {
                    server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                        if (api == "response") """{"id":"resp-test","output":[]}"""
                        else """{"id":"chat-test","choices":[]}"""
                    ))
                    val factory = LLMClientFactory(catalog, store, reasoningEffort = effort)
                    factory.create("model").chatWithTools("s", emptyList(), emptyList(), "gpt-test", null)
                    val request = server.takeRequest()
                    val body = JSONObject(request.body.readUtf8())
                    assertThat(body.getString("model")).isEqualTo("gpt-test")
                    if (effort == "high") {
                        if (provider == LLMProvider.OTHER) {
                            assertThat(body.getString("reasoning_effort")).isEqualTo("high")
                            assertThat(body.has("reasoning")).isFalse()
                        } else {
                            assertThat(body.getJSONObject("reasoning").getString("effort")).isEqualTo("high")
                            assertThat(body.has("reasoning_effort")).isFalse()
                        }
                    } else {
                        assertThat(body.has("reasoning")).isFalse()
                        assertThat(body.has("reasoning_effort")).isFalse()
                    }
                    factory.cleanupAll()
                }
            }
        } finally {
            unmockkObject(InsecureSslConfig)
            server.shutdown()
        }
    }
}
