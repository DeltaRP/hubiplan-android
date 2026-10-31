package de.hubiplan.app.data

import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Schlüsselpaare im Android-Keystore (Härtung H7). Sie lassen sich nicht aus dem Gerät
 * herauslesen; StrongBox, wenn vorhanden.
 *
 * - Geräteschlüsselpaar: unterschreibt die Erneuerung des Geräteschlüssels.
 * - Vorstands-Schlüsselpaar: unterschreibt jede Anfrage der Verwaltung; nur nutzbar, solange
 *   das Telefon entsperrt ist.
 */
object DeviceKeys {
    private const val PROVIDER = "AndroidKeyStore"

    class Unavailable(cause: Throwable? = null) : Exception(cause)

    private fun keyStore() = KeyStore.getInstance(PROVIDER).apply { load(null) }

    fun newAlias(): String = "hubiplan-geraet-" + java.util.UUID.randomUUID().toString()

    fun adminAlias(deviceAlias: String): String = "$deviceAlias-vorstand"

    /** Neues Schlüsselpaar unter [alias] (ein altes fällt weg); liefert den öffentlichen Teil. */
    fun create(alias: String, admin: Boolean, strongBox: Boolean): String {
        delete(alias)
        return try {
            generate(alias, admin, strongBox)
        } catch (e: java.security.GeneralSecurityException) {
            if (strongBox) generate(alias, admin, false) else throw Unavailable(e)
        } catch (e: java.security.ProviderException) {
            // u. a. StrongBoxUnavailableException: dann ohne StrongBox
            if (strongBox) generate(alias, admin, false) else throw Unavailable(e)
        }
    }

    private fun generate(alias: String, admin: Boolean, strongBox: Boolean): String {
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
        // Ab Android 9 (Mindestversion seit 0.24.0, Fachfrage 19)
        if (strongBox) builder.setIsStrongBoxBacked(true)
        if (admin) builder.setUnlockedDeviceRequired(true)
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
        generator.initialize(builder.build())
        val pair = generator.generateKeyPair()
        val pub = pair.public as ECPublicKey
        return SignedFormat.b64(SignedFormat.uncompressedPoint(pub.w.affineX, pub.w.affineY))
    }

    fun hasStrongBox(pm: PackageManager): Boolean =
        pm.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    fun exists(alias: String): Boolean = runCatching { keyStore().containsAlias(alias) }.getOrDefault(false)

    fun delete(alias: String) {
        runCatching { keyStore().deleteEntry(alias) }
    }

    /** Unterschrift (DER, base64url). Wirft [Unavailable], wenn der Schlüssel fehlt oder gesperrt ist. */
    fun sign(alias: String, message: ByteArray): String = try {
        val key = keyStore().getKey(alias, null) as? PrivateKey ?: throw Unavailable()
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(key)
        signature.update(message)
        SignedFormat.b64(signature.sign())
    } catch (e: Unavailable) {
        throw e
    } catch (e: Exception) {
        throw Unavailable(e)
    }
}
