package de.hubiplan.app.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Erneuerung des Geräteschlüssels (Härtung H7) für alle [Api]-Objekte der App.
 *
 * Der Server sperrt ein Gerät, wenn ein ersetzter Schlüssel nach kurzer Frist noch benutzt
 * wird (Erkennung von Kopien). Deshalb fragt jede Anfrage hier nach dem aktuellen Schlüssel,
 * auch wenn ein Objekt noch den alten kennt.
 */
object DeviceTokens {
    private val replaced = ConcurrentHashMap<String, String>()
    private val mutex = Mutex()
    private val renewedAt = ConcurrentHashMap<String, Long>()

    /** Erneuert den Schlüssel [token] auf [server] und speichert ihn; null: nicht möglich. */
    @Volatile
    var renewer: (suspend (server: String, token: String) -> String?)? = null

    /** Aktueller Schlüssel für [token] (folgt allen Erneuerungen). */
    fun current(token: String): String {
        var t = token
        repeat(20) { t = replaced[t] ?: return t }
        return t
    }

    fun replace(old: String, new: String) {
        if (old != new) replaced[old] = new
    }

    /** Nacheinander erneuern: zwei gleichzeitige Erneuerungen würden sich gegenseitig ersetzen. */
    suspend fun renew(server: String, token: String): String? = mutex.withLock {
        val now = current(token)
        if (now != token) return@withLock now
        val fresh = renewer?.invoke(server, token) ?: return@withLock null
        replace(token, fresh)
        renewedAt[server] = System.currentTimeMillis()
        fresh
    }

    /** Fällige Erneuerung (ohne Not): nicht, wenn gerade erst erneuert wurde. */
    suspend fun renewIfDue(server: String, token: String) {
        val last = renewedAt[server] ?: 0L
        if (System.currentTimeMillis() - last < 10 * 60 * 1000L) return
        renew(server, current(token))
    }
}
