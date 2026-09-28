package io.github.stardomains3.oxproxion

import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class LanServerTest {

    // --- normalize ---

    @Test fun bareIpGetsSchemeAndDefaultPort() {
        assertEquals("http://192.168.1.20:11434", LanEndpoints.normalize("192.168.1.20", 11434))
    }

    @Test fun hostWithPortKeepsItsPort() {
        assertEquals("http://nas.local:1234", LanEndpoints.normalize("nas.local:1234", 11434))
    }

    @Test fun fullUrlLosesTrailingSlashAndV1() {
        assertEquals("http://10.0.0.5:1234", LanEndpoints.normalize("http://10.0.0.5:1234/", 1234))
        assertEquals("http://10.0.0.5:1234", LanEndpoints.normalize("http://10.0.0.5:1234/v1", 1234))
        assertEquals("http://10.0.0.5:1234", LanEndpoints.normalize("http://10.0.0.5:1234/v1/", 1234))
        assertEquals("http://10.0.0.5:1234", LanEndpoints.normalize("10.0.0.5:1234/v1/chat/completions", null))
    }

    @Test fun otherPathPrefixesSurvive() {
        assertEquals("http://box.local:8080/llm", LanEndpoints.normalize("http://box.local:8080/llm/v1", 8080))
    }

    @Test fun httpsKeepsSchemeAndAddsNoPort() {
        assertEquals("https://llm.example.com", LanEndpoints.normalize("https://llm.example.com/", 11434))
    }

    @Test fun otherTypeAddsNoPort() {
        assertEquals("http://192.168.1.9", LanEndpoints.normalize("192.168.1.9", null))
    }

    @Test fun schemeIsLowercasedAndWhitespaceTrimmed() {
        assertEquals("http://localhost:11434", LanEndpoints.normalize("  HTTP://localhost  ", 11434))
    }

    @Test fun noHostIsNull() {
        assertNull(LanEndpoints.normalize("", 11434))
        assertNull(LanEndpoints.normalize("   ", 11434))
        assertNull(LanEndpoints.normalize(":11434", 11434))
    }

    @Test fun normalizedUrlPassesTheValidator() {
        assertNull(LanEndpointValidator.validate(LanEndpoints.normalize("192.168.1.20", 11434)!!))
    }

    // --- host field follows the server type ---

    @Test fun emptyFieldGetsThePortOfThePickedType() {
        assertEquals(":11434", LanEndpoints.retargetPort("", null, LanServerType.OLLAMA))
        assertEquals(":1234", LanEndpoints.retargetPort(":11434", LanServerType.OLLAMA, LanServerType.LM_STUDIO))
        assertEquals(":8080", LanEndpoints.retargetPort(":1234", LanServerType.LM_STUDIO, LanServerType.LLAMA_CPP))
        assertEquals(":5001", LanEndpoints.retargetPort(":8080", LanServerType.LLAMA_CPP, LanServerType.KOBOLDCPP))
        assertEquals("", LanEndpoints.retargetPort(":5001", LanServerType.KOBOLDCPP, LanServerType.OTHER))
    }

    @Test fun hostWithTheOldDefaultPortIsRetargeted() {
        assertEquals(
            "192.168.1.20:1234",
            LanEndpoints.retargetPort("192.168.1.20:11434", LanServerType.OLLAMA, LanServerType.LM_STUDIO)
        )
        assertEquals(
            "192.168.1.20",
            LanEndpoints.retargetPort("192.168.1.20:11434", LanServerType.OLLAMA, LanServerType.OTHER)
        )
    }

    @Test fun handTypedPortsAreLeftAlone() {
        assertEquals(
            "192.168.1.20:9999",
            LanEndpoints.retargetPort("192.168.1.20:9999", LanServerType.OLLAMA, LanServerType.LM_STUDIO)
        )
        assertEquals(
            "http://box/v1",
            LanEndpoints.retargetPort("http://box/v1", LanServerType.OLLAMA, LanServerType.LM_STUDIO)
        )
    }

    @Test fun hostLabelAndEditText() {
        assertEquals("192.168.1.20:11434", LanEndpoints.hostLabel("http://192.168.1.20:11434"))
        assertEquals("nas.local", LanEndpoints.hostLabel("http://nas.local"))
        assertEquals("192.168.1.20:11434", LanEndpoints.editText("http://192.168.1.20:11434"))
        assertEquals("https://x.example", LanEndpoints.editText("https://x.example"))
    }

    @Test fun legacyProvidersShowAsOther() {
        assertEquals(LanServerType.OLLAMA, LanServerType.fromProvider("ollama"))
        assertEquals(LanServerType.OTHER, LanServerType.fromProvider("mlx_lm"))
        assertEquals(LanServerType.OTHER, LanServerType.fromProvider("hermes_agent"))
        assertEquals(LanServerType.OTHER, LanServerType.fromProvider(null))
    }

    // --- error mapping ---

    private fun kind(t: Throwable?, status: Int? = null, unreachableOnTimeout: Boolean = false, body: String? = null) =
        LanErrors.classify(t, status, timeoutSeconds = 300, timeoutMeansUnreachable = unreachableOnTimeout, body = body).kind

    @Test fun unreachableHostsMapToOneMessage() {
        assertEquals(LanFailure.Kind.UNREACHABLE, kind(ConnectException("Failed to connect to /192.168.1.5:11434")))
        assertEquals(LanFailure.Kind.UNREACHABLE, kind(NoRouteToHostException()))
        assertEquals(LanFailure.Kind.UNREACHABLE, kind(UnknownHostException("nas.local")))
    }

    @Test fun causesAreUnwrapped() {
        assertEquals(LanFailure.Kind.UNREACHABLE, kind(RuntimeException("wrapped", ConnectException())))
    }

    @Test fun timeoutCarriesTheRealLimit() {
        val f = LanErrors.classify(SocketTimeoutException("timeout"), timeoutSeconds = 300)
        assertEquals(LanFailure.Kind.TIMEOUT, f.kind)
        assertEquals(300, f.timeoutSeconds)
        assertEquals(LanFailure.Kind.TIMEOUT, kind(HttpRequestTimeoutException("http://x", 1000)))
    }

    @Test fun connectTimeoutMeansNothingAnswered() {
        assertEquals(LanFailure.Kind.UNREACHABLE, kind(SocketTimeoutException("connect timed out")))
    }

    @Test fun shortProbeTimeoutIsUnreachable() {
        val timeout = try {
            kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(1) { kotlinx.coroutines.delay(1000) } }
            null
        } catch (e: TimeoutCancellationException) {
            e
        }
        assertEquals(LanFailure.Kind.UNREACHABLE, kind(timeout, unreachableOnTimeout = true))
        assertEquals(LanFailure.Kind.TIMEOUT, kind(timeout))
    }

    @Test fun statusCodesMap() {
        assertEquals(LanFailure.Kind.UNAUTHORIZED, LanErrors.fromStatus(401).kind)
        assertEquals(LanFailure.Kind.UNAUTHORIZED, LanErrors.fromStatus(403).kind)
        assertEquals(LanFailure.Kind.NOT_FOUND, LanErrors.fromStatus(404).kind)
        assertEquals(LanFailure.Kind.SERVER_ERROR, LanErrors.fromStatus(503).kind)
        assertEquals(LanFailure.Kind.HTTP, LanErrors.fromStatus(400).kind)
    }

    @Test fun ollamaMissingModelIsNotAWrongServerType() {
        assertEquals(
            LanFailure.Kind.MODEL_NOT_FOUND,
            LanErrors.fromStatus(404, "model \"llama9\" not found, try pulling it first").kind
        )
        assertEquals(LanFailure.Kind.NOT_FOUND, LanErrors.fromStatus(404, "404 page not found").kind)
    }

    @Test fun httpExceptionCarriesItsStatusThroughWrapping() {
        assertEquals(LanFailure.Kind.UNAUTHORIZED, kind(RuntimeException(LanHttpException(401))))
        assertEquals(LanFailure.Kind.NOT_FOUND, kind(LanHttpException(404)))
        assertEquals(LanFailure.Kind.MODEL_NOT_FOUND, kind(LanHttpException(404), body = "model 'x' not found"))
    }

    @Test fun tlsAndUnknown() {
        assertEquals(LanFailure.Kind.TLS, kind(SSLHandshakeException("bad cert")))
        assertEquals(LanFailure.Kind.OTHER, kind(IllegalStateException("boom")))
        assertEquals(LanFailure.Kind.OTHER, kind(null))
    }

    // --- model traits ---

    @Test fun reasoningModelsAreFlagged() {
        listOf("qwen3:8b", "deepseek-r1:14b", "DeepSeek-R1-Distill-Llama", "openai/gpt-oss-20b", "qwq:32b",
            "magistral-small", "smollm3-thinking", "my-r1-mini").forEach {
            assertTrue(it, LanModelTraits.isReasoning(it))
        }
        listOf("llama3.1:8b", "mistral:7b", "gemma2:9b").forEach { assertFalse(it, LanModelTraits.isReasoning(it)) }
    }

    @Test fun visionModelsAreFlagged() {
        listOf("llava:13b", "qwen2.5-vl:7b", "llama3.2-vision", "gemma3:12b", "pixtral-12b", "minicpm-v:8b").forEach {
            assertTrue(it, LanModelTraits.isVision(it))
        }
        listOf("llama3.1:8b", "qwen3:8b", "mistral:7b").forEach { assertFalse(it, LanModelTraits.isVision(it)) }
    }
}
