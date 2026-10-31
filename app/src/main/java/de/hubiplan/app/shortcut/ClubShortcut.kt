package de.hubiplan.app.shortcut

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import de.hubiplan.app.MainActivity
import de.hubiplan.app.R
import de.hubiplan.app.data.Account
import de.hubiplan.app.data.ClubLogo
import de.hubiplan.app.data.LocalAccountData
import de.hubiplan.app.data.PortalAccess
import de.hubiplan.app.sync.Notifications

/**
 * Verknüpfung auf dem Startbildschirm mit Wappen und Namen des Vereins (oder Zugs). Name und
 * Symbol der App selbst bleiben „HUBIplan“; die Verknüpfung öffnet genau dieses Konto und
 * diesen Zugang. Android fragt beim Anlegen selbst nach („Zum Startbildschirm hinzufügen?“).
 */
object ClubShortcut {
    /** Kantenlänge des Symbols: 108 dp bei 4-facher Dichte (adaptives Symbol). */
    private const val SIZE = 432

    /** Sichtbarer Kreis eines adaptiven Symbols ist 72 von 108 dp; das Wappen bleibt darin. */
    private const val LOGO_EDGE = 264

    fun supported(context: Context): Boolean = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** Kennung des Kontos in der Verknüpfung (Prüfsumme aus Server und Gerät, kein Klartext). */
    fun accountId(account: Account): String = LocalAccountData.id(LocalAccountData.key(account.server, account.deviceId))

    fun id(account: Account, accessId: Int) = "verein-${accountId(account)}-$accessId"

    private fun prefix(account: Account) = "verein-${accountId(account)}-"

    /** Startbildschirm um Erlaubnis bitten; false, wenn der Startbildschirm das nicht kann. */
    fun request(context: Context, account: Account, access: PortalAccess): Boolean {
        if (!supported(context)) return false
        return ShortcutManagerCompat.requestPinShortcut(context, info(context, account, access), null)
    }

    /** Angelegte Verknüpfungen dieses Kontos an Namen/Wappen anpassen, verschwundene abschalten. */
    fun refresh(context: Context, account: Account, accesses: List<PortalAccess>) {
        val pinned = pinnedIds(context, account)
        if (pinned.isEmpty()) return
        val byId = accesses.associateBy { id(account, it.id) }
        val update = pinned.mapNotNull { byId[it] }.map { info(context, account, it) }
        if (update.isNotEmpty()) runCatching { ShortcutManagerCompat.updateShortcuts(context, update) }
        val gone = pinned.filter { it !in byId }
        if (gone.isNotEmpty()) disable(context, gone, "Kein Zugang mehr zu dieser Einheit")
    }

    /** Abgemeldet: Verknüpfungen dieses Kontos ausgrauen (entfernen kann nur der Nutzer). */
    fun forget(context: Context, account: Account) {
        val pinned = pinnedIds(context, account)
        if (pinned.isNotEmpty()) disable(context, pinned, "In HUBIplan abgemeldet")
    }

    /** Gehört eine Verknüpfung zu einem bereits angelegten Zugang? */
    fun isPinned(context: Context, account: Account, accessId: Int): Boolean = id(account, accessId) in pinnedIds(context, account)

    private fun pinnedIds(context: Context, account: Account): List<String> =
        runCatching { ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED) }
            .getOrDefault(emptyList())
            .map { it.id }
            .filter { it.startsWith(prefix(account)) }

    private fun disable(context: Context, ids: List<String>, why: String) {
        runCatching { ShortcutManagerCompat.disableShortcuts(context, ids, why) }
    }

    private fun info(context: Context, account: Account, access: PortalAccess): ShortcutInfoCompat {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(Notifications.EXTRA_SHORTCUT_ACCOUNT, accountId(account))
            .putExtra(Notifications.EXTRA_ACCESS, access.id)
        val logo = ClubLogo.load(context, account, access.id)
        val icon = if (logo != null) {
            IconCompat.createWithAdaptiveBitmap(iconBitmap(logo))
        } else {
            IconCompat.createWithResource(context, R.drawable.ic_launcher)
        }
        return ShortcutInfoCompat.Builder(context, id(account, access.id))
            .setShortLabel(access.title.take(25))
            .setLongLabel(access.title)
            .setIcon(icon)
            .setIntent(intent)
            .build()
    }

    /** Wappen mittig auf weißem Grund, so groß, dass es in jede Symbolform passt. */
    private fun iconBitmap(logo: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val scale = LOGO_EDGE.toFloat() / maxOf(logo.width, logo.height)
        val w = (logo.width * scale).toInt()
        val h = (logo.height * scale).toInt()
        val left = (SIZE - w) / 2
        val top = (SIZE - h) / 2
        canvas.drawBitmap(logo, null, Rect(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }
}
