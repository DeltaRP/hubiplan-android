package de.hubiplan.app.data

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Tresor für den Vorstandsschlüssel (Schritt A8): Der Schlüssel im Android-Keystore lässt
 * sich nur bis [UNLOCK_SECONDS] Sekunden nach Fingerabdruck, Gesicht oder Geräte-PIN nutzen.
 * Ohne eingerichtete Bildschirmsperre gibt es keinen Tresor und damit keine Verwaltung.
 */
object AdminVault {
    const val UNLOCK_SECONDS = 300
    private const val ALIAS = "hubiplan-verwaltung"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12

    /** Gerät muss erst entsperrt werden (Fingerabdruck/PIN). */
    class LockedException : Exception()

    /** Schlüssel unbrauchbar (z. B. Bildschirmsperre geändert): neu freischalten. */
    class GoneException : Exception()

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(create: Boolean): SecretKey? {
        (keyStore().getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val builder = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setUserAuthenticationParameters(
                UNLOCK_SECONDS,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(UNLOCK_SECONDS)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(builder.build())
        return generator.generateKey()
    }

    /** Verschlüsseln (direkt nach dem Entsperren aufrufen). */
    fun seal(plain: String): String = guarded {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    fun open(sealed: String): String = guarded {
        val data = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(create = false) ?: throw GoneException(), GCMParameterSpec(128, data, 0, IV_BYTES))
        String(cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES))
    }

    private fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: UserNotAuthenticatedException) {
        throw LockedException()
    } catch (e: KeyPermanentlyInvalidatedException) {
        runCatching { keyStore().deleteEntry(ALIAS) }
        throw GoneException()
    } catch (e: java.security.GeneralSecurityException) {
        // u. a. ohne Bildschirmsperre: kein Schlüssel mit Entsperr-Pflicht möglich
        throw GoneException()
    } catch (e: IllegalStateException) {
        throw GoneException()
    }
}
