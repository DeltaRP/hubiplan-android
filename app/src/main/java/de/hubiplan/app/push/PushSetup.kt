package de.hubiplan.app.push

import android.content.Context
import de.hubiplan.app.HubiplanApp
import de.hubiplan.app.data.AccountStore
import de.hubiplan.app.data.Api
import de.hubiplan.app.data.ApiException
import de.hubiplan.app.data.ClubLogo
import de.hubiplan.app.data.PushWay
import de.hubiplan.app.sync.Notifications
import de.hubiplan.app.sync.SyncWorker
import kotlinx.coroutines.flow.first

/**
 * Push einrichten, Endpunkt an HUBIplan melden und Nachrichten anzeigen. Jedes Konto (je
 * Instanz) meldet sich einzeln an, mit eigener Kennung beim Verteiler und eigenen Schlüsseln;
 * daran erkennt die App, für welches Konto eine Nachricht ist.
 */
object PushSetup {

    private fun accounts(context: Context) = (context.applicationContext as HubiplanApp).accounts

    /**
     * Bei der Verteiler-App [distributor] anmelden; der Endpunkt kommt per Broadcast zurück.
     * [GooglePush.ID] (nur Play-Fassung): über Google und das Push-Gateway der Instanz.
     */
    suspend fun enable(context: Context, store: AccountStore, distributor: String) {
        val account = store.account.first() ?: return
        val previous = store.settings.first().push.distributor
        if (previous != null && previous != distributor) {
            leave(context, store, previous)
            store.setPushDistributor(null)
        }
        store.setPushOff(false)
        val ways = runCatching { Api(account.server).instance().push }.getOrNull()
        if (distributor == GooglePush.ID) {
            store.setPushDistributor(distributor)
            enableGoogle(context, store, ways)
            return
        }
        val vapid = ways?.firstOrNull { it.kind == "unifiedpush" }?.vapidPublicKey
        store.setPushDistributor(distributor)
        UnifiedPush.register(context, distributor, store.pushToken(), vapid)
    }

    private suspend fun enableGoogle(context: Context, store: AccountStore, ways: List<PushWay>?, fcmToken: String? = null) {
        if (ways == null) {
            store.setPushEndpoint(null, "HUBIplan ist gerade nicht erreichbar.")
            return
        }
        val fcmWay = ways.firstOrNull { it.kind == "fcm" }
        val gateway = fcmWay?.gateway
        if (gateway.isNullOrBlank()) {
            store.setPushEndpoint(null, "Dein Verein bietet Push über Google nicht an. Eine UnifiedPush-App wie „ntfy“ geht immer.")
            return
        }
        val token = fcmToken ?: GooglePush.token(context)
        if (token == null) {
            store.setPushEndpoint(null, "Google-Push ist auf diesem Gerät nicht verfügbar.")
            return
        }
        // An den VAPID-Schlüssel der Instanz binden (Gateway nimmt nur deren Nachrichten an)
        val vapid = fcmWay.vapidPublicKey ?: ways.firstOrNull { it.kind == "unifiedpush" }?.vapidPublicKey
        register(store, GatewayEndpoint.of(gateway, token, vapid), kind = "fcm")
    }

    /** Beim bisherigen Weg abmelden (Verteiler-App bzw. Google-Kennung, wenn kein anderes Konto sie nutzt). */
    private suspend fun leave(context: Context, store: AccountStore, distributor: String) {
        if (distributor == GooglePush.ID) {
            val others = accounts(context).allStores().filter { it.slot != store.slot }
                .any { it.settings.first().push.distributor == GooglePush.ID }
            if (!others) GooglePush.delete(context)
        } else {
            store.storedPushToken()?.let { UnifiedPush.unregister(context, distributor, it) }
        }
    }

    /** Push abschalten: beim Verteiler und bei HUBIplan abmelden (dann nur Abruf). */
    suspend fun disable(context: Context, store: AccountStore) {
        store.settings.first().push.distributor?.let { leave(context, store, it) }
        store.account.first()?.let { account -> runCatching { Api(account.server, account.token).unregisterPush() } }
        store.setPushDistributor(null)
    }

    /** Selbst abgeschaltet: merken, damit die Play-Fassung nicht von allein Google einrichtet. */
    suspend fun turnOff(context: Context, store: AccountStore) {
        disable(context, store)
        store.setPushOff(true)
    }

    /** Nach dem Start: bei der Verteiler-App erneut anmelden (sie bestätigt den Endpunkt). */
    suspend fun refresh(context: Context, store: AccountStore) {
        val push = store.settings.first().push
        val distributor = push.distributor
        if (distributor == null) {
            // Play-Fassung ohne UnifiedPush-App: von allein über Google, außer selbst abgeschaltet
            if (!push.off && GooglePush.available(context) && UnifiedPush.distributors(context).isEmpty()) {
                enable(context, store, GooglePush.ID)
            }
            return
        }
        if (distributor == GooglePush.ID) {
            if (GooglePush.available(context)) enable(context, store, distributor) else disable(context, store)
            return
        }
        if (UnifiedPush.distributors(context).none { it.packageName == distributor }) {
            // Verteiler-App deinstalliert: nur noch Abruf
            disable(context, store)
        } else {
            enable(context, store, distributor)
        }
    }

    /** Gerät abgemeldet: nur beim Verteiler abmelden (HUBIplan kennt das Gerät nicht mehr). */
    suspend fun forget(context: Context, store: AccountStore) {
        val distributor = store.settings.first().push.distributor ?: return
        leave(context, store, distributor)
    }

    /** Konto, zu dem eine Kennung beim Verteiler gehört. */
    private suspend fun storeFor(context: Context, token: String?): AccountStore? {
        if (token == null) return null
        return accounts(context).allStores().firstOrNull { it.storedPushToken() == token }
    }

    /** Google hat dem Gerät eine neue Kennung gegeben: Endpunkt bei allen Konten mit Google neu melden. */
    suspend fun onGoogleToken(context: Context, fcmToken: String) {
        for (store in accounts(context).allStores()) {
            if (store.settings.first().push.distributor != GooglePush.ID) continue
            val account = store.account.first() ?: continue
            enableGoogle(context, store, runCatching { Api(account.server).instance().push }.getOrNull(), fcmToken)
        }
    }

    /**
     * Nachricht über Google (Play-Fassung): derselbe verschlüsselte Web-Push wie bei UnifiedPush.
     * Welches Konto gemeint ist, zeigt der Schlüssel, mit dem sie sich entschlüsseln lässt.
     */
    suspend fun onGoogleMessage(context: Context, bytes: ByteArray) {
        for (store in accounts(context).allStores()) {
            if (store.settings.first().push.distributor != GooglePush.ID) continue
            val message = decrypt(store, bytes) ?: continue
            handle(context, store, message)
            return
        }
    }

    suspend fun onNewEndpoint(context: Context, token: String?, endpoint: String?) {
        val store = storeFor(context, token) ?: return
        if (endpoint == null) return
        register(store, endpoint, kind = "unifiedpush")
    }

    private suspend fun register(store: AccountStore, endpoint: String, kind: String) {
        val account = store.account.first() ?: return
        val keys = store.pushKeys()
        try {
            Api(account.server, account.token).registerPush(endpoint, keys.p256dh, keys.authB64, kind)
            store.setPushEndpoint(endpoint)
        } catch (e: ApiException) {
            store.setPushEndpoint(null, e.message)
        }
    }

    suspend fun onFailed(context: Context, token: String?, reason: String?) {
        val store = storeFor(context, token) ?: return
        store.setPushEndpoint(null, "Die Push-App hat die Anmeldung abgelehnt" + (reason?.let { " ($it)" } ?: "") + ".")
    }

    suspend fun onUnregistered(context: Context, token: String?) {
        val store = storeFor(context, token) ?: return
        val account = store.account.first()
        runCatching { account?.let { Api(it.server, it.token).unregisterPush() } }
        store.setPushDistributor(null)
    }

    suspend fun onMessage(context: Context, token: String?, bytes: ByteArray?, id: String?) {
        val store = storeFor(context, token) ?: return
        if (bytes == null || token == null) return
        store.settings.first().push.distributor?.let { UnifiedPush.acknowledge(context, it, token, id) }
        // Nicht von HUBIplan oder für einen alten Schlüssel: verwerfen
        val message = decrypt(store, bytes) ?: return
        handle(context, store, message)
    }

    private suspend fun decrypt(store: AccountStore, bytes: ByteArray): PushMessage? = try {
        PushMessage.parse(String(WebPushCrypto.decrypt(bytes, store.pushKeys())))
    } catch (e: PushDecryptException) {
        null
    }

    suspend fun handle(context: Context, store: AccountStore, message: PushMessage) {
        when (message.type) {
            "circular" -> {
                val access = message.access ?: return
                val id = message.id ?: return
                val key = circularKey(access, id)
                if (key in store.notifiedKeys()) return
                val logo = ClubLogo.load(context, store.currentAccount(), access)
                Notifications.showCircular(context, store.slot, access, id, message.title, message.text, message.from, message.kind == "short", logo)
                store.addNotified(setOf(key))
            }
            "info" -> {
                val access = message.access ?: return
                Notifications.showInfo(context, store.slot, access, message.title, message.text, ClubLogo.load(context, store.currentAccount(), access))
            }
            else -> SyncWorker.now(context)
        }
    }

    fun circularKey(access: Int, id: Int) = "r:$access:$id"
}
