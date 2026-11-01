package de.hubiplan.app.push

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Schlüssel dieses Geräts für Web-Push (RFC 8291): P-256-Schlüsselpaar und 16 Byte „auth“.
 * Der öffentliche Teil und auth gehen an HUBIplan, der private bleibt auf dem Gerät.
 */
class PushKeys(val keyPair: KeyPair, val auth: ByteArray) {
    val publicBytes: ByteArray get() = WebPushCrypto.uncompressed(keyPair.public as ECPublicKey)
    val p256dh: String get() = WebPushCrypto.b64(publicBytes)
    val authB64: String get() = WebPushCrypto.b64(auth)

    /** Zum Speichern (verschlüsselt im AccountStore): privater Schlüssel (PKCS#8) und auth. */
    fun serialize(): String =
        WebPushCrypto.b64(keyPair.private.encoded) + "." + WebPushCrypto.b64(publicBytes) + "." + authB64

    companion object {
        fun generate(random: SecureRandom = SecureRandom()): PushKeys {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"), random)
            val auth = ByteArray(16).also { random.nextBytes(it) }
            return PushKeys(generator.generateKeyPair(), auth)
        }

        fun parse(text: String): PushKeys? = runCatching {
            val (priv, pub, auth) = text.split(".")
            val factory = KeyFactory.getInstance("EC")
            val private = factory.generatePrivate(PKCS8EncodedKeySpec(WebPushCrypto.unb64(priv))) as ECPrivateKey
            val public = WebPushCrypto.publicKey(WebPushCrypto.unb64(pub), private)
            PushKeys(KeyPair(public, private), WebPushCrypto.unb64(auth))
        }.getOrNull()
    }
}

class PushDecryptException(message: String) : Exception(message)

/** Entschlüsseln nach RFC 8291 (Inhaltskodierung aes128gcm, RFC 8188), ein Datensatz. */
object WebPushCrypto {

    fun b64(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    fun unb64(text: String): ByteArray = Base64.getUrlDecoder().decode(text.trim().trimEnd('='))

    fun uncompressed(key: ECPublicKey): ByteArray {
        fun fixed(n: BigInteger): ByteArray {
            val raw = n.toByteArray()
            return when {
                raw.size == 32 -> raw
                raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
                else -> ByteArray(32 - raw.size) + raw
            }
        }
        return byteArrayOf(0x04) + fixed(key.w.affineX) + fixed(key.w.affineY)
    }

    /** Öffentlicher Schlüssel aus 65 Byte (0x04 ‖ x ‖ y) auf der Kurve von [like]. */
    fun publicKey(raw: ByteArray, like: ECPrivateKey): ECPublicKey {
        if (raw.size != 65 || raw[0] != 0x04.toByte()) throw PushDecryptException("Ungültiger Schlüssel")
        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))
        val spec = ECPublicKeySpec(ECPoint(x, y), like.params)
        return KeyFactory.getInstance("EC").generatePublic(spec) as ECPublicKey
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** Höchste Größe einer Push-Nachricht (RFC 8030 verlangt mindestens 4096 Byte). */
    const val MAX_BODY = 8192

    /**
     * Entschlüsselt eine Push-Nachricht. Jeder Fehler (auch ungültige Punkte, Längen oder
     * Schlüssel) endet als [PushDecryptException] und damit am vorgesehenen Fehlerpfad
     * (Gesamtaudit 01.11., GA-S05).
     */
    fun decrypt(body: ByteArray, keys: PushKeys): ByteArray = try {
        decryptChecked(body, keys)
    } catch (e: PushDecryptException) {
        throw e
    } catch (e: java.security.GeneralSecurityException) {
        throw PushDecryptException("Ungültige Nachricht")
    } catch (e: IllegalArgumentException) {
        throw PushDecryptException("Ungültige Nachricht")
    } catch (e: java.nio.BufferUnderflowException) {
        throw PushDecryptException("Nachricht zu kurz")
    } catch (e: ArithmeticException) {
        throw PushDecryptException("Ungültige Nachricht")
    } catch (e: IllegalStateException) {
        throw PushDecryptException("Ungültige Nachricht")
    }

    private fun decryptChecked(body: ByteArray, keys: PushKeys): ByteArray {
        if (body.size < 21 + 65 + 17) throw PushDecryptException("Nachricht zu kurz")
        if (body.size > MAX_BODY) throw PushDecryptException("Nachricht zu groß")
        val buffer = ByteBuffer.wrap(body)
        val salt = ByteArray(16).also { buffer.get(it) }
        val recordSize = buffer.int
        val idLength = buffer.get().toInt() and 0xFF
        if (idLength != 65 || recordSize < 18) throw PushDecryptException("Unbekanntes Format")
        val senderRaw = ByteArray(idLength).also { buffer.get(it) }
        val ciphertext = ByteArray(buffer.remaining()).also { buffer.get(it) }
        if (ciphertext.size > recordSize) throw PushDecryptException("Mehrere Datensätze werden nicht unterstützt")

        val private = keys.keyPair.private as ECPrivateKey
        val sender = publicKey(senderRaw, private)
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(private)
        agreement.doPhase(sender, true)
        val shared = agreement.generateSecret()

        val prkKey = hmac(keys.auth, shared)
        val ikm = hmac(prkKey, "WebPush: info\u0000".toByteArray() + keys.publicBytes + senderRaw + byteArrayOf(1))
        val prk = hmac(salt, ikm)
        val cek = hmac(prk, "Content-Encoding: aes128gcm\u0000".toByteArray() + byteArrayOf(1)).copyOf(16)
        val nonce = hmac(prk, "Content-Encoding: nonce\u0000".toByteArray() + byteArrayOf(1)).copyOf(12)

        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw PushDecryptException("Entschlüsseln fehlgeschlagen")
        }
        // Auffüllung entfernen: Ende ist das letzte Byte ungleich 0, es muss 2 sein (letzter Datensatz)
        var end = plain.size - 1
        while (end >= 0 && plain[end] == 0.toByte()) end--
        if (end < 0 || plain[end] != 2.toByte()) throw PushDecryptException("Ungültige Auffüllung")
        return plain.copyOf(end)
    }
}
