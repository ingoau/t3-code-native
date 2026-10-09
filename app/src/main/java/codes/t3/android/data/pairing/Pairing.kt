package codes.t3.android.data.pairing

import java.net.URI
import java.net.URLDecoder

/** A parsed pairing target: where the server lives and the one-time token to exchange. */
data class PairingTarget(val httpBaseUrl: String, val token: String?) {
    val wsBaseUrl: String
        get() = when {
            httpBaseUrl.startsWith("https://") -> "wss://" + httpBaseUrl.removePrefix("https://")
            httpBaseUrl.startsWith("http://") -> "ws://" + httpBaseUrl.removePrefix("http://")
            else -> httpBaseUrl
        }
}

object Pairing {
    private val ipLiteral = Regex("""^(\d{1,3}(\.\d{1,3}){3}|\[[0-9a-fA-F:]+\]|localhost)(:\d+)?$""")

    /**
     * Parse a QR payload or pasted link. Accepts:
     *  - `t3code://…?pairingUrl=<encoded url>`
     *  - hosted links `https://app.t3.codes/pair?host=<backend>#token=…`
     *  - direct links `http://host:3773/pair#token=…`
     */
    fun parse(input: String): PairingTarget? {
        var raw = input.trim()
        if (raw.isEmpty()) return null
        if (raw.startsWith("t3code", ignoreCase = true)) {
            val uri = runCatching { URI(raw) }.getOrNull() ?: return null
            raw = queryParams(uri.rawQuery)["pairingUrl"] ?: return null
        }
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme !in setOf("http", "https", "ws", "wss")) return null
        val query = queryParams(uri.rawQuery)
        val fragment = queryParams(uri.rawFragment)
        val token = fragment["token"] ?: query["token"]
        val host = query["host"]
        val base = if (host != null && token != null) {
            val withScheme = if (host.contains("://")) host else "https://$host"
            origin(runCatching { URI(withScheme) }.getOrNull() ?: return null) ?: return null
        } else {
            origin(uri) ?: return null
        }
        return PairingTarget(base, token?.trim()?.ifEmpty { null })
    }

    /** Build a target from the manual "Address" + "Code" fields. */
    fun manual(address: String, code: String): PairingTarget? {
        val trimmed = address.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val withScheme = when {
            trimmed.contains("://") -> trimmed
            ipLiteral.matches(trimmed) -> "http://$trimmed"
            // Bare hostnames with a port (e.g. tailnet machine names) are usually plain HTTP too.
            Regex("""^[A-Za-z0-9-]+(:\d+)?$""").matches(trimmed) -> "http://$trimmed"
            else -> "https://$trimmed"
        }
        val parsed = parse(withScheme) ?: return null
        val fromLink = parsed.token
        return parsed.copy(token = code.trim().uppercase().ifEmpty { fromLink })
    }

    private fun origin(uri: URI): String? {
        val host = uri.host ?: return null
        val scheme = when (uri.scheme.lowercase()) {
            "ws" -> "http"
            "wss" -> "https"
            else -> uri.scheme.lowercase()
        }
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val hostPart = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "$scheme://$hostPart$port/"
    }

    private fun queryParams(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return raw.split('&').mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) null
            else URLDecoder.decode(part.substring(0, idx), "UTF-8") to URLDecoder.decode(part.substring(idx + 1), "UTF-8")
        }.toMap()
    }
}
