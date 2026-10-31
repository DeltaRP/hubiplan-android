package de.hubiplan.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Angemeldetes Konto auf diesem Gerät (je Instanz eines, siehe [Accounts]). */
data class Account(
    val server: String,
    val token: String,
    val deviceId: Int,
    val email: String,
    val display: String,
    val selectedAccess: Int?,
    // Härtung H7: Name des Geräteschlüsselpaars im Keystore (null: ältere Verbindung)
    val keyAlias: String? = null,
)

// Je Datei darf es nur einen DataStore geben: einmal je Platz anlegen
private val dataStores = mutableMapOf<String, DataStore<Preferences>>()

private fun Context.accountDataStore(slot: Int): DataStore<Preferences> = synchronized(dataStores) {
    val name = if (slot == 0) "konto" else "konto-$slot"
    dataStores.getOrPut(name) {
        PreferenceDataStoreFactory.create(produceFile = { applicationContext.preferencesDataStoreFile(name) })
    }
}

private object Keys {
    val server = stringPreferencesKey("server")
    val token = stringPreferencesKey("token")
    val deviceId = intPreferencesKey("device_id")
    val email = stringPreferencesKey("email")
    val display = stringPreferencesKey("display")
    val selectedAccess = intPreferencesKey("selected_access")
    val theme = stringPreferencesKey("theme")
    val calendarOn = booleanPreferencesKey("kalender_an")
    val reminderMinutes = intPreferencesKey("erinnerung_minuten")
    val rsvpHints = booleanPreferencesKey("hinweis_rueckmeldung")
    val notified = stringSetPreferencesKey("hinweise_gezeigt")
    val lastSync = longPreferencesKey("letzter_abgleich")
    val pushKeys = stringPreferencesKey("push_schluessel")
    val pushDistributor = stringPreferencesKey("push_verteiler")
    val pushToken = stringPreferencesKey("push_kennung")
    val pushEndpoint = stringPreferencesKey("push_endpunkt")
    val pushError = stringPreferencesKey("push_fehler")
    val pushOff = booleanPreferencesKey("push_aus")
    // Vorstandszugang (A8), mit AdminVault verschlüsselt (nur nach Entsperren lesbar)
    val adminToken = stringPreferencesKey("verwaltung_schluessel")
    // Härtung H7: Schlüsselpaare im Keystore, Vorstandsanfragen unterschrieben
    val keyAlias = stringPreferencesKey("schluesselpaar")
    val adminSigned = booleanPreferencesKey("verwaltung_unterschrieben")
}

/** Push auf diesem Gerät: über eine UnifiedPush-App oder (Play-Fassung) über Google. */
data class PushState(
    val distributor: String? = null,
    val endpoint: String? = null,
    val error: String? = null,
    // Selbst ausgeschaltet: dann nicht von allein über Google einrichten
    val off: Boolean = false,
) {
    val active: Boolean get() = distributor != null && endpoint != null
}

/** Einstellungen der App auf diesem Gerät. */
data class AppSettings(
    val calendarOn: Boolean = false,
    // Persönliche Erinnerung im Gerätekalender; null = keine (Standard 1 Tag vorher)
    val reminderMinutes: Int? = DEFAULT_REMINDER_MINUTES,
    val rsvpHints: Boolean = true,
    val lastSync: Long? = null,
    val push: PushState = PushState(),
)

const val DEFAULT_REMINDER_MINUTES = 24 * 60

/**
 * Speichert das Konto. Der Geräteschlüssel liegt nur verschlüsselt vor; der Schlüssel
 * dazu steckt im Android-Keystore und lässt sich nicht aus dem Gerät herauslesen.
 */
class AccountStore(private val context: Context, val slot: Int = 0) {
    private val dataStore = context.accountDataStore(slot)

    suspend fun currentAccount(): Account? = account.first()

    val account: Flow<Account?> = dataStore.data.map { it.toAccount() }

    /** Zuletzt geladene Vereinsfarben (für den Start ohne Netz). */
    val cachedTheme: Flow<Theme?> = dataStore.data.map { prefs ->
        prefs[Keys.theme]?.let { runCatching { json.decodeFromString(Theme.serializer(), it) }.getOrNull() }
    }

    val settings: Flow<AppSettings> = dataStore.data.map { p ->
        AppSettings(
            calendarOn = p[Keys.calendarOn] ?: false,
            reminderMinutes = (p[Keys.reminderMinutes] ?: DEFAULT_REMINDER_MINUTES).takeIf { it >= 0 },
            rsvpHints = p[Keys.rsvpHints] ?: true,
            lastSync = p[Keys.lastSync],
            push = PushState(p[Keys.pushDistributor], p[Keys.pushEndpoint], p[Keys.pushError], p[Keys.pushOff] ?: false),
        )
    }

    /** Schlüssel für Web-Push; beim ersten Gebrauch erzeugt, verschlüsselt gespeichert. */
    suspend fun pushKeys(): de.hubiplan.app.push.PushKeys {
        val stored = dataStore.data.first()[Keys.pushKeys]?.let { decrypt(it) }
        stored?.let { de.hubiplan.app.push.PushKeys.parse(it) }?.let { return it }
        val keys = de.hubiplan.app.push.PushKeys.generate()
        dataStore.edit { it[Keys.pushKeys] = encrypt(keys.serialize()) }
        return keys
    }

    /** Kennung dieser App beim Verteiler (zufällig, je Anmeldung neu). */
    suspend fun pushToken(): String {
        dataStore.data.first()[Keys.pushToken]?.let { return it }
        val token = java.util.UUID.randomUUID().toString()
        dataStore.edit { it[Keys.pushToken] = token }
        return token
    }

    suspend fun storedPushToken(): String? = dataStore.data.first()[Keys.pushToken]

    suspend fun setPushDistributor(pkg: String?) = dataStore.edit {
        if (pkg == null) {
            it.remove(Keys.pushDistributor)
            it.remove(Keys.pushEndpoint)
            it.remove(Keys.pushToken)
        } else {
            it[Keys.pushDistributor] = pkg
        }
        it.remove(Keys.pushError)
    }

    /** Vorstandsschlüssel, mit [AdminVault] verschlüsselt; null: nicht freigeschaltet. */
    suspend fun sealedAdminToken(): String? = dataStore.data.first()[Keys.adminToken]

    suspend fun setSealedAdminToken(sealed: String?, signed: Boolean = false) = dataStore.edit {
        if (sealed == null) {
            it.remove(Keys.adminToken)
            it.remove(Keys.adminSigned)
        } else {
            it[Keys.adminToken] = sealed
            it[Keys.adminSigned] = signed
        }
    }

    /** Vorstandsanfragen müssen unterschrieben werden (H7). */
    suspend fun adminSigned(): Boolean = dataStore.data.first()[Keys.adminSigned] ?: false

    /** Neuer Geräteschlüssel nach der Erneuerung (H7). */
    suspend fun setToken(token: String) = dataStore.edit { it[Keys.token] = encrypt(token) }

    /** Schlüsselpaar nachträglich gemeldet (ältere Verbindung). */
    suspend fun setKeyAlias(alias: String) = dataStore.edit { it[Keys.keyAlias] = alias }

    suspend fun setPushOff(off: Boolean) = dataStore.edit { it[Keys.pushOff] = off }

    suspend fun setPushEndpoint(endpoint: String?, error: String? = null) = dataStore.edit {
        if (endpoint == null) it.remove(Keys.pushEndpoint) else it[Keys.pushEndpoint] = endpoint
        if (error == null) it.remove(Keys.pushError) else it[Keys.pushError] = error
    }

    suspend fun setCalendarOn(on: Boolean) = dataStore.edit { it[Keys.calendarOn] = on }

    suspend fun setReminderMinutes(minutes: Int?) = dataStore.edit { it[Keys.reminderMinutes] = minutes ?: -1 }

    suspend fun setRsvpHints(on: Boolean) = dataStore.edit { it[Keys.rsvpHints] = on }

    suspend fun markSynced(at: Long) = dataStore.edit { it[Keys.lastSync] = at }

    /** Hinweise „Rückmeldung fehlt“ nur einmal je Termin. */
    suspend fun notifiedKeys(): Set<String> = dataStore.data.map { it[Keys.notified] ?: emptySet() }.first()

    suspend fun addNotified(keys: Set<String>) = dataStore.edit {
        // Höchstens 400 behalten; die neuen immer (die Menge selbst hat keine Reihenfolge)
        val old = (it[Keys.notified] ?: emptySet()) - keys
        it[Keys.notified] = keys + old.take((400 - keys.size).coerceAtLeast(0))
    }

    private fun Preferences.toAccount(): Account? {
        val server = this[Keys.server] ?: return null
        val token = this[Keys.token]?.let { decrypt(it) } ?: return null
        return Account(
            server = server,
            token = token,
            deviceId = this[Keys.deviceId] ?: return null,
            email = this[Keys.email] ?: "",
            display = this[Keys.display] ?: "",
            selectedAccess = this[Keys.selectedAccess],
            keyAlias = this[Keys.keyAlias],
        )
    }

    suspend fun save(server: String, paired: Paired, keyAlias: String? = null) {
        val old = dataStore.data.first()[Keys.keyAlias]
        if (old != null && old != keyAlias) forgetKeys(old)
        // Ältere Instanz ohne Schlüsselpaare: das eben erzeugte wieder löschen
        if (keyAlias != null && paired.device.key != true) forgetKeys(keyAlias)
        dataStore.edit {
            if (keyAlias != null && paired.device.key == true) it[Keys.keyAlias] = keyAlias else it.remove(Keys.keyAlias)
            it.remove(Keys.adminToken)
            it.remove(Keys.adminSigned)
            it[Keys.server] = server
            it[Keys.token] = encrypt(paired.token)
            it[Keys.deviceId] = paired.device.id
            it[Keys.email] = paired.user.email
            it[Keys.display] = paired.user.display
            it.remove(Keys.selectedAccess)
        }
    }

    suspend fun selectAccess(id: Int, theme: Theme?) {
        dataStore.edit {
            it[Keys.selectedAccess] = id
            if (theme != null) it[Keys.theme] = json.encodeToString(Theme.serializer(), theme)
        }
    }

    /** Abmelden: alles auf dem Gerät löschen. */
    suspend fun clear() {
        dataStore.data.first()[Keys.keyAlias]?.let { forgetKeys(it) }
        dataStore.edit { it.clear() }
    }

    private fun forgetKeys(alias: String) {
        DeviceKeys.delete(alias)
        DeviceKeys.delete(DeviceKeys.adminAlias(alias))
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = runCatching {
        val data = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, IV_BYTES))
        String(cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES))
    }.getOrNull()

    private companion object {
        const val ALIAS = "hubiplan-geraeteschluessel"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
