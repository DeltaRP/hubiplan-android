package de.hubiplan.app.push

import java.security.MessageDigest

/**
 * Endpunkt beim Push-Gateway (Play-Fassung): `<Gateway>/fcm/<Token>/<Kennung>`.
 *
 * Die Kennung (erste 16 Zeichen hex von SHA-256 über den VAPID-Schlüssel der Instanz) bindet
 * den Endpunkt an diese Instanz: Das Gateway reicht nur Nachrichten mit genau diesem Schlüssel
 * weiter (Härtung H5). Ohne bekannten Schlüssel bleibt es beim alten Endpunkt.
 */
object GatewayEndpoint {
    fun of(gateway: String, token: String, vapidPublicKey: String?): String {
        val base = gateway.trimEnd('/') + "/fcm/" + token
        val kid = vapidPublicKey?.takeIf { it.isNotBlank() }?.let { keyId(it) } ?: return base
        return "$base/$kid"
    }

    fun keyId(vapidPublicKey: String): String? = runCatching {
        val raw = WebPushCrypto.unb64(vapidPublicKey)
        MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }.take(16)
    }.getOrNull()
}
