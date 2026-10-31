package de.hubiplan.app.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import de.hubiplan.app.HubiplanApp
import de.hubiplan.app.data.Account
import de.hubiplan.app.data.Api
import de.hubiplan.app.data.ApiException
import de.hubiplan.app.data.LocalAccountData
import kotlinx.coroutines.flow.first

/** Knopf „Zusage“/„Absage“ in der Benachrichtigung: Rückmeldung senden, auch ohne Netz (später). */
class RsvpActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val slot = intent.getIntExtra(EXTRA_SLOT, 0)
        val access = intent.getIntExtra(EXTRA_ACCESS, -1)
        val event = intent.getIntExtra(EXTRA_EVENT, -1)
        val status = intent.getStringExtra(EXTRA_STATUS) ?: return
        val notification = intent.getIntExtra(EXTRA_NOTIFICATION, 0)
        val accountId = intent.getStringExtra(EXTRA_ACCOUNT) ?: return
        if (access < 0 || event < 0) return
        NotificationManagerCompat.from(context).cancel(notification)
        // Gemerkt und gesendet, sobald Netz da ist. Die Arbeit gehört zu genau diesem Konto
        // (nicht zum Platz, der nach dem Abmelden neu vergeben wird; Gesamtaudit 31.10.b, C-F02)
        WorkManager.getInstance(context).enqueueUniqueWork(
            "rueckmeldung-$accountId-$access-$event",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<RsvpWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(tag(accountId))
                .setInputData(
                    workDataOf(
                        EXTRA_SLOT to slot,
                        EXTRA_ACCOUNT to accountId,
                        EXTRA_ACCESS to access,
                        EXTRA_EVENT to event,
                        EXTRA_STATUS to status,
                        EXTRA_NOTIFICATION to notification,
                    ),
                )
                .build(),
        )
    }

    companion object {
        const val EXTRA_SLOT = "konto"
        const val EXTRA_ACCESS = "zugang"
        const val EXTRA_EVENT = "termin"
        const val EXTRA_STATUS = "status"
        const val EXTRA_NOTIFICATION = "hinweis"
        const val EXTRA_ACCOUNT = "kontokennung"

        /** Kennung des Kontos (Server und Gerät, als Prüfsumme) für Arbeit und Hinweise. */
        fun accountId(account: Account): String = LocalAccountData.id(LocalAccountData.key(account.server, account.deviceId))

        fun tag(accountId: String) = "konto-$accountId"

        /** Beim Abmelden: noch wartende Rückmeldungen dieses Kontos verwerfen. */
        fun cancelFor(context: Context, account: Account) {
            WorkManager.getInstance(context).cancelAllWorkByTag(tag(accountId(account)))
        }

        fun intent(context: Context, slot: Int, accountId: String, accessId: Int, eventId: Int, status: String, notificationId: Int): Intent =
            Intent(context, RsvpActionReceiver::class.java)
                .setAction("de.hubiplan.app.RUECKMELDUNG.$status")
                .putExtra(EXTRA_SLOT, slot)
                .putExtra(EXTRA_ACCOUNT, accountId)
                .putExtra(EXTRA_ACCESS, accessId)
                .putExtra(EXTRA_EVENT, eventId)
                .putExtra(EXTRA_STATUS, status)
                .putExtra(EXTRA_NOTIFICATION, notificationId)
    }
}

class RsvpWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val slot = inputData.getInt(RsvpActionReceiver.EXTRA_SLOT, 0)
        val expected = inputData.getString(RsvpActionReceiver.EXTRA_ACCOUNT) ?: return Result.success()
        val store = (applicationContext as HubiplanApp).accounts.store(slot)
        val account = store.account.first() ?: return Result.success()
        // Platz inzwischen von einem anderen Konto belegt: nichts senden (C-F02)
        if (RsvpActionReceiver.accountId(account) != expected) return Result.success()
        val access = inputData.getInt(RsvpActionReceiver.EXTRA_ACCESS, -1)
        val event = inputData.getInt(RsvpActionReceiver.EXTRA_EVENT, -1)
        val status = inputData.getString(RsvpActionReceiver.EXTRA_STATUS) ?: return Result.failure()
        val notification = inputData.getInt(RsvpActionReceiver.EXTRA_NOTIFICATION, 0)
        return try {
            val detail = Api(account.server, account.token).respond(access, event, status)
            Notifications.showText(
                applicationContext,
                notification,
                if (status == "yes") "Zusage gesendet" else "Absage gesendet",
                detail.title,
            )
            SyncWorker.now(applicationContext)
            Result.success()
        } catch (e: ApiException) {
            if (e.status == 0 || e.status >= 500) return Result.retry()
            // Frist vorbei, ausgebucht, nicht eingeladen: Grund zeigen
            Notifications.showText(applicationContext, notification, "Rückmeldung nicht möglich", e.message ?: "")
            Result.failure()
        }
    }
}
