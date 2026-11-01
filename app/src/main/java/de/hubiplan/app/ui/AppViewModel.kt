package de.hubiplan.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.hubiplan.app.BuildConfig
import de.hubiplan.app.HubiplanApp
import de.hubiplan.app.calendar.DeviceCalendar
import de.hubiplan.app.data.Account
import de.hubiplan.app.data.AdminClient
import de.hubiplan.app.data.AccountStore
import de.hubiplan.app.data.AdminOverview
import de.hubiplan.app.data.AdminVault
import de.hubiplan.app.data.Api
import de.hubiplan.app.data.ApiException
import de.hubiplan.app.data.ClubLogo
import de.hubiplan.app.data.AppSettings
import de.hubiplan.app.data.DeviceKeys
import de.hubiplan.app.data.DraftStore
import de.hubiplan.app.data.KeyMaintenance
import de.hubiplan.app.data.LocalAccountData
import de.hubiplan.app.data.LoginStep
import de.hubiplan.app.data.Me
import de.hubiplan.app.data.Paired
import de.hubiplan.app.data.PairingLink
import de.hubiplan.app.data.PortalAccess
import de.hubiplan.app.data.ReceiptStore
import de.hubiplan.app.data.Theme
import de.hubiplan.app.data.normalizeServer
import de.hubiplan.app.push.PushSetup
import de.hubiplan.app.shortcut.ClubShortcut
import de.hubiplan.app.sync.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import de.hubiplan.app.tr
import de.hubiplan.app.trn

sealed interface Screen {
    data object Loading : Screen
    data object Connect : Screen
    data class Main(val account: Account, val me: Me?, val access: PortalAccess?, val offline: Boolean) : Screen
}

/** Schritte beim Verbinden (QR-Code oder Passwort mit 2FA). */
data class ConnectState(
    val busy: Boolean = false,
    val error: String? = null,
    val server: String = "",
    // Zweiter Faktor: Sitzung der Anmeldung, bis der Code eingegeben ist
    val pendingSession: String? = null,
    // Weiteres Konto verbinden (zurück zum bisherigen möglich)
    val adding: Boolean = false,
    // Gescannter QR-Code, wartet auf „Ja, verbinden“ (Adresse sichtbar, Härtung H3)
    val pendingPairing: PairingLink? = null,
)

/** Verwaltung in der App (Schritt A8). [token] ist der entsperrte Vorstandsschlüssel im Speicher. */
data class AdminState(
    val overview: AdminOverview? = null,
    val sealed: Boolean = false,
    val token: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    // Härtung H7: Vorstandsanfragen mit diesem Schlüsselpaar unterschreiben
    val keyAlias: String? = null,
) {
    val visible: Boolean get() = overview?.allUnits?.isNotEmpty() == true
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val accounts = (app as HubiplanApp).accounts
    private val store get() = accounts.activeStore()

    /** Alle Konten auf dem Gerät (Platz, Konto), für den Wechsel unter „Konto“. */
    private val _accountList = MutableStateFlow<List<Pair<Int, Account>>>(emptyList())
    val accountList: StateFlow<List<Pair<Int, Account>>> = _accountList

    val activeSlot: StateFlow<Int> = accounts.active

    private val _screen = MutableStateFlow<Screen>(Screen.Loading)
    val screen: StateFlow<Screen> = _screen

    private val _connect = MutableStateFlow(ConnectState())
    val connect: StateFlow<ConnectState> = _connect

    // Fachfrage 16 (Audit 31.10.): zurückgeholte Belege nach erneuter Anmeldung (Hinweis) und
    // alte Belege in der Quarantäne (Rückfrage vor dem Löschen)
    private val _restored = MutableStateFlow(0)
    val restored: StateFlow<Int> = _restored
    private val _staleQuarantine = MutableStateFlow(0)
    val staleQuarantine: StateFlow<Int> = _staleQuarantine

    fun dismissRestored() {
        _restored.value = 0
    }

    /** Alte Belege behalten: erst beim nächsten Start wieder fragen. */
    fun keepStaleQuarantine() {
        _staleQuarantine.value = 0
    }

    fun discardStaleQuarantine() {
        _staleQuarantine.value = 0
        viewModelScope.launch(Dispatchers.IO) { runCatching { LocalAccountData.discardStale(context) } }
    }

    private val _theme = MutableStateFlow<Theme?>(null)
    val theme: StateFlow<Theme?> = _theme

    /** Wappen der gewählten Einheit (Kopfzeile); null, solange keins gespeichert ist. */
    private val _logo = MutableStateFlow<Bitmap?>(null)
    val logo: StateFlow<Bitmap?> = _logo

    /** Angebot „Verein auf den Startbildschirm legen“ für den gewählten Zugang zeigen? */
    private val _shortcutOffer = MutableStateFlow(false)
    val shortcutOffer: StateFlow<Boolean> = _shortcutOffer

    val settings: StateFlow<AppSettings> = accounts.active
        .flatMapLatest { accounts.store(it).settings }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val context get() = getApplication<Application>()

    private val _admin = MutableStateFlow(AdminState())
    val admin: StateFlow<AdminState> = _admin
    private var adminUsedAt = 0L

    private suspend fun loadAdmin(account: Account, target: AccountStore = store, stillCurrent: () -> Boolean = { true }) {
        val overview = runCatching { Api(account.server, account.token).adminOverview() }.getOrNull()
        if (!stillCurrent()) return
        val sealed = target.sealedAdminToken()
        if (overview != null && !overview.unlocked && sealed != null) target.setSealedAdminToken(null)
        val signed = target.adminSigned()
        if (!stillCurrent()) return
        val signedAlias = account.keyAlias?.takeIf { signed }?.let(DeviceKeys::adminAlias)
        _admin.value = AdminState(
            overview = overview,
            sealed = overview?.unlocked == true && sealed != null,
            keyAlias = signedAlias,
        )
    }

    /** Nach Fingerabdruck/PIN: Vorstandsschlüssel aus dem Tresor holen. */
    fun openAdmin() {
        viewModelScope.launch {
            val sealed = store.sealedAdminToken() ?: return@launch
            try {
                val token = AdminVault.open(sealed)
                adminUsedAt = SystemClock.elapsedRealtime()
                _admin.value = _admin.value.copy(token = token, error = null)
            } catch (e: AdminVault.LockedException) {
                _admin.value = _admin.value.copy(error = tr("Bitte mit Fingerabdruck oder Geräte-PIN entsperren."))
            } catch (e: AdminVault.GoneException) {
                store.setSealedAdminToken(null)
                _admin.value = _admin.value.copy(sealed = false, error = tr("Bitte die Verwaltung neu freischalten."))
            }
        }
    }

    /** Freischalten mit Passwort und Code (direkt nach Fingerabdruck/PIN aufrufen). */
    fun unlockAdmin(password: String, code: String) {
        val main = screen.value as? Screen.Main ?: return
        _admin.value = _admin.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                // Härtung H7: neues Vorstands-Schlüsselpaar, nur bei entsperrtem Telefon nutzbar
                val alias = main.account.keyAlias?.let(DeviceKeys::adminAlias)
                val adminKey = alias?.let {
                    withContext(Dispatchers.Default) {
                        runCatching {
                            DeviceKeys.create(it, admin = true, strongBox = DeviceKeys.hasStrongBox(context.packageManager))
                        }.getOrNull()
                    }
                } ?: ""
                val result = Api(main.account.server, main.account.token).unlockAdmin(password, code.trim(), adminKey)
                try {
                    store.setSealedAdminToken(AdminVault.seal(result.token), signed = result.signed && adminKey.isNotEmpty())
                } catch (e: Exception) {
                    // Ohne Tresor (keine Bildschirmsperre) nicht speichern und gleich wieder sperren
                    runCatching { Api(main.account.server, main.account.token).lockAdmin() }
                    _admin.value = _admin.value.copy(
                        busy = false,
                        error = tr("Für die Verwaltung braucht das Telefon eine Bildschirmsperre (PIN, Muster oder Fingerabdruck)."),
                    )
                    return@launch
                }
                adminUsedAt = SystemClock.elapsedRealtime()
                loadAdmin(main.account)
                _admin.value = _admin.value.copy(token = result.token)
            } catch (e: ApiException) {
                _admin.value = _admin.value.copy(busy = false, error = e.message)
            }
        }
    }

    /** Mehr als 5 Minuten ohne Verwaltung (monotone Uhr, unabhängig von der Uhrzeit des Telefons). */
    private fun adminIdle(): Boolean =
        SystemClock.elapsedRealtime() - adminUsedAt > AdminVault.UNLOCK_SECONDS * 1000L

    /** Nach mehr als 5 Minuten ohne Verwaltung: Schlüssel aus dem Speicher nehmen (neu entsperren). */
    fun checkAdminIdle() {
        if (_admin.value.token != null && adminIdle()) {
            _admin.value = _admin.value.copy(token = null)
        }
    }

    /** Api mit dem Vorstandsschlüssel (null: gesperrt oder zu lange nicht benutzt). */
    fun adminApi(): Api? {
        val main = screen.value as? Screen.Main ?: return null
        checkAdminIdle()
        val token = _admin.value.token ?: return null
        return Api(main.account.server, token, main.account.token, _admin.value.keyAlias)
    }

    /**
     * Echte Bedienung (Tippen, Tasten) verlängert die 5 Minuten; automatisches Nachladen nicht
     * (Fachfrage 18, 31.10.b).
     */
    fun userInteracted() {
        if (_admin.value.token != null && !adminIdle()) adminUsedAt = SystemClock.elapsedRealtime()
    }

    /** Verwaltung sofort sperren (Bildschirm aus, Telefon gesperrt). */
    fun lockAdminNow() {
        if (_admin.value.token != null) _admin.value = _admin.value.copy(token = null)
    }

    private var backgroundSince = 0L

    /** App geht in den Hintergrund (auch beim Sperren des Telefons). */
    fun wentToBackground() {
        backgroundSince = SystemClock.elapsedRealtime()
    }

    /**
     * Zurück im Vordergrund: Nach mehr als einer Minute im Hintergrund ist die Verwaltung
     * gesperrt. Die kurze Frist erlaubt das Belegfoto mit der Kamera-App.
     */
    fun cameToForeground() {
        val since = backgroundSince
        backgroundSince = 0L
        if (since > 0 && SystemClock.elapsedRealtime() - since > BACKGROUND_LOCK_MS) lockAdminNow()
        checkAdminIdle()
    }

    /**
     * Vor jeder Anfrage der Verwaltung: die Pause prüfen (verlängert wird nur durch Bedienung).
     * Nach mehr als 5 Minuten (auch bei offener Ansicht im Vordergrund) wird der Schlüssel
     * verworfen und die Anfrage nicht gesendet (Gesamtaudit 30.10., AND-S01).
     */
    fun useAdmin() {
        if (_admin.value.token == null || adminIdle()) {
            _admin.value = _admin.value.copy(token = null)
            throw ApiException(ADMIN_IDLE, tr("Die Verwaltung wurde nach 5 Minuten Pause gesperrt. Bitte neu entsperren."))
        }
    }

    /** Server meldet 401 für die Verwaltung: abgelaufen oder im Browser gesperrt. */
    fun adminRejected() {
        val main = screen.value as? Screen.Main ?: return
        viewModelScope.launch {
            store.setSealedAdminToken(null)
            loadAdmin(main.account)
        }
    }

    /** Verwaltung auf diesem Gerät sperren (Gerät bleibt angemeldet). */
    fun lockAdmin() {
        val main = screen.value as? Screen.Main ?: return
        viewModelScope.launch {
            runCatching { Api(main.account.server, main.account.token).lockAdmin() }
            store.setSealedAdminToken(null)
            loadAdmin(main.account)
        }
    }

    /** Darf dieses Konto Kurzmitteilungen senden (Vorstand, Zugführung)? */
    // Wählbare Sprachen (vom zuerst verbundenen Konto, Fachfrage F6); leer: keine Auswahl
    private val _languages = MutableStateFlow<List<de.hubiplan.app.data.LanguageOption>>(emptyList())
    val languages: StateFlow<List<de.hubiplan.app.data.LanguageOption>> = _languages

    /** Sprache des zuerst verbundenen Kontos lesen und übernehmen. */
    private suspend fun syncLanguage() {
        val first = accounts.allStores().firstNotNullOfOrNull { it.account.first() } ?: return
        val state = runCatching { Api(first.server, first.token).language() }.getOrNull() ?: return
        _languages.value = state.languages
        de.hubiplan.app.Texte.kontoSprache(context, state.language)
        // Namen der Benachrichtigungskanäle in der neuen Sprache
        de.hubiplan.app.sync.Notifications.createChannels(context)
    }

    /** Sprache im Konto ändern (gilt auch im Browser) und sofort anzeigen. */
    fun setLanguage(code: String) {
        viewModelScope.launch {
            val first = accounts.allStores().firstNotNullOfOrNull { it.account.first() } ?: return@launch
            val state = runCatching { Api(first.server, first.token).setLanguage(code) }.getOrNull()
            de.hubiplan.app.Texte.kontoSprache(context, state?.language ?: code)
            // Namen der Benachrichtigungskanäle in der neuen Sprache
            de.hubiplan.app.sync.Notifications.createChannels(context)
            state?.let { _languages.value = it.languages }
        }
    }

    private val _canSend = MutableStateFlow(false)
    val canSend: StateFlow<Boolean> = _canSend

    /** Mitteilung, die aus einer Benachrichtigung geöffnet werden soll (Zugang, Nummer). */
    private val _openRequest = MutableStateFlow<Pair<Int, Int>?>(null)
    val openRequest: StateFlow<Pair<Int, Int>?> = _openRequest

    fun openCircular(slot: Int, accessId: Int, circularId: Int) {
        _openRequest.value = accessId to circularId
        if (slot != accounts.active.value) {
            // Mitteilung eines anderen Kontos: dorthin wechseln, mit dem Zugang der Mitteilung
            viewModelScope.launch {
                accounts.store(slot).currentAccount() ?: return@launch
                accounts.store(slot).selectAccess(accessId, null)
                switchTo(slot)
            }
            return
        }
        val main = screen.value as? Screen.Main ?: return
        main.me?.portal?.firstOrNull { it.id == accessId }?.let { if (main.access?.id != it.id) selectAccess(it) }
    }

    fun openHandled() {
        _openRequest.value = null
    }

    /** Push über eine UnifiedPush-App oder Google einschalten oder (null) abschalten. */
    fun setPushDistributor(pkg: String?) {
        viewModelScope.launch {
            if (pkg == null) PushSetup.turnOff(context, store) else PushSetup.enable(context, store, pkg)
            // Ohne Push öfter abgleichen (UnifiedPush meldet seinen Endpunkt später; der Abgleich prüft erneut)
            SyncWorker.schedule(context)
        }
    }

    /** Nach Änderungen (Rückmeldung, Einstellung) Kalender und Hinweise aktualisieren. */
    fun syncSoon() = SyncWorker.now(context)

    fun setCalendarOn(on: Boolean) {
        viewModelScope.launch {
            store.setCalendarOn(on)
            if (!on) store.currentAccount()?.let { DeviceCalendar(context).removeCalendarsExcept(emptySet(), it.server) }
            syncSoon()
        }
    }

    fun setReminderMinutes(minutes: Int?) {
        viewModelScope.launch {
            store.setReminderMinutes(minutes)
            syncSoon()
        }
    }

    fun setRsvpHints(on: Boolean) {
        viewModelScope.launch { store.setRsvpHints(on) }
    }

    init {
        viewModelScope.launch {
            // Aktives Konto fehlt (abgemeldet), aber andere sind da: zum ersten wechseln
            if (store.currentAccount() == null) accounts.list().firstOrNull()?.let { accounts.setActive(it.first) }
            // Lokale Daten aus dem alten Ordnerschema übernehmen (AND-S02)
            val keys = accounts.list().map { (_, a) -> LocalAccountData.key(a.server, a.deviceId) }
            withContext(Dispatchers.IO) { runCatching { LocalAccountData.migrate(context, keys) } }
            _staleQuarantine.value = withContext(Dispatchers.IO) {
                runCatching { LocalAccountData.staleCount(context) }.getOrDefault(0)
            }
            _theme.value = store.cachedTheme.first()
            loadAccounts()
            val account = store.account.first()
            if (account == null) _screen.value = Screen.Connect else refresh(account)
        }
    }

    private suspend fun loadAccounts() {
        _accountList.value = accounts.list()
    }

    private suspend fun switchTo(slot: Int) {
        accounts.setActive(slot)
        _canSend.value = false
        _admin.value = AdminState()
        _theme.value = store.cachedTheme.first()
        _logo.value = null
        _shortcutOffer.value = false
        loadAccounts()
        val account = store.currentAccount()
        if (account == null) _screen.value = Screen.Connect else refresh(account)
    }

    /** Zu einem anderen Konto (andere Instanz) wechseln. */
    fun switchAccount(slot: Int) {
        if (slot == accounts.active.value) return
        viewModelScope.launch { switchTo(slot) }
    }

    /** Weiteres Konto verbinden (QR-Code oder Anmeldung einer anderen Instanz). */
    fun addAccount() {
        _connect.value = ConnectState(adding = true)
        _screen.value = Screen.Connect
    }

    /** Verbinden abbrechen: zurück zum bisherigen Konto. */
    fun cancelAdd() {
        viewModelScope.launch {
            _connect.value = ConnectState()
            val account = store.currentAccount()
            if (account == null) _screen.value = Screen.Connect else refresh(account)
        }
    }

    /** Konto ist weg (abgemeldet, ungültig): zum nächsten wechseln oder Verbinden zeigen. */
    private suspend fun dropActive(error: String?) = dropSlot(accounts.active.value, null, error)

    /**
     * Konto auf [slot] entfernen. Mit [expected] nur, wenn dort noch genau dieses Konto liegt:
     * Eine verspätete Abmeldung von A darf nie das inzwischen gewählte Konto B löschen
     * (Gesamtaudit 01.11., GA-F03). War [slot] nicht mehr aktiv, bleibt das aktive Konto stehen.
     */
    private suspend fun dropSlot(slot: Int, expected: Account?, error: String?) {
        val slotStore = accounts.store(slot)
        val current = slotStore.currentAccount()
        if (expected != null &&
            (current == null || current.server != expected.server || current.deviceId != expected.deviceId)
        ) {
            return
        }
        val wasActive = accounts.active.value == slot
        val server = current?.server
        // Selbst abgemeldet (nach der Rückfrage „senden oder verwerfen“): alle lokalen Daten
        // und Schlüssel löschen (AND-S02). Vom Server abgemeldet: ungesendete Belege in die
        // Quarantäne, sie kommen nach erneuter Anmeldung zurück (Fachfrage 16, Audit 31.10.)
        current?.let { runCatching { ClubShortcut.forget(context, it) } }
        current?.let {
            val key = LocalAccountData.key(it.server, it.deviceId)
            runCatching {
                if (error == null) LocalAccountData.forget(context, key)
                else LocalAccountData.retire(context, key, it.server, it.email)
            }
        }
        // Noch wartende Zu-/Absagen dieses Kontos verwerfen (C-F02)
        current?.let { runCatching { de.hubiplan.app.sync.RsvpActionReceiver.cancelFor(context, it) } }
        if (wasActive) _admin.value = AdminState()
        runCatching { PushSetup.forget(context, slotStore) }
        runCatching { server?.let { DeviceCalendar(context).removeCalendarsExcept(emptySet(), it) } }
        accounts.remove(slot)
        if (!wasActive) {
            loadAccounts()
            return
        }
        val next = accounts.list().firstOrNull()
        if (next != null) {
            switchTo(next.first)
        } else {
            SyncWorker.cancel(context)
            loadAccounts()
            _theme.value = null
            _logo.value = null
            _shortcutOffer.value = false
            _connect.value = ConnectState(error = error, server = server ?: "")
            _screen.value = Screen.Connect
        }
    }

    fun api(): Api? = (screen.value as? Screen.Main)?.account?.let { Api(it.server, it.token) }

    private val deviceName: String
        get() = listOf(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL)
            .distinct().joinToString(" ").take(100)

    // Jede Aktualisierung bekommt eine Nummer; eine ältere (z. B. vor einem Kontowechsel
    // gestartet) schreibt nach ihren Netzaufrufen nichts mehr (Gesamtaudit 31.10.b, C-F03)
    private var refreshGeneration = 0

    private suspend fun refresh(account: Account) {
        val generation = ++refreshGeneration
        val target = store // Konto dieser Aktualisierung, auch wenn inzwischen gewechselt wird
        val current = { generation == refreshGeneration }
        _screen.value = Screen.Main(account, null, null, offline = false)
        try {
            val me = Api(account.server, account.token).me()
            if (!current()) return
            val access = me.portal.firstOrNull { it.id == account.selectedAccess } ?: me.portal.firstOrNull()
            _screen.value = Screen.Main(account, me, access, offline = false)
            access?.let { applyAccess(it, target) }
            updateLogos(account, me.portal)
            if (!current()) return
            access?.let { showLogo(account, it) }
            SyncWorker.schedule(context)
            SyncWorker.now(context)
            syncLanguage()
            for (s in accounts.allStores()) runCatching { PushSetup.refresh(context, s) }
            val canSend = runCatching { Api(account.server, account.token).senders().senders.isNotEmpty() }.getOrDefault(false)
            if (!current()) return
            _canSend.value = canSend
            loadAdmin(account, target, current)
            // Härtung H7: Schlüsselpaar nachmelden, Geräteschlüssel erneuern, wenn fällig
            runCatching { KeyMaintenance(context, accounts).maintain(target, me.device) }
        } catch (e: ApiException) {
            if (e.status == 401) {
                // Gerät abgemeldet (im Browser, Passwort geändert, lange nicht genutzt). Nur
                // dieses Konto entfernen, nie ein inzwischen gewähltes anderes.
                val active = store.currentAccount()
                if (current() && active?.server == account.server && active.deviceId == account.deviceId) {
                    dropActive(e.message)
                }
            } else if (current()) {
                _screen.value = Screen.Main(account, null, null, offline = true)
            }
        }
    }

    fun reload() {
        val main = screen.value as? Screen.Main ?: return
        viewModelScope.launch { refresh(main.account) }
    }

    private suspend fun applyAccess(access: PortalAccess, target: AccountStore = store) {
        target.selectAccess(access.id, access.theme)
        if (target.slot == accounts.active.value) {
            _theme.value = access.theme
            target.currentAccount()?.let { showLogo(it, access) }
        }
    }

    /** Wappen aller Zugänge dieses Kontos laden, wenn neu oder geändert; Verknüpfungen anpassen. */
    private suspend fun updateLogos(account: Account, accesses: List<PortalAccess>) {
        val api = Api(account.server, account.token)
        withContext(Dispatchers.IO) {
            for (a in accesses) {
                val url = a.theme?.logoUrl
                if (ClubLogo.isCurrent(context, account, a.id, url)) continue
                val bytes = url?.let { api.image(it) }
                // Nicht erreichbar: beim nächsten Mal erneut versuchen
                if (url != null && bytes == null) continue
                runCatching { ClubLogo.save(context, account, a.id, url, bytes) }
            }
            runCatching { ClubShortcut.refresh(context, account, accesses) }
        }
    }

    private suspend fun showLogo(account: Account, access: PortalAccess) {
        _logo.value = withContext(Dispatchers.IO) { ClubLogo.load(context, account, access.id) }
        _shortcutOffer.value = ClubShortcut.supported(context) &&
            !ClubShortcut.isPinned(context, account, access.id) &&
            !shortcutDeclined(ClubShortcut.id(account, access.id))
    }

    private val shortcutPrefs get() = context.getSharedPreferences("verknuepfungen", android.content.Context.MODE_PRIVATE)

    private fun shortcutDeclined(id: String) = shortcutPrefs.getBoolean(id, false)

    /** Verknüpfung mit Wappen und Namen der gewählten Einheit auf den Startbildschirm legen. */
    fun pinShortcut(): Boolean {
        val main = screen.value as? Screen.Main ?: return false
        val access = main.access ?: return false
        val ok = ClubShortcut.request(context, main.account, access)
        // Einmal angeboten genügt; Android fragt selbst nach, ob sie wirklich angelegt wird
        dismissShortcutOffer()
        return ok
    }

    /** Angebot für diesen Zugang nicht mehr zeigen (unter „Konto“ bleibt der Knopf). */
    fun dismissShortcutOffer() {
        val main = screen.value as? Screen.Main ?: return
        val access = main.access ?: return
        shortcutPrefs.edit().putBoolean(ClubShortcut.id(main.account, access.id), true).apply()
        _shortcutOffer.value = false
    }

    /** Aus einer Verknüpfung: das Konto mit dieser Prüfsumme und den Zugang öffnen. */
    fun openShortcut(accountId: String, accessId: Int) {
        viewModelScope.launch {
            val slot = accounts.list().firstOrNull { (_, a) -> ClubShortcut.accountId(a) == accountId }?.first ?: return@launch
            if (slot != accounts.active.value) {
                accounts.store(slot).selectAccess(accessId, null)
                switchTo(slot)
                return@launch
            }
            val main = screen.value as? Screen.Main
            val target = main?.me?.portal?.firstOrNull { it.id == accessId }
            if (target != null) {
                if (main.access?.id != target.id) selectAccess(target)
            } else {
                // Noch beim Laden: Auswahl merken und neu laden (die laufende Aktualisierung verfällt)
                store.selectAccess(accessId, null)
                store.currentAccount()?.let { refresh(it) }
            }
        }
    }

    fun selectAccess(access: PortalAccess) {
        val main = screen.value as? Screen.Main ?: return
        _screen.value = main.copy(access = access)
        viewModelScope.launch { applyAccess(access) }
    }

    fun clearError() {
        _connect.value = _connect.value.copy(error = null)
    }

    fun scanned(text: String) {
        val link = PairingLink.parse(text)
        if (link == null) {
            _connect.value = _connect.value.copy(
                error = tr("Das ist kein QR-Code von HUBIplan. Bitte den Code unter „Mein Konto › App auf dem Telefon“ scannen."),
            )
            return
        }
        // Erst nachfragen: ein untergeschobener QR-Code einer fremden Adresse fällt so auf
        _connect.value = _connect.value.copy(pendingPairing = link, error = null)
    }

    fun confirmPairing() {
        val link = _connect.value.pendingPairing ?: return
        _connect.value = _connect.value.copy(pendingPairing = null)
        run(link.server) { api, key -> api.pair(code = link.code, deviceName = deviceName, appVersion = appVersion, deviceKey = key()) }
    }

    fun cancelPairing() {
        _connect.value = _connect.value.copy(pendingPairing = null)
    }

    fun login(serverInput: String, email: String, password: String) {
        val server = normalizeServer(serverInput)
        if (server == null) {
            _connect.value = _connect.value.copy(error = tr("Bitte die Adresse von HUBIplan angeben, z. B. verein.example.de."))
            return
        }
        _connect.value = _connect.value.copy(server = server)
        run(server) { api, key ->
            when (val step = api.login(email.trim(), password)) {
                is LoginStep.Done -> api.createDevice(step.sessionToken, deviceName, appVersion, key())
                is LoginStep.NeedsCode -> {
                    _connect.value = _connect.value.copy(pendingSession = step.sessionToken)
                    null
                }
            }
        }
    }

    fun secondFactor(code: String) {
        val state = _connect.value
        val session = state.pendingSession ?: return
        run(state.server) { api, key ->
            when (val step = api.secondFactor(session, code.trim())) {
                is LoginStep.Done -> api.createDevice(step.sessionToken, deviceName, appVersion, key())
                is LoginStep.NeedsCode -> throw ApiException(400, tr("Der Code stimmt nicht. Bitte erneut versuchen."))
            }
        }
    }

    fun cancelSecondFactor() {
        _connect.value = _connect.value.copy(pendingSession = null, error = null)
    }

    /** [action] bekommt die Api und eine Funktion, die das Geräteschlüsselpaar erzeugt (H7). */
    private fun run(server: String, action: suspend (Api, suspend () -> String) -> Paired?) {
        _connect.value = _connect.value.copy(busy = true, error = null, server = server)
        viewModelScope.launch {
            val alias = DeviceKeys.newAlias()
            var created = false
            val key: suspend () -> String = {
                // Ohne Keystore-Schlüssel (sehr alte Geräte) verbindet die App wie bisher
                withContext(Dispatchers.Default) {
                    runCatching {
                        DeviceKeys.create(alias, admin = false, strongBox = DeviceKeys.hasStrongBox(context.packageManager))
                    }.getOrNull()
                }?.also { created = true } ?: ""
            }
            try {
                val api = Api(server)
                val instance = api.instance()
                if (instance.product != "HUBIplan") throw ApiException(0, tr("Unter dieser Adresse läuft kein HUBIplan."))
                if (instance.minAppVersion > BuildConfig.VERSION_CODE) {
                    throw ApiException(0, tr("Bitte die App aktualisieren, diese Version wird von HUBIplan nicht mehr unterstützt."))
                }
                val paired = action(api, key)
                if (paired == null && created) DeviceKeys.delete(alias)
                if (paired != null) {
                    val slot = accounts.slotFor(server)
                    val target = accounts.store(slot)
                    target.currentAccount()?.let { old ->
                        // Dieselbe Instanz neu verbunden: altes Gerät abmelden
                        runCatching { PushSetup.disable(context, target) }
                        runCatching { Api(old.server, old.token).logout(old.deviceId) }
                    }
                    target.save(server, paired, alias.takeIf { created })
                    // Belege aus einer früheren Anmeldung desselben Mitglieds zurückholen (Fachfrage 16)
                    target.currentAccount()?.let { now ->
                        val back = withContext(Dispatchers.IO) {
                            runCatching {
                                LocalAccountData.adopt(context, LocalAccountData.key(now.server, now.deviceId), now.server, now.email)
                            }.getOrDefault(0)
                        }
                        if (back > 0) _restored.value = back
                    }
                    accounts.setActive(slot)
                    _connect.value = ConnectState()
                    _theme.value = null
                    loadAccounts()
                    refresh(target.currentAccount() ?: return@launch)
                } else {
                    _connect.value = _connect.value.copy(busy = false)
                }
            } catch (e: ApiException) {
                if (created) DeviceKeys.delete(alias)
                _connect.value = _connect.value.copy(busy = false, error = e.message)
            }
        }
    }

    /** Dieses Konto vom Gerät abmelden; andere Konten bleiben. */
    /** Noch nicht gesendete Buchungen und Quittungen dieses Kontos (UX-Audit 31.10. A14, F07). */
    suspend fun pendingLocalItems(): Int {
        val current = store.currentAccount() ?: return 0
        val key = LocalAccountData.key(current.server, current.deviceId)
        return withContext(Dispatchers.IO) {
            runCatching { DraftStore(context, key).count() + ReceiptStore(context, key).count() }.getOrDefault(0)
        }
    }

    /**
     * Vor dem Abmelden die unversandten Buchungen und Quittungen senden (Fachfrage 8, 31.10.b).
     * Rückgabe: Hinweis, wenn etwas übrig bleibt; null, wenn alles gesendet ist.
     */
    suspend fun sendPendingBeforeLogout(): String? {
        val current = store.currentAccount() ?: return null
        val api = adminApi()
            ?: return tr("Die Verwaltung ist gesperrt. Bitte unter „Verwaltung“ entsperren und dann noch einmal abmelden.")
        val key = LocalAccountData.key(current.server, current.deviceId)
        val units = _admin.value.overview?.allUnits.orEmpty()
        return withContext(Dispatchers.IO) {
            val drafts = DraftStore(context, key)
            val receipts = ReceiptStore(context, key)
            val ids = (drafts.list().map { it.unitId } + receipts.list().map { it.unitId }).distinct()
            for (id in ids) {
                val unit = units.firstOrNull { it.id == id } ?: continue
                val client = AdminClient(api, id, ::useAdmin, unit.scope, key)
                try {
                    sendDrafts(client, drafts)
                    sendReceipts(client, receipts)
                } catch (e: ApiException) {
                    return@withContext e.message ?: tr("Senden ist fehlgeschlagen.")
                }
            }
            val left = runCatching { drafts.count() + receipts.count() }.getOrDefault(0)
            if (left > 0) {
                trn("1 Vorgang konnte nicht gesendet werden (z. B. ohne Verbindung oder mit Fehler). Bitte unter Verwaltung › Kasse prüfen.", "{n} Vorgänge konnten nicht gesendet werden (z. B. ohne Verbindung oder mit Fehler). Bitte unter Verwaltung › Kasse prüfen.", left)
            } else {
                null
            }
        }
    }

    /** Plätze, deren Abmeldung gerade läuft (zweimal tippen meldet nicht zweimal ab). */
    private val loggingOut = mutableSetOf<Int>()

    fun logout() {
        val main = screen.value as? Screen.Main ?: return
        // Konto und Platz jetzt festhalten: Bis Push und Server antworten, kann ein anderes
        // Konto gewählt sein (GA-F03)
        val slot = accounts.active.value
        val slotStore = accounts.store(slot)
        val account = main.account
        if (!loggingOut.add(slot)) return
        viewModelScope.launch {
            try {
                // Auch ohne Netz lokal abmelden; der Server lässt den Schlüssel sonst nach 180 Tagen verfallen
                runCatching { PushSetup.disable(context, slotStore) }
                runCatching { Api(account.server, account.token).logout(account.deviceId) }
                dropSlot(slot, account, null)
            } finally {
                loggingOut.remove(slot)
            }
        }
    }

    /** Geräteschlüssel ungültig geworden (z. B. im Browser abgemeldet). */
    fun deviceRevoked(message: String?) {
        viewModelScope.launch { dropActive(message) }
    }

    private val appVersion: String get() = BuildConfig.VERSION_CODE.toString()
}

/** Länger im Hintergrund als das: Verwaltung beim Zurückkommen gesperrt (Fachfrage 18). */
const val BACKGROUND_LOCK_MS = 60_000L

/** Status der Ausnahme, wenn die Verwaltung wegen Pause gesperrt wurde (kein Serverfehler). */
const val ADMIN_IDLE = 423
