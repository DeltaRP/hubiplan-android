package de.hubiplan.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Mehrere Konten auf einem Gerät (Schritt A7), je HUBIplan-Instanz eines. Jedes Konto hat
 * einen eigenen Speicherplatz („Platz“) mit Geräteschlüssel, Push-Schlüsseln und
 * Einstellungen. Platz 0 ist der Speicher der früheren Versionen mit nur einem Konto.
 */
class Accounts(private val context: Context) {
    private val prefs = context.getSharedPreferences("konten", Context.MODE_PRIVATE)
    private val stores = mutableMapOf<Int, AccountStore>()

    private val _active = MutableStateFlow(prefs.getInt(ACTIVE, 0))

    /** Platz des Kontos, das die App gerade zeigt. */
    val active: StateFlow<Int> = _active

    @Synchronized
    fun store(slot: Int): AccountStore = stores.getOrPut(slot) { AccountStore(context, slot) }

    fun activeStore(): AccountStore = store(_active.value)

    /** Belegte Plätze (ohne Konto gespeicherte Plätze fallen beim Lesen des Kontos heraus). */
    fun slots(): List<Int> =
        prefs.getString(SLOTS, null)?.split(",")?.mapNotNull { it.toIntOrNull() }?.distinct() ?: listOf(0)

    fun allStores(): List<AccountStore> = slots().map { store(it) }

    fun setActive(slot: Int) {
        prefs.edit().putInt(ACTIVE, slot).apply()
        _active.value = slot
    }

    /** Platz für ein neues Konto auf [server]: derselbe Server nimmt seinen bisherigen Platz. */
    suspend fun slotFor(server: String): Int {
        for (slot in slots()) {
            val account = store(slot).currentAccount()
            if (account == null || account.server == server) return slot
        }
        val next = (slots().maxOrNull() ?: -1) + 1
        prefs.edit().putString(SLOTS, (slots() + next).joinToString(",")).apply()
        return next
    }

    /** Konto entfernen (Speicher leeren); der Platz wird frei. */
    suspend fun remove(slot: Int) {
        store(slot).clear()
        val rest = slots() - slot
        prefs.edit().putString(SLOTS, rest.ifEmpty { listOf(0) }.joinToString(",")).apply()
    }

    /** Alle Konten mit Platz, in der Reihenfolge des Verbindens. */
    suspend fun list(): List<Pair<Int, Account>> =
        slots().mapNotNull { slot -> store(slot).currentAccount()?.let { slot to it } }

    private companion object {
        const val SLOTS = "plaetze"
        const val ACTIVE = "aktiv"
    }
}
