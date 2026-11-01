package de.hubiplan.app

import android.app.Application
import de.hubiplan.app.data.AccountStore
import de.hubiplan.app.data.Accounts
import de.hubiplan.app.sync.Notifications

class HubiplanApp : Application() {
    val accounts: Accounts by lazy { Accounts(this) }

    /** Konto, das die App gerade zeigt. */
    val store: AccountStore get() = accounts.activeStore()

    override fun onCreate() {
        super.onCreate()
        // Sprache vor allem anderen, damit auch Kanäle und Hinweise passen
        Texte.init(this)
        Notifications.createChannels(this)
        // Härtung H7: alle Anfragen erneuern den Geräteschlüssel bei Bedarf über die Konten
        val keys = de.hubiplan.app.data.KeyMaintenance(this, accounts)
        de.hubiplan.app.data.DeviceTokens.renewer = { server, token -> keys.renew(server, token) }
        de.hubiplan.app.push.GooglePush.init(this)
        // Übrig gebliebene Quittungs-PDFs aufräumen (GA-S06), ohne den Start aufzuhalten
        Thread { runCatching { de.hubiplan.app.data.ReceiptCache.clear(this) } }.start()
    }
}
