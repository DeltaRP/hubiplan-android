package de.hubiplan.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.hubiplan.app.HubiplanApp
import de.hubiplan.app.calendar.DeviceCalendar
import de.hubiplan.app.calendar.needsRsvpReminder
import de.hubiplan.app.calendar.toCalendarEntry
import de.hubiplan.app.data.AccountStore
import de.hubiplan.app.data.Api
import de.hubiplan.app.data.ApiException
import de.hubiplan.app.ui.DEFAULT_PRIMARY
import de.hubiplan.app.ui.formatWhen
import de.hubiplan.app.ui.parseHexColor
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import de.hubiplan.app.tr

/**
 * Abgleich im Hintergrund: Termine aller Einheiten in den Gerätekalender schreiben und
 * Hinweise „Rückmeldung fehlt“ zeigen, verpasste Mitteilungen anzeigen. Läuft alle 6 Stunden,
 * ohne Push alle 15 Minuten (kürzer erlaubt Android nicht), und nach Änderungen.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Jedes Konto (je Instanz) für sich; ein Fehler bei einem hält die anderen nicht auf
        var retry = false
        for (store in (applicationContext as HubiplanApp).accounts.allStores()) {
            if (syncAccount(store)) retry = true
        }
        // Push kann inzwischen an- oder abgeschaltet sein: Abstand anpassen
        schedule(applicationContext)
        return if (retry) Result.retry() else Result.success()
    }

    /** Ein Konto abgleichen; true: später erneut versuchen (kein Netz, Server gestört). */
    private suspend fun syncAccount(store: AccountStore): Boolean {
        val account = store.account.first() ?: return false
        val settings = store.settings.first()
        val api = Api(account.server, account.token)
        val calendar = DeviceCalendar(applicationContext)
        val writeCalendar = settings.calendarOn && calendar.hasPermission()
        return try {
            val me = api.me()
            // Härtung H7: Geräteschlüssel erneuern, wenn fällig
            runCatching {
                de.hubiplan.app.data.KeyMaintenance(applicationContext, (applicationContext as HubiplanApp).accounts)
                    .maintain(store, me.device)
            }
            val today = LocalDate.now()
            val keep = mutableSetOf<String>()
            val hints = mutableListOf<Triple<Int, de.hubiplan.app.data.Occurrence, String>>()
            for (access in me.portal.filter { it.eventsEnabled }) {
                val items = api.events(access.id, today.minusDays(DAYS_BACK).toString(), today.plusDays(DAYS_AHEAD).toString())
                if (writeCalendar) {
                    val name = DeviceCalendar.calendarName(account.server, access.id)
                    keep += name
                    val color = (parseHexColor(access.theme?.primary) ?: DEFAULT_PRIMARY).toInt()
                    val id = calendar.ensureCalendar(name, access.title, color)
                    calendar.sync(id, items.map { it.toCalendarEntry(access.title, settings.reminderMinutes) })
                }
                val now = OffsetDateTime.now()
                items.filter { it.needsRsvpReminder(now) }.forEach { hints += Triple(access.id, it, access.title) }
            }
            missedPushes(store, api, me.portal.map { it.id }, firstSync = settings.lastSync == null)
            if (settings.calendarOn) calendar.removeCalendarsExcept(keep, account.server)
            if (settings.rsvpHints) showHints(store, hints)
            store.markSynced(System.currentTimeMillis())
            false
        } catch (e: ApiException) {
            when (e.status) {
                401 -> {
                    // Gerät abgemeldet: Kalender und Konto entfernen, die App fragt beim Öffnen neu
                    calendar.removeCalendarsExcept(emptySet(), account.server)
                    // Keine verwaisten Ordner (Audit 31.10., C-D01): Ausweise löschen, ungesendete
                    // Buchungen/Quittungen verschlüsselt in die Quarantäne statt sie zu vernichten
                    runCatching {
                        de.hubiplan.app.data.LocalAccountData.retire(
                            applicationContext,
                            de.hubiplan.app.data.LocalAccountData.key(account.server, account.deviceId),
                            account.server,
                            account.email,
                        )
                    }
                    runCatching { RsvpActionReceiver.cancelFor(applicationContext, account) }
                    runCatching { de.hubiplan.app.push.PushSetup.forget(applicationContext, store) }
                    (applicationContext as HubiplanApp).accounts.remove(store.slot)
                    false
                }
                0, in 500..599 -> true
                else -> false
            }
        } catch (e: SecurityException) {
            // Kalendererlaubnis entzogen
            store.setCalendarOn(false)
            false
        }
    }

    /**
     * Rückfallweg ohne Push: Mitteilungen, die per App zugestellt wurden, aber noch nicht
     * angezeigt sind (Push verloren, Energiesparen, kein Verteiler). Beim ersten Abgleich nur
     * merken, damit nach dem Verbinden keine alten Mitteilungen aufpoppen.
     */
    private suspend fun missedPushes(
        store: de.hubiplan.app.data.AccountStore,
        api: Api,
        accessIds: List<Int>,
        firstSync: Boolean,
    ) {
        val done = store.notifiedKeys()
        val since = OffsetDateTime.now().minusDays(3)
        val seen = mutableSetOf<String>()
        val account = store.currentAccount()
        for (accessId in accessIds) {
            val items = runCatching { api.circulars(accessId) }.getOrNull() ?: continue
            for (c in items.filter { it.channel == "app" }.take(20)) {
                val key = de.hubiplan.app.push.PushSetup.circularKey(accessId, c.id)
                if (key in done) continue
                val sent = c.sentAt?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
                if (!firstSync && sent != null && sent.isAfter(since)) {
                    val logo = de.hubiplan.app.data.ClubLogo.load(applicationContext, account, accessId)
                    Notifications.showCircular(applicationContext, store.slot, accessId, c.id, c.subject, "", c.sender, false, logo)
                }
                seen += key
            }
        }
        if (seen.isNotEmpty()) store.addNotified(seen)
    }

    private suspend fun showHints(
        store: de.hubiplan.app.data.AccountStore,
        hints: List<Triple<Int, de.hubiplan.app.data.Occurrence, String>>,
    ) {
        val done = store.notifiedKeys()
        val shown = mutableSetOf<String>()
        val account = store.currentAccount() ?: return
        val accountId = RsvpActionReceiver.accountId(account)
        for ((accessId, occ, unit) in hints) {
            val key = "$accessId:${occ.key}"
            if (key in done) continue
            Notifications.showRsvpMissing(
                applicationContext,
                store.slot,
                accountId,
                accessId,
                occ.eventId,
                occ.title,
                tr("{x1} · {unit}. Anmeldeschluss ist bald.", "x1" to formatWhen(occ.startsAt, occ.endsAt, occ.allDay), "unit" to unit),
                de.hubiplan.app.data.ClubLogo.load(applicationContext, account, accessId),
            )
            shown += key
        }
        if (shown.isNotEmpty()) store.addNotified(shown)
    }

    companion object {
        private const val DAYS_BACK = 30L
        private const val DAYS_AHEAD = 400L
        private const val PERIODIC = "abgleich"
        private const val NOW = "abgleich-jetzt"

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Abstand des Abgleichs: mit Push für alle Konten selten, sonst so oft wie Android erlaubt. */
        fun intervalMinutes(allPush: Boolean): Long = if (allPush) 6 * 60L else 15L

        /** Regelmäßigen Abgleich einplanen (beim Start der App und nach jedem Abgleich). */
        suspend fun schedule(context: Context) {
            val stores = (context.applicationContext as HubiplanApp).accounts.allStores()
            val allPush = stores.isNotEmpty() && stores.all { it.settings.first().push.active }
            val minutes = intervalMinutes(allPush)
            val prefs = context.getSharedPreferences("abgleich", Context.MODE_PRIVATE)
            // Nur bei geändertem Abstand neu einplanen, sonst verschiebt sich der nächste Lauf
            val policy = if (prefs.getLong("minuten", -1L) == minutes) ExistingPeriodicWorkPolicy.KEEP
            else ExistingPeriodicWorkPolicy.UPDATE
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC,
                policy,
                PeriodicWorkRequestBuilder<SyncWorker>(minutes, TimeUnit.MINUTES).setConstraints(network).build(),
            )
            prefs.edit().putLong("minuten", minutes).apply()
        }

        /** Sofort abgleichen (nach Anmeldung, Rückmeldung, geänderter Einstellung). */
        fun now(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(network).build(),
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
            WorkManager.getInstance(context).cancelUniqueWork(NOW)
            context.getSharedPreferences("abgleich", Context.MODE_PRIVATE).edit().remove("minuten").apply()
        }
    }
}
