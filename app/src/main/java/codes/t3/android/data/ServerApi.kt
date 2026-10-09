package codes.t3.android.data

import codes.t3.android.data.model.EnvironmentDescriptor
import codes.t3.android.data.model.T3Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Thrown for HTTP-level failures. [blocked] errors (auth, incompatible server) should not be retried automatically. */
class ServerApiException(message: String, val status: Int = 0, val blocked: Boolean = false) : IOException(message)

/** Newest orchestration protocol this client speaks; v1 (t3 ≤ 0.0.45) is supported through an adapter. */
const val ORCHESTRATION_PROTOCOL = 2
val SUPPORTED_PROTOCOLS = 1..2

@Serializable
data class AccessTokenResult(val access_token: String, val expires_in: Long? = null, val scope: String? = null)

@Serializable
data class WebSocketTicket(val ticket: String, val expiresAt: String? = null)

/** The handful of plain-HTTP endpoints needed to pair and to mint WebSocket tickets. */
class ServerApi(private val http: OkHttpClient) {

    suspend fun descriptor(httpBaseUrl: String): EnvironmentDescriptor = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(httpBaseUrl.trimEnd('/') + "/.well-known/t3/environment").get().build()
        val body = execute(request)
        val descriptor = T3Json.decodeFromString<EnvironmentDescriptor>(body)
        val version = descriptor.orchestrationProtocolVersion ?: 1
        if (version !in SUPPORTED_PROTOCOLS) {
            throw ServerApiException(
                if (version < SUPPORTED_PROTOCOLS.first) "This T3 Code server is too old. Update T3 Code on the host."
                else "This server needs a newer version of the app.",
                blocked = true,
            )
        }
        descriptor
    }

    suspend fun exchangePairingToken(httpBaseUrl: String, token: String, deviceLabel: String): AccessTokenResult =
        withContext(Dispatchers.IO) {
            val form = FormBody.Builder()
                .add("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange")
                .add("subject_token", token)
                .add("subject_token_type", "urn:t3:params:oauth:token-type:environment-bootstrap")
                .add("requested_token_type", "urn:ietf:params:oauth:token-type:access_token")
                .add("client_label", deviceLabel)
                .add("client_device_type", "mobile")
                .add("client_os", "Android")
                .build()
            val request = Request.Builder().url(httpBaseUrl.trimEnd('/') + "/oauth/token").post(form).build()
            T3Json.decodeFromString<AccessTokenResult>(execute(request))
        }

    suspend fun webSocketTicket(httpBaseUrl: String, accessToken: String): WebSocketTicket = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(httpBaseUrl.trimEnd('/') + "/api/auth/websocket-ticket")
            .header("Authorization", "Bearer $accessToken")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        T3Json.decodeFromString<WebSocketTicket>(execute(request))
    }

    private fun execute(request: Request): String {
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw ServerApiException(friendlyNetworkError(e))
        }
        response.use {
            val body = it.body.string()
            if (it.isSuccessful) return body
            throw errorFor(it.code, body)
        }
    }

    private fun errorFor(code: Int, body: String): ServerApiException {
        val json = runCatching { T3Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val reason = (json?.get("reason") as? JsonPrimitive)?.contentOrNull
        val message = (json?.get("message") as? JsonPrimitive)?.contentOrNull
        return when (code) {
            401 -> ServerApiException(
                if (reason == "invalid_credential") "That pairing code is invalid or has expired. Run `t3 pair` for a new one."
                else "Not authorized. Pair this device again.",
                code, blocked = true,
            )
            426 -> ServerApiException("This server speaks a different protocol version. Update T3 Code.", code, blocked = true)
            else -> ServerApiException(message ?: "Server returned HTTP $code", code)
        }
    }
}

fun friendlyNetworkError(e: Throwable): String {
    val name = e::class.java.simpleName
    val msg = e.message.orEmpty()
    return when {
        name.contains("UnknownHost") -> "Couldn't find that host. Check the address."
        name.contains("ConnectException") || msg.contains("Failed to connect") || msg.contains("ECONNREFUSED") ->
            "Couldn't reach the server. Is `t3` running and reachable from this device?"
        name.contains("SocketTimeout") || msg.contains("timeout", ignoreCase = true) -> "The server took too long to respond."
        name.contains("SSL") -> "Secure connection failed: $msg"
        else -> msg.ifEmpty { name }
    }
}
