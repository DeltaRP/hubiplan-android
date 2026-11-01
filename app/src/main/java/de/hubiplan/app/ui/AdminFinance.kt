package de.hubiplan.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import de.hubiplan.app.data.AdminClient
import de.hubiplan.app.data.ApiException
import de.hubiplan.app.data.BookingDraft
import de.hubiplan.app.data.BookingItem
import de.hubiplan.app.data.DraftStore
import de.hubiplan.app.data.FinanceAccount
import de.hubiplan.app.data.FinanceCategory
import de.hubiplan.app.data.ReceiptDraft
import de.hubiplan.app.data.ReceiptStore
import de.hubiplan.app.photo.PhotoPrep
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Verwaltung in der App, Schritt B4: Kasse mit Belegfoto

const val FINANCE_VIEW = "finance.view"
const val FINANCE_EDIT = "finance.edit"

/** Entwürfe senden: gesendete löschen, bei fehlendem Netz aufhören, sonst Fehler merken. */
suspend fun sendDrafts(client: AdminClient, store: DraftStore): Int {
    var sent = 0
    for (draft in store.list().filter { it.unitId == client.unitId }) {
        try {
            val jpeg = Base64.decode(draft.photo, Base64.NO_WRAP)
            client.book(draft, jpeg)
            store.delete(draft.key)
            sent++
        } catch (e: ApiException) {
            if (e.status == 0) break
            if (e.status == 401) throw e
            store.save(draft.copy(error = e.message))
        }
    }
    return sent
}

/** Kontostände, nicht gesendete Entwürfe und letzte Buchungen. */
@Composable
fun FinanceSection(
    client: AdminClient,
    canEdit: Boolean,
    store: DraftStore,
    receiptStore: ReceiptStore,
    errors: AdminErrors,
    reload: Int,
    onNew: () -> Unit,
    onReceipt: () -> Unit,
    onOpen: (Int) -> Unit,
    onChanged: OnChanged,
) {
    val scope = rememberCoroutineScope()
    var accounts by remember(client.unitId) { mutableStateOf<List<FinanceAccount>?>(null) }
    var bookings by remember(client.unitId) { mutableStateOf<List<BookingItem>>(emptyList()) }
    var count by remember(client.unitId) { mutableIntStateOf(0) }
    var page by remember(client.unitId) { mutableIntStateOf(1) }
    var hasMore by remember(client.unitId) { mutableStateOf(false) }
    var withoutReceipt by remember(client.unitId) { mutableStateOf(false) }
    // Entwürfe lesen und entschlüsseln nicht im UI-Thread (Gesamtaudit 30.10., AND-P01)
    var drafts by remember(client.unitId) { mutableStateOf<List<BookingDraft>>(emptyList()) }
    var error by remember(client.unitId) { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var sending by remember { mutableStateOf(false) }
    var receipts by remember(client.unitId) { mutableStateOf<List<ReceiptDraft>>(emptyList()) }
    var categories by remember(client.unitId) { mutableStateOf<List<FinanceCategory>>(emptyList()) }
    // Korrigieren und Verwerfen mit Rückfrage (UX-Audit 31.10. A10)
    var fixDraft by remember { mutableStateOf<BookingDraft?>(null) }
    var discardDraft by remember { mutableStateOf<BookingDraft?>(null) }
    var fixReceipt by remember { mutableStateOf<ReceiptDraft?>(null) }
    var discardReceipt by remember { mutableStateOf<ReceiptDraft?>(null) }
    // Unlesbare Entwürfe und Quittungen (Schlüssel weg): zählen und melden (GA-F04)
    var unreadable by remember { mutableIntStateOf(0) }
    var discardUnreadable by remember { mutableStateOf(false) }
    suspend fun reloadDrafts() {
        drafts = withContext(Dispatchers.IO) { store.list().filter { it.unitId == client.unitId } }
        unreadable = withContext(Dispatchers.IO) {
            runCatching { store.unreadable().size + receiptStore.unreadable().size }.getOrDefault(0)
        }
    }
    suspend fun reloadReceipts() {
        receipts = withContext(Dispatchers.IO) { receiptStore.list().filter { it.unitId == client.unitId } }
    }
    var sendingReceipts by remember { mutableStateOf(false) }

    fun sendPendingReceipts() {
        scope.launch {
            sendingReceipts = true
            try {
                val sent = withContext(Dispatchers.IO) { sendReceipts(client, receiptStore) }
                if (sent > 0) {
                    onChanged(if (sent == 1) "1 Quittung abgeschlossen" else "$sent Quittungen abgeschlossen", null)
                    refresh++
                }
            } catch (e: ApiException) {
                error = errors.message(e)
            }
            reloadReceipts()
            sendingReceipts = false
        }
    }

    fun send() {
        scope.launch {
            sending = true
            try {
                val sent = withContext(Dispatchers.IO) { sendDrafts(client, store) }
                if (sent > 0) {
                    onChanged(if (sent == 1) "1 Entwurf gebucht" else "$sent Entwürfe gebucht", null)
                    refresh++
                }
            } catch (e: ApiException) {
                error = errors.message(e)
            }
            reloadDrafts()
            sending = false
        }
    }
    // Liegen Entwürfe da, beim Öffnen gleich versuchen
    LaunchedEffect(client.unitId) {
        reloadDrafts()
        reloadReceipts()
        if (drafts.isNotEmpty() && canEdit) send()
        if (receipts.isNotEmpty() && canEdit) sendPendingReceipts()
    }
    LaunchedEffect(client.unitId, reload, refresh, withoutReceipt) {
        try {
            accounts = client.accounts()
            categories = runCatching { client.categories() }.getOrDefault(categories)
            val first = client.bookings(1, withoutReceipt)
            bookings = first.results
            count = first.count
            hasMore = first.next != null
            page = 1
            error = null
        } catch (e: ApiException) {
            error = errors.message(e)
        }
    }

    if (discardUnreadable) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { discardUnreadable = false },
            title = { Text("Unlesbare Vorgänge verwerfen?") },
            text = {
                Text(
                    "Diese Vorgänge lassen sich auf diesem Telefon nicht mehr öffnen und wurden nie gesendet. " +
                        "Nach dem Verwerfen müssen sie neu erfasst werden.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    discardUnreadable = false
                    scope.launch {
                        withContext(Dispatchers.IO) { store.discardUnreadable() + receiptStore.discardUnreadable() }
                        reloadDrafts()
                        reloadReceipts()
                    }
                }) { Text("Verwerfen") }
            },
            dismissButton = { TextButton(onClick = { discardUnreadable = false }) { Text("Behalten") } },
        )
    }

    fixDraft?.let { d ->
        BookingDraftFixDialog(d, accounts.orEmpty(), categories, onDismiss = { fixDraft = null }) { fixed ->
            fixDraft = null
            scope.launch {
                withContext(Dispatchers.IO) { store.save(fixed) }
                reloadDrafts()
                send()
            }
        }
    }
    discardDraft?.let { d ->
        DiscardDraftDialog("Entwurf", signed = false, onDismiss = { discardDraft = null }) {
            discardDraft = null
            store.delete(d.key)
            scope.launch { reloadDrafts() }
        }
    }
    fixReceipt?.let { d ->
        ReceiptDraftFixDialog(
            d,
            accounts.orEmpty().filter { it.kind == "cash" },
            categories,
            onDismiss = { fixReceipt = null },
        ) { fixed ->
            fixReceipt = null
            scope.launch {
                withContext(Dispatchers.IO) { receiptStore.save(fixed) }
                reloadReceipts()
                sendPendingReceipts()
            }
        }
    }
    discardReceipt?.let { d ->
        DiscardDraftDialog("Quittung", signed = true, onDismiss = { discardReceipt = null }) {
            discardReceipt = null
            receiptStore.delete(d.key)
            scope.launch { reloadReceipts() }
        }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Kasse", style = MaterialTheme.typography.titleMedium, modifier = Modifier.asHeading().weight(1f))
                TextButton(onClick = { refresh++ }) { Text("Aktualisieren") }
                if (canEdit) Button(onClick = onNew) { Text("Neue Buchung") }
            }
            if (canEdit) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = onReceipt) { Text("Quittung mit Unterschrift") }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.announce(urgent = true).padding(horizontal = 16.dp)) }
        }
        if (unreadable > 0) {
            item {
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (unreadable == 1) "1 Vorgang ist nicht mehr lesbar" else "$unreadable Vorgänge sind nicht mehr lesbar",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.asHeading().announce(urgent = true),
                        )
                        Text(
                            "Noch nicht gesendete Buchungen oder Quittungen lassen sich auf diesem Telefon nicht mehr " +
                                "öffnen, meist weil die Bildschirmsperre entfernt wurde. Sie bleiben gespeichert. " +
                                "Bitte prüfe in HUBIplan, was fehlt, und erfasse es dort neu.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (canEdit) TextButton(onClick = { discardUnreadable = true }) { Text("Unlesbare verwerfen …") }
                    }
                }
            }
        }
        if (receipts.isNotEmpty()) {
            item {
                PendingReceipts(
                    receipts,
                    sendingReceipts,
                    onSend = { sendPendingReceipts() },
                    onFix = { fixReceipt = it },
                    onDiscard = { discardReceipt = it },
                )
            }
        }
        if (drafts.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Noch nicht gesendet (${drafts.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.asHeading())
                        Text("Liegen verschlüsselt auf diesem Telefon, bis sie gebucht sind.", style = MaterialTheme.typography.bodySmall)
                        drafts.forEach { d ->
                            Text("${formatPlainDate(d.date)} · ${formatMoney(d.amount)} · ${d.text}")
                            d.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.announce(urgent = true)) }
                            if (d.error != null) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { fixDraft = d }) { Text("Korrigieren") }
                                    TextButton(onClick = { discardDraft = d }) { Text("Verwerfen …") }
                                }
                            }
                        }
                        if (sending) LinearProgressIndicator(Modifier.fillMaxWidth())
                        OutlinedButton(onClick = { send() }, enabled = !sending) { Text("Jetzt senden") }
                    }
                }
            }
        }
        val list = accounts
        if (list == null && error == null) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
        list?.let { accs ->
            items(accs, key = { "konto-${it.id}" }) { a ->
                ListItem(
                    headlineContent = { Text(a.name) },
                    supportingContent = { Text(a.kindLabel) },
                    trailingContent = { Text(formatMoney(a.balance), style = MaterialTheme.typography.titleSmall) },
                )
            }
        }
        item {
            HorizontalDivider()
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !withoutReceipt, onClick = { withoutReceipt = false }, label = { Text("Letzte Buchungen") })
                FilterChip(selected = withoutReceipt, onClick = { withoutReceipt = true }, label = { Text("Ohne Beleg") })
            }
            if (bookings.isEmpty() && list != null) Text(if (withoutReceipt) "Alle Buchungen haben einen Beleg." else "Noch keine Buchungen.", Modifier.padding(16.dp))
        }
        items(bookings, key = { "buchung-${it.id}" }) { b ->
            BookingRow(b) { onOpen(b.id) }
            HorizontalDivider()
        }
        if (hasMore) {
            item {
                TextButton(modifier = Modifier.padding(8.dp), onClick = {
                    scope.launch {
                        try {
                            val next = client.bookings(page + 1, withoutReceipt)
                            bookings = bookings + next.results
                            hasMore = next.next != null
                            page++
                        } catch (e: ApiException) {
                            error = errors.message(e)
                        }
                    }
                }) { Text("Weitere laden (${bookings.size} von $count)") }
            }
        }
    }
}

@Composable
private fun BookingRow(b: BookingItem, onClick: () -> Unit) {
    val sign = when (b.kind) {
        "income" -> "+"
        "expense" -> "−"
        else -> ""
    }
    ListItem(
        overlineContent = { Text("${formatPlainDate(b.date)} · Nr. ${b.displayNumber}") },
        headlineContent = { Text(b.text.ifBlank { b.kindLabel }) },
        supportingContent = {
            Text(
                listOfNotNull(
                    b.categoryName,
                    b.counterparty.ifBlank { null },
                    if (b.kind == "transfer") "${b.accountName} → ${b.toAccountName ?: ""}" else b.accountName,
                    if (b.receipts.isEmpty()) "ohne Beleg" else null,
                ).joinToString(" · "),
            )
        },
        trailingContent = { Text(sign + formatMoney(b.amount)) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Foto aufnehmen oder aus der Galerie: verkleinert und ohne EXIF (wie beim Mitgliederfoto). */
class PhotoPicker(val camera: () -> Unit, val gallery: () -> Unit)

@Composable
fun rememberPhotoPicker(onPhoto: (Bitmap) -> Unit, onError: (String) -> Unit): PhotoPicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cameraFile by remember { mutableStateOf<File?>(null) }
    fun load(uri: Uri) {
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { runCatching { PhotoPrep.load(context, uri) }.getOrNull() }
            cameraFile?.delete()
            cameraFile = null
            if (bitmap == null) onError("Das Bild ließ sich nicht öffnen.") else onPhoto(bitmap)
        }
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = cameraFile
        if (ok && file != null) load(Uri.fromFile(file)) else file?.delete()
    }
    fun startCamera() {
        val dir = File(context.cacheDir, "fotos").apply { mkdirs() }
        val file = File(dir, "beleg.jpg")
        cameraFile = file
        takePicture.launch(FileProvider.getUriForFile(context, "${context.packageName}.dateien", file))
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else onError("Ohne Zugriff auf die Kamera bitte ein Bild aus der Galerie wählen.")
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) load(uri) }
    return remember {
        PhotoPicker(
            camera = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    startCamera()
                } else {
                    permission.launch(Manifest.permission.CAMERA)
                }
            },
            gallery = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        )
    }
}

/** Eine Buchung mit Belegen; Beleg nachreichen. */
@Composable
fun BookingScreen(client: AdminClient, id: Int, canEdit: Boolean, errors: AdminErrors, onBack: () -> Unit, onChanged: OnChanged) {
    val scope = rememberCoroutineScope()
    var booking by remember(id) { mutableStateOf<BookingItem?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(id, refresh) {
        try {
            booking = client.booking(id)
            error = null
        } catch (e: ApiException) {
            error = errors.message(e)
        }
    }
    val picker = rememberPhotoPicker(onPhoto = { bitmap ->
        scope.launch {
            busy = true
            try {
                val jpeg = withContext(Dispatchers.Default) { PhotoPrep.jpeg(bitmap) }
                client.addReceipt(id, jpeg)
                onChanged("Beleg hinzugefügt", null)
                refresh++
            } catch (e: ApiException) {
                error = errors.message(e)
            }
            busy = false
        }
    }, onError = { error = it })

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onBack) { Text("‹ Zurück") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.announce(urgent = true)) }
        val b = booking ?: return@Column
        Text(b.text.ifBlank { b.kindLabel }, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.asHeading())
        Text("${b.kindLabel} · ${formatMoney(b.amount)}", style = MaterialTheme.typography.titleMedium)
        Text(
            listOfNotNull(
                "Nr. ${b.displayNumber}",
                formatPlainDate(b.date),
                b.categoryName,
                if (b.kind == "transfer") "${b.accountName} → ${b.toAccountName ?: ""}" else b.accountName,
                b.counterparty.ifBlank { null },
            ).joinToString(" · "),
        )
        Text("Ändern oder stornieren bitte im Browser.", style = MaterialTheme.typography.bodySmall)
        if (b.receipts.isEmpty()) Text("Noch kein Beleg.")
        b.receipts.forEach { r ->
            if (r.contentType.startsWith("image/")) {
                RemoteImage(r.id.toString(), { client.receipt(it.toInt()) }, r.name, Modifier.fillMaxWidth().heightIn(max = 480.dp))
            } else {
                Text("${r.name} (im Browser ansehen)")
            }
        }
        if (canEdit) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = picker.camera, enabled = !busy) { Text("Beleg fotografieren") }
                OutlinedButton(onClick = picker.gallery, enabled = !busy) { Text("Aus der Galerie") }
            }
        }
    }
}

private val DAY_INPUT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

/** Neue Buchung mit Belegfoto. Ohne Netz bleibt sie als Entwurf auf dem Telefon. */
@Composable
fun BookingForm(
    client: AdminClient,
    unitName: String,
    store: DraftStore,
    errors: AdminErrors,
    onBack: () -> Unit,
    onDone: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Eingaben überstehen Drehen und kurze Unterbrechungen (UX-Audit 31.10. A02)
    val key = rememberSaveable { UUID.randomUUID().toString() }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var kind by rememberSaveable { mutableStateOf("expense") }
    var amount by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().format(DAY_INPUT)) }
    var text by rememberSaveable { mutableStateOf("") }
    var counterparty by rememberSaveable { mutableStateOf("") }
    var accounts by remember { mutableStateOf<List<FinanceAccount>>(emptyList()) }
    var categories by remember { mutableStateOf<List<FinanceCategory>>(emptyList()) }
    var account by remember { mutableStateOf<FinanceAccount?>(null) }
    var category by remember { mutableStateOf<FinanceCategory?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            accounts = client.accounts()
            categories = client.categories()
            // Meist die Barkasse
            account = accounts.firstOrNull { it.kind == "cash" } ?: accounts.firstOrNull()
        } catch (e: ApiException) {
            error = errors.message(e)
        }
    }
    val picker = rememberPhotoPicker(onPhoto = { photo = it }, onError = { error = it })
    val amountValue = parseAmount(amount)
    val dateValue = parseGermanDate(date)
    val kindCategories = categoriesFor(categories, kind)
    val ready = photo != null && amountValue != null && dateValue != null && account != null && text.isNotBlank() &&
        (kindCategories.isEmpty() || category != null)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = onBack) { Text("‹ Zurück") }
        Text("Neue Buchung", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.asHeading())
        Text("Wird sofort gebucht, nur mit Belegfoto. Ändern und Storno im Browser.", style = MaterialTheme.typography.bodySmall)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.announce(urgent = true)) }
        photo?.let { Image(it.asImageBitmap(), contentDescription = "Beleg", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = picker.camera) { Text(if (photo == null) "Beleg fotografieren" else "Neu fotografieren") }
            OutlinedButton(onClick = picker.gallery) { Text("Aus der Galerie") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = kind == "expense", onClick = { kind = "expense"; category = null }, label = { Text("Ausgabe") })
            FilterChip(selected = kind == "income", onClick = { kind = "income"; category = null }, label = { Text("Einnahme") })
        }
        OutlinedTextField(
            value = amount,
            onValueChange = { amount = it },
            label = { Text("Betrag in €") },
            singleLine = true,
            isError = amount.isNotBlank() && amountValue == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = date,
            onValueChange = { date = it },
            label = { Text("Datum (TT.MM.JJJJ)") },
            singleLine = true,
            isError = dateValue == null,
            trailingIcon = { DatePickButton(date) { date = it } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(value = text, onValueChange = { text = it.take(200) }, label = { Text("Buchungstext") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = counterparty,
            onValueChange = { counterparty = it.take(200) },
            label = { Text(if (kind == "expense") "Empfänger (optional)" else "Zahler (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ChoicePicker("Konto", accounts.map { it to it.name }, account, { it.name }) { account = it }
        if (kindCategories.isNotEmpty()) ChoicePicker("Kategorie", kindCategories.map { it to it.name }, category, { it.name }) { category = it }
        Button(
            enabled = ready && !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val bitmap = photo ?: return@Button
                val acc = account ?: return@Button
                scope.launch {
                    busy = true
                    val jpeg = withContext(Dispatchers.Default) { PhotoPrep.jpeg(bitmap) }
                    val draft = BookingDraft(
                        key = key,
                        unitId = client.unitId,
                        unitName = unitName,
                        date = dateValue!!,
                        kind = kind,
                        amount = amountValue!!,
                        account = acc.id,
                        accountName = acc.name,
                        category = category?.id,
                        categoryName = category?.name.orEmpty(),
                        text = text.trim(),
                        counterparty = counterparty.trim(),
                        photo = Base64.encodeToString(jpeg, Base64.NO_WRAP),
                    )
                    // Erst dauerhaft als Entwurf sichern, dann senden (Audit 31.10., R-CL-01):
                    // bricht die App mitten im Aufruf ab, geht die Buchung nicht verloren; der
                    // Idempotency-Key verhindert doppeltes Buchen beim späteren Senden.
                    val saved = runCatching { withContext(Dispatchers.IO) { store.save(draft) } }.isSuccess
                    try {
                        val booked = client.book(draft, jpeg)
                        withContext(Dispatchers.IO) { store.delete(key) }
                        onDone("Gebucht als Nr. ${booked.displayNumber}")
                    } catch (e: ApiException) {
                        if (e.status == 0) {
                            // Ohne Netz: verschlüsselt aufheben, später senden
                            if (!saved) withContext(Dispatchers.IO) { store.save(draft) }
                            onDone("Ohne Verbindung: als Entwurf gespeichert, wird später gesendet")
                        } else {
                            // Abgelehnt: Entwurf wieder entfernen, die Eingaben stehen noch im Formular
                            withContext(Dispatchers.IO) { store.delete(key) }
                            error = errors.message(e)
                        }
                    }
                    busy = false
                }
            },
        ) { Text("Buchen") }
    }
}
