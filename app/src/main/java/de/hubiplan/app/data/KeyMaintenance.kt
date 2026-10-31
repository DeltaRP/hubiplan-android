package de.hubiplan.app.data

import android.content.Context

/**
 * Pflege der Schlüsselpaare (Härtung H7): Geräteschlüssel erneuern, Schlüsselpaar einer
 * älteren Verbindung nachmelden. Wird von [DeviceTokens] und nach jedem Abruf von `me/` genutzt.
 */
class KeyMaintenance(private val context: Context, private val accounts: Accounts) {

    /** Geräteschlüssel [token] auf [server] erneuern und speichern; null: nicht möglich. */
    suspend fun renew(server: String, token: String): String? {
        val store = accounts.allStores().firstOrNull { it.currentAccount()?.server == server } ?: return null
        val account = store.currentAccount() ?: return null
        // Schon erneuert (z. B. vor einem Neustart der App)
        if (account.token != token) return account.token
        val alias = account.keyAlias ?: return null
        return try {
            val api = Api(server, token)
            val challenge = api.renewChallenge()
            val signature = DeviceKeys.sign(alias, SignedFormat.renewMessage(account.deviceId, challenge))
            val renewed = api.renew(challenge, signature)
            store.setToken(renewed.token)
            renewed.token
        } catch (e: ApiException) {
            null
        } catch (e: DeviceKeys.Unavailable) {
            null
        }
    }

    /** Nach `me/`: Schlüsselpaar nachmelden oder Geräteschlüssel erneuern, wenn fällig. */
    suspend fun maintain(store: AccountStore, device: DeviceInfo) {
        val account = store.currentAccount() ?: return
        if (device.key == false && account.keyAlias == null) {
            val alias = DeviceKeys.newAlias()
            val public = runCatching {
                DeviceKeys.create(alias, admin = false, strongBox = DeviceKeys.hasStrongBox(context.packageManager))
            }.getOrNull() ?: return
            val ok = runCatching { Api(account.server, account.token).registerDeviceKey(public).key == true }.getOrDefault(false)
            if (ok) store.setKeyAlias(alias) else DeviceKeys.delete(alias)
        } else if (device.renewDue && account.keyAlias != null) {
            DeviceTokens.renewIfDue(account.server, account.token)
        }
    }
}
