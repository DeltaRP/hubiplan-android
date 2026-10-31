package de.hubiplan.app.push

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * UnifiedPush ohne Fremdbibliothek: Anmeldung bei einer Verteiler-App (z. B. ntfy) über
 * Broadcasts nach https://unifiedpush.org/developers/spec/android/. Der Verteiler liefert
 * einen Endpunkt; HUBIplan schickt dorthin verschlüsselte Web-Push-Nachrichten.
 */
object UnifiedPush {
    const val ACTION_REGISTER = "org.unifiedpush.android.distributor.REGISTER"
    const val ACTION_UNREGISTER = "org.unifiedpush.android.distributor.UNREGISTER"
    const val ACTION_MESSAGE_ACK = "org.unifiedpush.android.distributor.MESSAGE_ACK"
    const val ACTION_NEW_ENDPOINT = "org.unifiedpush.android.connector.NEW_ENDPOINT"
    const val ACTION_REGISTRATION_FAILED = "org.unifiedpush.android.connector.REGISTRATION_FAILED"
    const val ACTION_UNREGISTERED = "org.unifiedpush.android.connector.UNREGISTERED"
    const val ACTION_MESSAGE = "org.unifiedpush.android.connector.MESSAGE"
    private const val FEATURE_BYTES = "org.unifiedpush.android.distributor.feature.BYTES_MESSAGE"

    data class Distributor(val packageName: String, val label: String)

    /** Installierte Verteiler-Apps (ohne diese App selbst). */
    fun distributors(context: Context): List<Distributor> {
        val pm = context.packageManager
        val intent = Intent(ACTION_REGISTER)
        val found = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryBroadcastReceivers(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryBroadcastReceivers(intent, 0)
        }
        return found.mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == context.packageName) return@mapNotNull null
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            Distributor(pkg, label)
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }

    private fun identity(context: Context): PendingIntent =
        PendingIntent.getBroadcast(context, 0, Intent("org.unifiedpush.dummy_app"), PendingIntent.FLAG_IMMUTABLE)

    fun register(context: Context, distributor: String, token: String, vapid: String?) {
        val intent = Intent(ACTION_REGISTER).setPackage(distributor)
            .putExtra("token", token)
            .putExtra("application", context.packageName)
            .putExtra("message", "HUBIplan")
            .putStringArrayListExtra("features", arrayListOf(FEATURE_BYTES))
            .putExtra("pi", identity(context))
        if (!vapid.isNullOrBlank()) intent.putExtra("vapid", vapid)
        context.sendBroadcast(intent)
    }

    fun unregister(context: Context, distributor: String, token: String) {
        context.sendBroadcast(
            Intent(ACTION_UNREGISTER).setPackage(distributor)
                .putExtra("token", token)
                .putExtra("application", context.packageName)
                .putExtra("pi", identity(context)),
        )
    }

    fun acknowledge(context: Context, distributor: String, token: String, id: String?) {
        if (id.isNullOrBlank()) return
        context.sendBroadcast(
            Intent(ACTION_MESSAGE_ACK).setPackage(distributor)
                .putExtra("token", token)
                .putExtra("id", id),
        )
    }
}
