package de.hubiplan.app.data

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Lokale Daten eines Kontos (Entwürfe, Quittungen, Ausweise) eindeutig getrennt
 * (Gesamtaudit 30.10., AND-S02): Ordnername und Keystore-Schlüssel kommen aus einer Prüfsumme
 * der vollständigen Kontokennung (Server und Gerät), nicht aus gekürzten Buchstaben. Beim
 * Abmelden werden Ordner und Schlüssel gelöscht.
 */
object LocalAccountData {
    private val FOLDERS = listOf("entwuerfe", "quittungen", "ausweise", ClubLogo.FOLDER)

    /** Kontokennung wie bisher: Serveradresse und Geräte-Nummer. */
    fun key(server: String, deviceId: Int) = "$server-$deviceId"

    fun id(accountKey: String): String =
        MessageDigest.getInstance("SHA-256").digest(accountKey.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    fun dir(context: Context, folder: String, accountKey: String) = File(context.filesDir, "$folder/k-${id(accountKey)}")

    fun alias(accountKey: String) = "hubiplan-daten-${id(accountKey)}"

    /** Bisheriger Ordnername (bis App 0.18): konnte bei verschiedenen Konten gleich sein. */
    private fun legacyName(accountKey: String) = accountKey.filter { it.isLetterOrDigit() }.take(60)

    /** Abmelden: alle lokalen Daten dieses Kontos und seinen Schlüssel löschen. */
    fun forget(context: Context, accountKey: String) {
        FOLDERS.forEach { dir(context, it, accountKey).deleteRecursively() }
        ReceiptCache.forget(context.cacheDir, accountKey)
        DraftCrypto.deleteKey(alias(accountKey))
    }

    /**
     * Konto ohne Rückfrage entfernt (Gerät auf dem Server abgemeldet, Audit 31.10. C-D01):
     * Ausweise löschen; noch nicht gesendete Buchungen und Quittungen nicht vernichten, sondern
     * verschlüsselt in die Quarantäne legen. Der Schlüssel bleibt nur, wenn dort etwas liegt.
     */
    fun retire(context: Context, accountKey: String, server: String = "", email: String = "") {
        dir(context, "ausweise", accountKey).deleteRecursively()
        ReceiptCache.forget(context.cacheDir, accountKey)
        var kept = false
        for (folder in listOf("entwuerfe", "quittungen")) {
            val source = dir(context, folder, accountKey)
            val files = source.listFiles().orEmpty().filter { !it.name.endsWith(".tmp") }
            if (files.isEmpty()) {
                source.deleteRecursively()
                continue
            }
            val target = File(File(context.filesDir, QUARANTINE), "$folder-k-${id(accountKey)}").apply { mkdirs() }
            for (file in files) {
                val dest = File(target, file.name)
                if (!dest.exists()) file.renameTo(dest)
            }
            kept = true
            // Wem die Belege gehören, damit sie nach erneuter Anmeldung zurückkommen (Fachfrage 16)
            File(target, OWNER).writeText(listOf(server, email.lowercase(), alias(accountKey), System.currentTimeMillis()).joinToString("\n"))
            if (source.listFiles().orEmpty().none { !it.name.endsWith(".tmp") }) source.deleteRecursively()
        }
        if (!kept) DraftCrypto.deleteKey(alias(accountKey))
    }

    private const val OWNER = ".konto"

    /** Nach dieser Zeit fragt die App, ob alte Belege aus der Quarantäne gelöscht werden dürfen. */
    const val STALE_DAYS = 90L

    private data class Owner(val server: String, val email: String, val alias: String, val since: Long)

    private fun owner(dir: File): Owner? = runCatching {
        val lines = File(dir, OWNER).readLines()
        Owner(lines[0], lines[1], lines[2], lines[3].toLong())
    }.getOrNull()

    /**
     * Erneut angemeldet (Fachfrage 16, Audit 31.10.): Belege desselben Mitglieds auf derselben
     * Instanz aus der Quarantäne in das neue Konto übernehmen, damit die App sie senden kann.
     * Rückgabe: Zahl der übernommenen Vorgänge.
     */
    fun adopt(context: Context, accountKey: String, server: String, email: String): Int {
        val quarantine = File(context.filesDir, QUARANTINE)
        var moved = 0
        val freed = mutableSetOf<String>()
        for (box in quarantine.listFiles().orEmpty()) {
            val who = owner(box) ?: continue
            if (who.server != server || who.email != email.lowercase() || who.email.isEmpty()) continue
            val folder = box.name.substringBefore("-k-")
            if (folder !in FOLDERS) continue
            val target = dir(context, folder, accountKey).apply { mkdirs() }
            var complete = true
            for (file in box.listFiles().orEmpty()) {
                if (file.name == OWNER || file.name.endsWith(".tmp")) continue
                val ok = runCatching {
                    val plain = DraftCrypto.open(who.alias, file.readBytes())
                    val tmp = File(target, file.name + ".tmp")
                    tmp.writeBytes(DraftCrypto.seal(alias(accountKey), plain))
                    replace(tmp, File(target, file.name))
                    file.delete()
                }.isSuccess
                if (ok) moved++ else complete = false
            }
            if (complete) {
                box.deleteRecursively()
                freed += who.alias
            }
        }
        // Alten Schlüssel nur löschen, wenn nichts mehr mit ihm verschlüsselt ist
        val inUse = quarantine.listFiles().orEmpty().mapNotNull { owner(it)?.alias }.toSet()
        (freed - inUse).forEach { DraftCrypto.deleteKey(it) }
        return moved
    }

    /** Vorgänge in der Quarantäne, die älter als [STALE_DAYS] Tage sind. */
    fun staleCount(context: Context): Int {
        val limit = System.currentTimeMillis() - STALE_DAYS * 24 * 60 * 60 * 1000
        return File(context.filesDir, QUARANTINE).listFiles().orEmpty()
            .filter { (owner(it)?.since ?: it.lastModified()) < limit }
            .sumOf { box -> box.listFiles().orEmpty().count { it.name != OWNER && !it.name.endsWith(".tmp") } }
    }

    /** Nach ausdrücklicher Zustimmung: alte Vorgänge aus der Quarantäne löschen. */
    fun discardStale(context: Context) {
        val limit = System.currentTimeMillis() - STALE_DAYS * 24 * 60 * 60 * 1000
        val quarantine = File(context.filesDir, QUARANTINE)
        val freed = mutableSetOf<String>()
        for (box in quarantine.listFiles().orEmpty()) {
            val who = owner(box)
            if ((who?.since ?: box.lastModified()) >= limit) continue
            if (box.deleteRecursively()) who?.alias?.let { freed += it }
        }
        // Schlüssel gelöschter Konten freigeben (den Altschlüssel räumt migrate() auf)
        val inUse = quarantine.listFiles().orEmpty().mapNotNull { owner(it)?.alias }.toSet()
        (freed - inUse).forEach { DraftCrypto.deleteKey(it) }
    }

    /** Altbestände, die keinem Konto sicher gehören: unverändert verschlüsselt, nie angezeigt. */
    const val QUARANTINE = "quarantaene"

    /**
     * Einmalig beim Start: Ordner im alten Namensschema übernehmen, aber nur, wenn genau ein
     * angemeldetes Konto dazu passt und der Name nicht gekürzt war. Mehrdeutige oder
     * herrenlose Ordner werden nicht gelöscht (Audit 31.10., CL-02), sondern unverändert in
     * die Quarantäne verschoben; der alte Schlüssel bleibt, solange dort noch etwas liegt.
     */
    fun migrate(context: Context, accountKeys: List<String>) {
        val byLegacy = accountKeys.groupBy(::legacyName)
        val quarantine = File(context.filesDir, QUARANTINE)
        for (folder in FOLDERS) {
            val root = File(context.filesDir, folder)
            for (old in root.listFiles().orEmpty()) {
                if (!old.isDirectory || old.name.startsWith("k-")) continue
                val owners = byLegacy[old.name].orEmpty()
                val owner = owners.singleOrNull()?.takeIf { legacyName(it).length < 60 }
                if (owner == null) {
                    val target = File(quarantine, "$folder-${old.name}")
                    quarantine.mkdirs()
                    if (!target.exists() && old.renameTo(target)) continue
                    // Umbenennen ging nicht: Dateien einzeln dazulegen, nichts löschen
                    target.mkdirs()
                    for (file in old.listFiles().orEmpty()) {
                        val dest = File(target, file.name)
                        if (!dest.exists()) file.renameTo(dest)
                    }
                    if (old.listFiles().isNullOrEmpty()) old.delete()
                    continue
                }
                var complete = true
                val target = dir(context, folder, owner).apply { mkdirs() }
                for (file in old.listFiles().orEmpty()) {
                    if (file.name.endsWith(".tmp")) continue
                    val moved = runCatching {
                        val plain = DraftCrypto.openLegacy(file.readBytes())
                        val tmp = File(target, file.name + ".tmp")
                        tmp.writeBytes(DraftCrypto.seal(alias(owner), plain))
                        replace(tmp, File(target, file.name))
                        file.delete()
                    }
                    if (moved.isFailure) complete = false
                }
                // Nicht lesbar (z. B. Telefon gesperrt): beim nächsten Start erneut versuchen
                if (complete) old.deleteRecursively()
            }
        }
        val legacyLeft = FOLDERS.any { folder ->
            File(context.filesDir, folder).listFiles().orEmpty().any { it.isDirectory && !it.name.startsWith("k-") }
        } || quarantine.listFiles().orEmpty().any { !it.name.contains("-k-") }
        if (!legacyLeft) DraftCrypto.deleteKey(DraftCrypto.LEGACY_ALIAS)
    }

    /** Datei sicher ersetzen: erst vollständig schreiben, dann umbenennen (Fehler melden). */
    fun replace(tmp: File, target: File) {
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Die Datei ließ sich nicht speichern.")
        }
    }
}
