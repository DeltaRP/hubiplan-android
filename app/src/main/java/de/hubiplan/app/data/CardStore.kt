package de.hubiplan.app.data

import android.content.Context
import android.util.Base64
import kotlinx.serialization.Serializable
import java.io.File

/** Ausweis, wie er zuletzt vom Server kam, mit Foto und Wappen (ohne Netz anzeigen). */
@Serializable
data class StoredCard(
    val card: CardState,
    /** Zeitpunkt des Abrufs in Millisekunden */
    val savedAt: Long,
    /** Foto und Wappen als Base64 */
    val photo: String? = null,
    val logo: String? = null,
) {
    fun photoBytes(): ByteArray? = photo?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
    fun logoBytes(): ByteArray? = logo?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }

    companion object {
        fun encode(bytes: ByteArray?): String? = bytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
    }
}

/**
 * Mitgliedsausweise je Konto und Zugang. Wie [DraftStore] mit einem Schlüssel aus dem
 * Android-Keystore verschlüsselt, ohne Sicherung; beim Abmelden des Kontos gelöscht.
 */
class CardStore(context: Context, accountKey: String) {
    private val dir = LocalAccountData.dir(context, "ausweise", accountKey)
    private val alias = LocalAccountData.alias(accountKey)

    fun load(accessId: Int): StoredCard? = runCatching {
        val file = File(dir, "ausweis-$accessId.bin")
        json.decodeFromString(StoredCard.serializer(), String(DraftCrypto.open(alias, file.readBytes())))
    }.getOrNull()

    fun save(accessId: Int, card: StoredCard) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "ausweis-$accessId.tmp")
            tmp.writeBytes(DraftCrypto.seal(alias, json.encodeToString(StoredCard.serializer(), card).toByteArray()))
            tmp.renameTo(File(dir, "ausweis-$accessId.bin"))
        }
    }

    fun delete(accessId: Int) {
        File(dir, "ausweis-$accessId.bin").delete()
    }

    /** Alles dieses Kontos löschen (Abmelden). */
    fun clear() {
        dir.deleteRecursively()
    }

    companion object {
        fun key(server: String, deviceId: Int) = LocalAccountData.key(server, deviceId)
    }
}
