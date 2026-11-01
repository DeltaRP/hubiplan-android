package de.hubiplan.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import de.hubiplan.app.tr

/**
 * Buchungsentwürfe der Kasse (Schritt B4), solange sie nicht gesendet sind: die einzige
 * Ausnahme von „keine Verwaltungsdaten auf dem Gerät“. Jede Datei ist mit einem Schlüssel aus
 * dem Android-Keystore verschlüsselt (nur nutzbar, solange das Telefon entsperrt ist), liegt im
 * App-Speicher ohne Sicherung und wird nach dem Senden gelöscht. Je Konto ein eigener Ordner.
 */
class DraftStore(context: Context, accountKey: String) {
    private val dir = LocalAccountData.dir(context, "entwuerfe", accountKey).apply { mkdirs() }
    private val alias = LocalAccountData.alias(accountKey)

    /** Langsam (lesen und entschlüsseln): nicht im UI-Thread aufrufen. */
    fun list(): List<BookingDraft> = dir.listFiles().orEmpty()
        .filter { it.name.endsWith(".bin") }
        .sortedBy { it.lastModified() }
        .mapNotNull { file -> runCatching { decodeDraft(String(DraftCrypto.open(alias, file.readBytes()))) }.getOrNull() }

    fun save(draft: BookingDraft) {
        val file = File(dir, safe(draft.key) + ".bin")
        val tmp = File(dir, safe(draft.key) + ".tmp")
        tmp.writeBytes(DraftCrypto.seal(alias, encodeDraft(draft).toByteArray()))
        LocalAccountData.replace(tmp, file)
    }

    /**
     * Dateien, die sich nicht mehr entschlüsseln lassen (z. B. weil Android den Schlüssel nach
     * dem Entfernen der Bildschirmsperre gelöscht hat). Sie bleiben liegen und werden in der
     * Verwaltung als Hinweis gezählt, nie still ausgeblendet (Gesamtaudit 01.11., GA-F04).
     */
    fun unreadable(): List<File> = dir.listFiles().orEmpty()
        .filter { it.name.endsWith(".bin") }
        .filter { file -> runCatching { decodeDraft(String(DraftCrypto.open(alias, file.readBytes()))) }.isFailure }

    /** Nur auf ausdrücklichen Wunsch: unlesbare Dateien löschen. */
    fun discardUnreadable(): Int = unreadable().count { it.delete() }

    /** Anzahl ohne Entschlüsseln (z. B. als Warnung vor dem Abmelden). */
    fun count(): Int = dir.listFiles().orEmpty().count { it.name.endsWith(".bin") }

    fun delete(key: String) {
        File(dir, safe(key) + ".bin").delete()
    }

    private fun safe(key: String) = key.filter { it.isLetterOrDigit() || it == '-' }.take(64)
}

/** Schlüssel aus dem Android-Keystore für lokale Daten, je Konto ein eigener (AND-S02). */
internal object DraftCrypto {
    /** Gemeinsamer Schlüssel bis App 0.18 (nur noch zum Übernehmen alter Dateien). */
    const val LEGACY_ALIAS = "hubiplan-entwuerfe"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12

    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(alias: String): SecretKey {
        (store().getKey(alias, null) as? SecretKey)?.let { return it }
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        // Nur bei entsperrtem Telefon lesbar (Android 9 ist Mindestversion, Fachfrage 19)
        builder.setUnlockedDeviceRequired(true)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(builder.build())
        return generator.generateKey()
    }

    fun seal(alias: String, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(alias))
        return cipher.iv + cipher.doFinal(plain)
    }

    fun open(alias: String, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(alias), GCMParameterSpec(128, data, 0, IV_BYTES))
        return cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES)
    }

    /** Alte Datei (gemeinsamer Schlüssel) lesen; nur beim Übernehmen. */
    fun openLegacy(data: ByteArray): ByteArray {
        if (store().getKey(LEGACY_ALIAS, null) == null) throw IllegalStateException(tr("kein alter Schlüssel"))
        return open(LEGACY_ALIAS, data)
    }

    fun deleteKey(alias: String) {
        runCatching { store().deleteEntry(alias) }
    }
}

fun encodeDraft(draft: BookingDraft): String = json.encodeToString(BookingDraft.serializer(), draft)

fun decodeDraft(text: String): BookingDraft = json.decodeFromString(BookingDraft.serializer(), text)
