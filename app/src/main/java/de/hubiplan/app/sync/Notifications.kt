package de.hubiplan.app.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import de.hubiplan.app.MainActivity
import de.hubiplan.app.R

/** Benachrichtigungskanäle (je Art einzeln abschaltbar in den Android-Einstellungen). */
object Notifications {
    const val CHANNEL_RSVP = "rueckmeldung"
    const val CHANNEL_MESSAGES = "mitteilungen"
    const val EXTRA_ACCESS = "zugang"
    const val EXTRA_CIRCULAR = "mitteilung"
    const val EXTRA_SLOT = "konto"
    /** Verknüpfung auf dem Startbildschirm: Prüfsumme des Kontos (Platz kann sich ändern). */
    const val EXTRA_SHORTCUT_ACCOUNT = "verknuepfung_konto"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Mitteilungen", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Mitteilungen und Rundschreiben des Vorstands"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RSVP, "Rückmeldung fehlt", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Hinweis vor dem Anmeldeschluss, wenn du noch nicht zu- oder abgesagt hast"
            },
        )
    }

    private const val PREFS = "hinweise"
    private const val KEY_CONTENT_LOCKED = "inhalt_gesperrt"

    /**
     * Inhalt auch auf dem gesperrten Bildschirm zeigen? Standard nein (Härtung H3): dort steht dann
     * nur „Neue Mitteilung in HUBIplan“, der Inhalt erst nach dem Entsperren. Gilt für das ganze Gerät.
     */
    fun contentWhenLocked(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CONTENT_LOCKED, false)

    fun setContentWhenLocked(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CONTENT_LOCKED, on).apply()
    }

    /** Sperrbildschirm: privat mit neutraler Fassung, außer das Mitglied will den Inhalt sehen. */
    private fun NotificationCompat.Builder.lockScreen(context: Context, channel: String, neutral: String): NotificationCompat.Builder {
        if (contentWhenLocked(context)) return setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        val public = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("HUBIplan")
            .setContentText(neutral)
            .build()
        return setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
    }

    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Hinweis mit Knöpfen Zusage/Absage; die Antwort geht ohne Öffnen der App an HUBIplan. */
    fun showRsvpMissing(
        context: Context,
        slot: Int,
        accountId: String,
        accessId: Int,
        eventId: Int,
        title: String,
        text: String,
        logo: Bitmap? = null,
    ) {
        if (!allowed(context)) return
        val id = notificationId(slot, accessId, eventId)
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_SLOT, slot),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun action(status: String, label: String, request: Int) = NotificationCompat.Action(
            0,
            label,
            PendingIntent.getBroadcast(
                context,
                request,
                RsvpActionReceiver.intent(context, slot, accountId, accessId, eventId, status, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_RSVP)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Rückmeldung fehlt: $title")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            // Wappen der Einheit als großes Bild (das kleine Symbol muss einfarbig sein und bleibt HUBIplan)
            .setLargeIcon(logo)
            .setAutoCancel(true)
            .addAction(action("yes", "Zusage", id * 2))
            .addAction(action("no", "Absage", id * 2 + 1))
            .lockScreen(context, CHANNEL_RSVP, "Rückmeldung fehlt")
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // Erlaubnis inzwischen entzogen
        }
    }

    /** Mitteilung des Vorstands; Antippen öffnet sie in der App. */
    fun showCircular(
        context: Context,
        slot: Int,
        accessId: Int,
        circularId: Int,
        title: String,
        text: String,
        from: String,
        short: Boolean,
        logo: Bitmap? = null,
    ) {
        if (!allowed(context)) return
        val id = (notificationId(slot, accessId, circularId) xor 0x2000_0000) and 0x3FFFFFFF
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_SLOT, slot)
                .putExtra(EXTRA_ACCESS, accessId)
                .putExtra(EXTRA_CIRCULAR, circularId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val body = if (short) text else text.ifBlank { "Neues Rundschreiben" }
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.ifBlank { "Mitteilung" })
            .setContentText(body)
            .setSubText(from.ifBlank { null })
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(open)
            // Wappen der Einheit als großes Bild (das kleine Symbol muss einfarbig sein und bleibt HUBIplan)
            .setLargeIcon(logo)
            .setAutoCancel(true)
            .lockScreen(context, CHANNEL_MESSAGES, "Neue Mitteilung")
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
        }
    }

    fun showText(context: Context, id: Int, title: String, text: String) {
        if (!allowed(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_RSVP)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .lockScreen(context, CHANNEL_RSVP, "Neuer Hinweis")
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
        }
    }

    /** Hinweis von HUBIplan (z. B. Foto freigegeben, Änderung übernommen); öffnet die App. */
    fun showInfo(context: Context, slot: Int, accessId: Int, title: String, text: String, logo: Bitmap? = null) {
        if (!allowed(context)) return
        val id = (notificationId(slot, accessId, (title + text).hashCode()) xor 0x1000_0000) and 0x3FFFFFFF
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_SLOT, slot),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.ifBlank { "HUBIplan" })
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setLargeIcon(logo)
            .setAutoCancel(true)
            .lockScreen(context, CHANNEL_MESSAGES, "Neuer Hinweis")
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
        }
    }

    /** Kennung je Konto, Zugang und Termin/Mitteilung (Konto 0 wie in früheren Versionen). */
    fun notificationId(slot: Int, accessId: Int, itemId: Int): Int =
        (accessId * 100_003 + itemId + slot * 7_919_993) and 0x3FFFFFFF
}
