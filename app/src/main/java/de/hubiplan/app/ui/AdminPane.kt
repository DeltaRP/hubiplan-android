package de.hubiplan.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import de.hubiplan.app.data.AdminClient
import de.hubiplan.app.data.AdminTask
import de.hubiplan.app.data.AdminUnit
import de.hubiplan.app.data.Api
import de.hubiplan.app.data.ApiException
import de.hubiplan.app.data.CardCheck
import de.hubiplan.app.data.DraftStore
import de.hubiplan.app.data.ReceiptStore
import kotlinx.coroutines.launch

/**
 * Reiter „Verwaltung“ (Schritt A8): nur für Konten mit Verwaltungsrechten. Erst nach dem
 * Freischalten (Passwort + 2FA) und jedes Mal nach Fingerabdruck/PIN; keine Bildschirmfotos.
 */
@Composable
fun AdminPane(main: Screen.Main, vm: AppViewModel) {
    val context = LocalContext.current
    val state by vm.admin.collectAsStateWithLifecycle()
    // Im Verwaltungsbereich keine Bildschirmfotos und keine Vorschau im App-Wechsler
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    // Beim Öffnen und nach Rückkehr in die App: nach 5 Minuten Pause neu entsperren
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.checkAdminIdle()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Auch bei offener Ansicht im Vordergrund nach 5 Minuten Pause sperren (AND-S01)
    LaunchedEffect(Unit) {
        while (true) {
            vm.checkAdminIdle()
            kotlinx.coroutines.delay(15_000)
        }
    }
    val overview = state.overview ?: return Loading()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        when {
            !overview.twoFactor -> Notice(
                "Für die Verwaltung in der App ist die Zwei-Faktor-Anmeldung nötig. Bitte im Browser unter " +
                    "„Mein Konto › Anmeldung & Sicherheit“ einrichten, danach hier freischalten.",
                "Mein Konto öffnen",
            ) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${main.account.server}/mein-konto/anmeldung"))) }
            !DeviceUnlock.available(context) -> Notice(
                "Für die Verwaltung braucht das Telefon eine Bildschirmsperre (PIN, Muster oder Fingerabdruck).",
                null,
            ) {}
            !state.sealed -> UnlockForm(state, vm)
            state.token == null -> Column(
                Modifier.widthIn(max = 520.dp).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Verwaltung", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.asHeading())
                Text("Zum Öffnen mit Fingerabdruck, Gesicht oder Geräte-PIN entsperren.")
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.announce(urgent = true)) }
                Button(onClick = {
                    DeviceUnlock.ask(context, "Verwaltung öffnen", onSuccess = vm::openAdmin, onError = {})
                }) { Text("Entsperren") }
                LaunchedEffect(Unit) {
                    DeviceUnlock.ask(context, "Verwaltung öffnen", onSuccess = vm::openAdmin, onError = {})
                }
            }
            else -> AdminHome(overview.allUnits, overview.until, main, vm)
        }
    }
}

@Composable
private fun Notice(text: String, action: String?, onAction: () -> Unit) {
    Column(Modifier.widthIn(max = 520.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Verwaltung", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.asHeading())
        Text(text)
        action?.let { OutlinedButton(onClick = onAction) { Text(it) } }
    }
}

@Composable
private fun UnlockForm(state: AdminState, vm: AppViewModel) {
    val context = LocalContext.current
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Verwaltung freischalten", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.asHeading())
        Text(
            "Für Mitglieder, Termine und Kasse in der App bitte mit Passwort und Zwei-Faktor-Code bestätigen. " +
                "Die Freischaltung gilt 30 Tage auf diesem Telefon; geöffnet wird jedes Mal mit Fingerabdruck oder PIN.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        (error ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.announce(urgent = true)) }
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Passwort") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isDigit).take(8) },
            label = { Text("Code aus der Authenticator-App") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                error = null
                // Erst das Telefon entsperren: Der Tresor für den Schlüssel braucht es
                DeviceUnlock.ask(
                    context,
                    "Verwaltung freischalten",
                    onSuccess = { vm.unlockAdmin(password, code) },
                    onError = { error = it },
                )
            },
            enabled = !state.busy && password.isNotEmpty() && code.length >= 6,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Freischalten") }
    }
}

private enum class Section(val label: String) {
    TASKS("Aufgaben"),
    MEMBERS("Mitglieder"),
    EVENTS("Termine"),
    FINANCE("Kasse"),
    BILLING("Beiträge"),
    OCCASIONS("Anlässe"),
}

/** Bereiche je nach Rechten; Untereinheiten (Zugführung) haben Mitglieder und Termine. */
private fun sectionsFor(unit: AdminUnit): List<Section> {
    val p = unit.permissions
    if (unit.isDepartment) {
        return listOfNotNull(
            Section.TASKS,
            Section.MEMBERS.takeIf { p.any { it.startsWith("dept.members.") } },
            Section.EVENTS,
        )
    }
    val members = p.any { it.startsWith("members.") }
    return listOfNotNull(
        Section.TASKS,
        Section.MEMBERS.takeIf { members },
        Section.EVENTS,
        Section.FINANCE.takeIf { FINANCE_VIEW in p || FINANCE_EDIT in p },
        Section.BILLING.takeIf { BILLING_VIEW in p || BILLING_EDIT in p },
        Section.OCCASIONS.takeIf { members },
    )
}

@Composable
private fun AdminHome(units: List<AdminUnit>, until: String?, main: Screen.Main, vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var unitKey by rememberSaveable { mutableStateOf(units.first().key) }
    val unit = units.firstOrNull { it.key == unitKey } ?: units.first()
    val client = remember(unit.key) { vm.adminApi()?.let { AdminClient(it, unit.id, vm::useAdmin, unit.scope) } } ?: return
    val errors = remember { AdminErrors(onLocked = vm::adminRejected) }
    val sections = sectionsFor(unit)
    val hasMembers = !unit.isDepartment && unit.permissions.any { it.startsWith("members.") }
    // Entwürfe der Kasse: je Konto (Server und Gerät)
    val drafts = remember(main.account.server, main.account.deviceId) {
        DraftStore(context, "${main.account.server}-${main.account.deviceId}")
    }
    val receiptStore = remember(main.account.server, main.account.deviceId) {
        ReceiptStore(context, "${main.account.server}-${main.account.deviceId}")
    }
    var section by rememberSaveable(unit.key) { mutableStateOf(Section.TASKS) }
    var member by rememberSaveable(unit.key) { mutableStateOf<Int?>(null) }
    var requests by rememberSaveable(unit.key) { mutableStateOf(false) }
    var admitting by rememberSaveable(unit.key) { mutableStateOf(false) }
    var eventId by rememberSaveable(unit.key) { mutableStateOf<Int?>(null) }
    var eventStart by rememberSaveable(unit.key) { mutableStateOf<String?>(null) }
    var creatingEvent by rememberSaveable(unit.key) { mutableStateOf<Boolean?>(null) }
    var bookingId by rememberSaveable(unit.key) { mutableStateOf<Int?>(null) }
    var newBooking by rememberSaveable(unit.key) { mutableStateOf(false) }
    var newReceipt by rememberSaveable(unit.key) { mutableStateOf(false) }
    var invoiceId by rememberSaveable(unit.key) { mutableStateOf<Int?>(null) }
    var memberSearch by rememberSaveable(unit.key) { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var card by remember { mutableStateOf<CardCheck?>(null) }
    var cardError by remember { mutableStateOf<String?>(null) }

    // Nach jeder Änderung: Hinweis mit „Rückgängig“ (wie im Browser)
    val onChanged: OnChanged = { text, changeSet ->
        scope.launch {
            val result = snackbar.showSnackbar(
                text,
                actionLabel = if (changeSet != null) "Rückgängig" else null,
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed && changeSet != null) {
                try {
                    client.undo(changeSet)
                    reload++
                    snackbar.showSnackbar("Rückgängig gemacht")
                } catch (e: ApiException) {
                    snackbar.showSnackbar(errors.message(e))
                }
            }
        }
    }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val code = result.contents ?: return@rememberLauncherForActivityResult
        scope.launch {
            try {
                card = client.checkCard(code)
            } catch (e: ApiException) {
                cardError = errors.message(e)
            }
        }
    }

    // Android-Zurück geht eine Ebene zurück wie „‹ Zurück“ (UX-Audit 31.10. A01); aus einem
    // Formular erst nach Rückfrage, damit Eingaben nicht unbemerkt verloren gehen.
    var leaveForm by remember { mutableStateOf<(() -> Unit)?>(null) }
    val inForm = newBooking || newReceipt || admitting || creatingEvent != null
    val nested = inForm || invoiceId != null || bookingId != null || eventId != null || member != null || requests
    androidx.activity.compose.BackHandler(enabled = nested) {
        when {
            invoiceId != null -> { invoiceId = null; reload++ }
            newReceipt -> leaveForm = { newReceipt = false; reload++ }
            newBooking -> leaveForm = { newBooking = false }
            bookingId != null -> bookingId = null
            creatingEvent != null -> leaveForm = { creatingEvent = null }
            eventId != null -> { eventId = null; eventStart = null }
            admitting -> leaveForm = { admitting = false }
            member != null -> member = null
            requests -> requests = false
        }
    }
    leaveForm?.let { leave ->
        AlertDialog(
            onDismissRequest = { leaveForm = null },
            title = { Text("Eingaben verwerfen?") },
            text = { Text("Was du in diesem Formular eingegeben hast, geht verloren.") },
            confirmButton = { TextButton(onClick = { leaveForm = null; leave() }) { Text("Verwerfen") } },
            dismissButton = { TextButton(onClick = { leaveForm = null }) { Text("Weiter bearbeiten") } },
        )
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            UnitPicker(units, unit) { unitKey = it.key }
            if (member == null && !requests && !admitting && eventId == null && creatingEvent == null && bookingId == null && !newBooking && !newReceipt && invoiceId == null) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    sections.forEach { s ->
                        FilterChip(selected = section == s, onClick = { section = s }, label = { Text(s.label) })
                    }
                    if (hasMembers) TextButton(onClick = {
                        scanner.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt("Mitgliedsausweis scannen")
                                .setBeepEnabled(false)
                                .setOrientationLocked(false),
                        )
                    }) { Text("Ausweis prüfen") }
                }
            }
            HorizontalDivider()
            cardError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.announce(urgent = true).padding(16.dp)) }
            val open = member
            val openEvent = eventId
            val rsvpAllowed = creatingEvent
            val openBooking = bookingId
            val openInvoice = invoiceId
            when {
                openInvoice != null -> InvoiceScreen(client, openInvoice, BILLING_EDIT in unit.permissions, errors, onBack = {
                    invoiceId = null
                    reload++
                }, onChanged = onChanged)
                newReceipt -> ReceiptScreen(client, unit.name, receiptStore, errors, onBack = {
                    newReceipt = false
                    reload++
                }, onChanged = onChanged)
                newBooking -> BookingForm(client, unit.name, drafts, errors, onBack = { newBooking = false }) { text ->
                    newBooking = false
                    reload++
                    onChanged(text, null)
                }
                openBooking != null -> BookingScreen(client, openBooking, FINANCE_EDIT in unit.permissions, errors, onBack = { bookingId = null }, onChanged = onChanged)
                rsvpAllowed != null -> EventCreateScreen(client, errors, rsvpAllowed, onBack = { creatingEvent = null }) { id, text, changeSet ->
                    creatingEvent = null
                    eventId = id
                    eventStart = null
                    onChanged(text, changeSet)
                }
                openEvent != null -> EventScreen(client, openEvent, eventStart, errors, onBack = {
                    eventId = null
                    eventStart = null
                }, onChanged = onChanged, reload = reload)
                admitting -> AdmitScreen(client, errors, onBack = { admitting = false }) { id, text ->
                    admitting = false
                    member = id
                    onChanged(text, null)
                }
                open != null && unit.isDepartment -> DeptMemberScreen(client, unit.permissions, open, errors, onBack = { member = null }, onChanged = onChanged, reload = reload)
                open != null -> MemberScreen(client, unit.permissions, open, errors, onBack = { member = null }, onChanged = onChanged, reload = reload)
                requests -> ChangeRequestsScreen(client, errors, onBack = { requests = false }, onOpen = {
                    requests = false
                    member = it
                }, onChanged = onChanged)
                section == Section.MEMBERS && section in sections -> MemberList(
                    client,
                    errors,
                    onAdmit = if (!unit.isDepartment && MEMBERS_EDIT in unit.permissions) {
                        { admitting = true }
                    } else {
                        null
                    },
                    initialSearch = memberSearch,
                    onSearch = { memberSearch = it },
                ) { member = it }
                section == Section.OCCASIONS && hasMembers -> OccasionsSection(client, errors) { member = it }
                section == Section.FINANCE && section in sections -> FinanceSection(
                    client,
                    canEdit = FINANCE_EDIT in unit.permissions,
                    store = drafts,
                    receiptStore = receiptStore,
                    errors = errors,
                    reload = reload,
                    onNew = { newBooking = true },
                    onReceipt = { newReceipt = true },
                    onOpen = { bookingId = it },
                    onChanged = onChanged,
                )
                section == Section.BILLING && section in sections -> BillingSection(client, errors, reload) { invoiceId = it }
                section == Section.EVENTS -> EventsSection(client, errors, reload, onCreate = { creatingEvent = it }) { o ->
                    eventId = o.eventId
                    eventStart = if (o.isSeries) o.originalStart ?: o.startsAt else null
                }
                else -> TaskList(client, errors, until, vm, reload) { task ->
                    when {
                        task.kind == "change_request" -> requests = true
                        task.kind == "photo" && task.membership != null -> member = task.membership
                        task.kind == "event" && task.ref != null -> {
                            eventId = task.ref
                            eventStart = null
                        }
                        // Wechselanfragen: im Browser
                        else -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(main.account.server + task.link)))
                    }
                }
            }
        }
    }
    card?.let { check ->
        val server = main.account.server
        CardResultDialog(
            check,
            loadPublic = { path -> Api(server).image(path) },
            onOpen = {
                card = null
                member = it
            },
            onDismiss = { card = null },
        )
    }
}

@Composable
private fun TaskList(
    client: AdminClient,
    errors: AdminErrors,
    until: String?,
    vm: AppViewModel,
    reloadKey: Int,
    onOpen: (AdminTask) -> Unit,
) {
    var tasks by remember(client.unitId) { mutableStateOf<List<AdminTask>?>(null) }
    var error by remember(client.unitId) { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(client.unitId, reload, reloadKey) {
        val api = vm.adminApi() ?: return@LaunchedEffect
        // Untereinheit (Zugführung): keine Aufgabenliste, nur Mitglieder und Termine
        if (client.scope == "d") {
            tasks = emptyList()
            return@LaunchedEffect
        }
        try {
            tasks = api.adminTasks(client.unitId).items
            error = null
        } catch (e: ApiException) {
            error = errors.message(e)
        }
    }
    Column(Modifier.fillMaxSize()) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.announce(urgent = true).padding(16.dp)) }
        val list = tasks
        when {
            list == null && error == null -> Loading()
            // Fehler ohne Liste ist nicht „keine Aufgaben“ (UX-Audit 31.10. A07)
            list == null -> Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { reload++ }) { Text("Erneut laden") }
                Footer(until, vm) { reload++ }
            }
            list.isEmpty() -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Keine offenen Aufgaben.")
                Footer(until, vm) { reload++ }
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Text(
                        "Offene Aufgaben",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.asHeading().padding(start = 16.dp, top = 12.dp, end = 16.dp),
                    )
                }
                items(list) { task ->
                    ListItem(
                        overlineContent = { Text(task.kindLabel) },
                        headlineContent = { Text(task.title) },
                        supportingContent = { Text(task.detail) },
                        modifier = Modifier.clickable { onOpen(task) },
                    )
                    HorizontalDivider()
                }
                item { Box(Modifier.padding(16.dp)) { Footer(until, vm) { reload++ } } }
            }
        }
    }
}

@Composable
private fun Footer(until: String?, vm: AppViewModel, onReload: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onReload) { Text("Aktualisieren") }
        until?.let { Text("Freigeschaltet bis ${formatDate(it)}", style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = vm::lockAdmin) { Text("Verwaltung auf diesem Telefon sperren") }
    }
}

@Composable
private fun UnitPicker(units: List<AdminUnit>, current: AdminUnit, onPick: (AdminUnit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = 8.dp)) {
        // Welche Einheit verwaltet wird, steht ausdrücklich da (UX-Audit 31.10. A04)
        if (units.size == 1) {
            Text("Verwaltung · ${current.name}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(8.dp))
        } else {
            TextButton(onClick = { open = true }, modifier = Modifier.dropdown("Verwaltete Einheit", current.name, open)) {
                Text("Verwaltung · ${current.name} ▾", style = MaterialTheme.typography.titleMedium)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                units.forEach { u ->
                    DropdownMenuItem(text = { Text(u.name) }, onClick = {
                        open = false
                        onPick(u)
                    })
                }
            }
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
