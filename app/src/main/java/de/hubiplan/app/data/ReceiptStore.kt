package de.hubiplan.app.data

import android.content.Context
import java.io.File

/**
 * Quittungen, die auf dem Telefon unterschrieben, aber noch nicht abgeschlossen sind (ohne
 * Netz). Wie [DraftStore]: je Datei mit einem Schlüssel aus dem Android-Keystore
 * verschlüsselt, ohne Sicherung, nach dem Abschluss gelöscht.
 */
class ReceiptStore(context: Context, accountKey: String) {
    private val dir = LocalAccountData.dir(context, "quittungen", accountKey).apply { mkdirs() }
    private val alias = LocalAccountData.alias(accountKey)

    /** Langsam (lesen und entschlüsseln): nicht im UI-Thread aufrufen. */
    fun list(): List<ReceiptDraft> = dir.listFiles().orEmpty()
        .filter { it.name.endsWith(".bin") }
        .sortedBy { it.lastModified() }
        .mapNotNull { file ->
            runCatching { json.decodeFromString(ReceiptDraft.serializer(), String(DraftCrypto.open(alias, file.readBytes()))) }.getOrNull()
        }

    fun save(draft: ReceiptDraft) {
        val file = File(dir, safe(draft.key) + ".bin")
        val tmp = File(dir, safe(draft.key) + ".tmp")
        tmp.writeBytes(DraftCrypto.seal(alias, json.encodeToString(ReceiptDraft.serializer(), draft).toByteArray()))
        LocalAccountData.replace(tmp, file)
    }

    /** Anzahl ohne Entschlüsseln (z. B. als Warnung vor dem Abmelden). */
    fun count(): Int = dir.listFiles().orEmpty().count { it.name.endsWith(".bin") }

    fun delete(key: String) {
        File(dir, safe(key) + ".bin").delete()
    }

    /** Zuletzt geladene Auswahl je Einheit (verschlüsselt), damit es auch ohne Netz geht. */
    fun choices(unitId: Int): ReceiptChoices? = runCatching {
        val file = File(dir, "auswahl-$unitId.dat")
        json.decodeFromString(ReceiptChoices.serializer(), String(DraftCrypto.open(alias, file.readBytes())))
    }.getOrNull()

    fun saveChoices(unitId: Int, choices: ReceiptChoices) {
        runCatching {
            File(dir, "auswahl-$unitId.dat")
                .writeBytes(DraftCrypto.seal(alias, json.encodeToString(ReceiptChoices.serializer(), choices).toByteArray()))
        }
    }

    private fun safe(key: String) = key.filter { it.isLetterOrDigit() || it == '-' }.take(64)
}
