package io.github.stardomains3.oxproxion

import android.content.Context
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.TimeoutCancellationException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** The server answered, just not with 2xx. Keeps the code so [LanErrors] can map it. */
class LanHttpException(val status: Int, message: String? = null) : Exception(message ?: "HTTP $status")

data class LanFailure(
    val kind: Kind,
    val status: Int? = null,
    val timeoutSeconds: Int? = null,
) {
    enum class Kind { UNREACHABLE, TIMEOUT, UNAUTHORIZED, NOT_FOUND, MODEL_NOT_FOUND, SERVER_ERROR, HTTP, TLS, OTHER }
}

/** One place that turns LAN failures into words a person can act on (list fetch and chat). */
object LanErrors {
    /**
     * @param status HTTP status if the server answered (overrides the throwable's own status).
     * @param timeoutSeconds the limit a timeout hit, shown in the message.
     * @param timeoutMeansUnreachable short probes (model list) treat a timeout as "can't reach it".
     * @param body response body, only used to tell "model not found" from "wrong endpoint" on 404.
     */
    fun classify(
        error: Throwable?,
        status: Int? = null,
        timeoutSeconds: Int,
        timeoutMeansUnreachable: Boolean = false,
        body: String? = null,
    ): LanFailure {
        val chain = generateSequence(error) { it.cause }.take(6).toList()
        val code = status ?: chain.filterIsInstance<LanHttpException>().firstOrNull()?.status
        if (code != null) return fromStatus(code, body)
        if (chain.any { it is SSLException }) return LanFailure(LanFailure.Kind.TLS)
        if (chain.any { it is ConnectException || it is NoRouteToHostException || it is UnknownHostException }) {
            return LanFailure(LanFailure.Kind.UNREACHABLE)
        }
        val timedOut = chain.any {
            it is TimeoutCancellationException || it is SocketTimeoutException || it is HttpRequestTimeoutException
        }
        if (timedOut) {
            // OkHttp reports a connect timeout as a SocketTimeoutException too; nothing answered at all.
            val connecting = chain.any { it.message?.contains("connect", ignoreCase = true) == true }
            return if (timeoutMeansUnreachable || connecting) {
                LanFailure(LanFailure.Kind.UNREACHABLE)
            } else {
                LanFailure(LanFailure.Kind.TIMEOUT, timeoutSeconds = timeoutSeconds)
            }
        }
        return LanFailure(LanFailure.Kind.OTHER)
    }

    fun fromStatus(code: Int, body: String? = null): LanFailure = when {
        code == 401 || code == 403 -> LanFailure(LanFailure.Kind.UNAUTHORIZED, code)
        // Ollama answers 404 for an unknown model; a missing route means the wrong server type.
        code == 404 && body != null && body.contains("model", true) && body.contains("not found", true) ->
            LanFailure(LanFailure.Kind.MODEL_NOT_FOUND, code)
        code == 404 -> LanFailure(LanFailure.Kind.NOT_FOUND, code)
        code in 500..599 -> LanFailure(LanFailure.Kind.SERVER_ERROR, code)
        else -> LanFailure(LanFailure.Kind.HTTP, code)
    }

    fun message(context: Context, failure: LanFailure, fallback: String? = null): String = when (failure.kind) {
        LanFailure.Kind.UNREACHABLE -> context.getString(R.string.lan_err_unreachable)
        LanFailure.Kind.TIMEOUT -> context.getString(
            R.string.lan_err_timeout,
            duration(context, failure.timeoutSeconds ?: 0)
        )
        LanFailure.Kind.UNAUTHORIZED -> context.getString(R.string.lan_err_unauthorized)
        LanFailure.Kind.NOT_FOUND -> context.getString(R.string.lan_err_not_found)
        LanFailure.Kind.MODEL_NOT_FOUND -> context.getString(R.string.lan_err_model_not_found)
        LanFailure.Kind.SERVER_ERROR -> context.getString(R.string.lan_err_server, failure.status ?: 500)
        LanFailure.Kind.HTTP -> context.getString(R.string.lan_err_http, failure.status ?: 0)
        LanFailure.Kind.TLS -> context.getString(R.string.lan_err_tls)
        LanFailure.Kind.OTHER -> fallback?.takeIf { it.isNotBlank() } ?: context.getString(R.string.lan_err_unknown)
    }

    fun duration(context: Context, seconds: Int): String =
        if (seconds >= 60) context.getString(R.string.lan_duration_min, (seconds + 30) / 60)
        else context.getString(R.string.lan_duration_s, seconds)
}
