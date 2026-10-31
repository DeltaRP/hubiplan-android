package de.hubiplan.app.data

import java.net.URI

/** Inhalt des QR-Codes „App verbinden“: https://<Instanz>/app/verbinden#code=<Code>. */
data class PairingLink(val server: String, val code: String) {
    companion object {
        fun parse(text: String): PairingLink? {
            val uri = try {
                URI(text.trim())
            } catch (e: Exception) {
                return null
            }
            if (uri.scheme != "https" || uri.host.isNullOrBlank()) return null
            if (uri.path?.trimEnd('/') != "/app/verbinden") return null
            val code = uri.rawFragment
                ?.split('&')
                ?.firstOrNull { it.startsWith("code=") }
                ?.removePrefix("code=")
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val port = if (uri.port == -1) "" else ":${uri.port}"
            return PairingLink("https://${uri.host}$port", code)
        }
    }
}

/** Eingegebene Serveradresse vereinheitlichen; nur HTTPS. */
fun normalizeServer(input: String): String? {
    var text = input.trim().trimEnd('/')
    if (text.isEmpty()) return null
    if (!text.contains("://")) text = "https://$text"
    val uri = try {
        URI(text)
    } catch (e: Exception) {
        return null
    }
    if (uri.scheme != "https" || uri.host.isNullOrBlank()) return null
    val port = if (uri.port == -1) "" else ":${uri.port}"
    return "https://${uri.host}$port"
}
