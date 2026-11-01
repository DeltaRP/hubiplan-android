package de.hubiplan.app.data

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * Quittungs-PDFs zum Teilen oder Ansehen (Gesamtaudit 01.11., GA-S06): je Konto ein eigener
 * Ordner und je Ausgabe ein zufälliger Unterordner. Eine einmal geteilte Adresse zeigt so nie
 * eine spätere oder kontofremde Datei. Aufgeräumt wird beim App-Start, vor jeder neuen
 * Ausgabe (älter als 10 Minuten) und beim Abmelden (alles des Kontos).
 */
object ReceiptCache {
    const val FOLDER = "quittungen"
    const val MAX_AGE_MS = 10 * 60 * 1000L

    fun root(cacheDir: File) = File(cacheDir, FOLDER)

    fun accountDir(cacheDir: File, accountKey: String) = File(root(cacheDir), "k-${LocalAccountData.id(accountKey)}")

    /** PDF ablegen; Rückgabe ist eine neue, nie wiederverwendete Datei. */
    fun write(cacheDir: File, accountKey: String, name: String, bytes: ByteArray): File {
        clear(cacheDir)
        val dir = File(accountDir(cacheDir, accountKey), UUID.randomUUID().toString()).apply { mkdirs() }
        return File(dir, name).apply { writeBytes(bytes) }
    }

    fun write(context: Context, accountKey: String, name: String, bytes: ByteArray): File =
        write(context.cacheDir, accountKey, name, bytes)

    /** Alte Ausgaben löschen (auch verwaiste aus früheren Versionen direkt im Ordner). */
    fun clear(cacheDir: File, olderThanMillis: Long = MAX_AGE_MS, now: Long = System.currentTimeMillis()) {
        val limit = now - olderThanMillis
        root(cacheDir).walkBottomUp().forEach { file ->
            when {
                file == root(cacheDir) -> Unit
                file.isFile && file.lastModified() < limit -> file.delete()
                file.isDirectory && file.listFiles().isNullOrEmpty() -> file.delete()
            }
        }
    }

    fun clear(context: Context) = clear(context.cacheDir)

    /** Abmelden: alle Ausgaben dieses Kontos löschen, andere Konten bleiben. */
    fun forget(cacheDir: File, accountKey: String) {
        accountDir(cacheDir, accountKey).deleteRecursively()
    }
}
