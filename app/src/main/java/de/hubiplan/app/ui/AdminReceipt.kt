package de.hubiplan.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import de.hubiplan.app.data.AdminClient
import de.hubiplan.app.data.ApiException
import de.hubiplan.app.data.CashReceiptItem
import de.hubiplan.app.data.FinanceAccount
import de.hubiplan.app.data.FinanceCategory
import de.hubiplan.app.data.ReceiptChoices
import de.hubiplan.app.data.ReceiptDraft
import de.hubiplan.app.data.ReceiptStore
import de.hubiplan.app.data.SignatureShape
import de.hubiplan.app.data.SignatureStrokes
import de.hubiplan.app.data.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime
import java.util.UUID

// Quittung mit Unterschrift (hubiplan konzepte/digitale-quittung.md): vorbereiten, beide
// unterschreiben (Gegenseite im Vollbild, quer), abschließen mit Belegnummer aus dem
// Kassenbuch. Ohne Netz bleibt die Quittung verschlüsselt auf dem Telefon, bis sie gesendet ist.

private val INK = Color(0xFF1A2A6C)

/** Unterschrift als Linienzüge in Bildschirmpunkten (keine Zeit- oder Druckwerte). */
private class Strokes {
    val lines = mutableStateListOf<List<Offset>>()
    var current by mutableStateOf<List<Offset>>(emptyList())
    var size by mutableStateOf(IntSize.Zero)

    val isEmpty: Boolean get() = lines.none { it.size > 1 }

    fun toStrokes(): SignatureStrokes? {
        if (isEmpty || size.width < 10 || size.height < 10) return null
        return SignatureStrokes(
            width = size.width,
            height = size.height,
            strokes = lines.filter { it.size > 1 }.map { line -> line.map { listOf(it.x.toInt(), it.y.toInt()) } },
        )
    }

    fun clear() {
        lines.clear()
        current = emptyList()
    }
}

/** Zeichenfeld: Finger oder Stift, Linie unten mit Beschriftung. */
@Composable
private fun SignaturePad(strokes: Strokes, label: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(Color.White, RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFFBBBBBB), RoundedCornerShape(8.dp)),
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { strokes.size = it }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val points = mutableListOf(down.position)
                        strokes.current = points.toList()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            val p = change.position
                            if (p.x in 0f..size.width.toFloat() && p.y in 0f..size.height.toFloat()) {
                                points += p
                                strokes.current = points.toList()
                            }
                        }
                        strokes.lines += points.toList()
                        strokes.current = emptyList()
                    }
                },
        ) {
            val lineY = size.height - 28.dp.toPx()
            drawLine(Color(0xFF999999), Offset(16.dp.toPx(), lineY), Offset(size.width - 16.dp.toPx(), lineY), 1.dp.toPx())
            val style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            (strokes.lines + listOf(strokes.current)).forEach { line ->
                if (line.size < 2) return@forEach
                val path = Path().apply {
                    moveTo(line[0].x, line[0].y)
                    line.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(path, INK, style = style)
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF666666),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 8.dp),
        )
    }
}

/** Gespeicherte oder gezeichnete Unterschrift anzeigen (auf die Tinte zugeschnitten). */
@Composable
private fun SignatureView(lines: List<List<Pair<Float, Float>>>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val all = lines.flatten()
        if (all.isEmpty()) return@Canvas
        val minX = all.minOf { it.first }
        val maxX = all.maxOf { it.first }
        val minY = all.minOf { it.second }
        val maxY = all.maxOf { it.second }
        val pad = 6.dp.toPx()
        val scale = minOf((size.width - 2 * pad) / maxOf(maxX - minX, 1f), (size.height - 2 * pad) / maxOf(maxY - minY, 1f))
        val offX = (size.width - (maxX - minX) * scale) / 2
        val offY = (size.height - (maxY - minY) * scale) / 2
        val style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        lines.filter { it.size > 1 }.forEach { line ->
            val path = Path().apply {
                line.forEachIndexed { i, (x, y) ->
                    val px = offX + (x - minX) * scale
                    val py = offY + (y - minY) * scale
                    if (i == 0) moveTo(px, py) else lineTo(px, py)
                }
            }
            drawPath(path, INK, style = style)
        }
    }
}

private fun SignatureStrokes.lines(): List<List<Pair<Float, Float>>> =
    strokes.map { line -> line.map { it[0].toFloat() to it[1].toFloat() } }

private fun SignatureShape.lines(): List<List<Pair<Float, Float>>> =
    s.map { flat -> flat.chunked(2).filter { it.size == 2 }.map { it[0].toFloat() to it[1].toFloat() } }

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/**
 * Vollbild zum Unterschreiben: quer, ohne Statusleiste, oben steht, was bestätigt wird.
 * „Zurück“ fragt, ob die Unterschrift verworfen werden soll.
 */
@Composable
private fun SignatureFullscreen(title: String, text: String, onConfirm: (SignatureStrokes) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val strokes = remember { Strokes() }
    var askCancel by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        // Kein erzwungenes Querformat (WCAG 1.3.4): wer das Telefon nicht drehen kann,
        // unterschreibt hochkant; im Hochformat steht ein Tipp (A-08)
        val activity = context.activity()
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    BackHandler {
        if (strokes.isEmpty) onCancel() else askCancel = true
    }
    Column(
        Modifier.fillMaxSize().background(Color.White).safeDrawingPadding().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = Color(0xFF222222))
        if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT) {
            Text("Tipp: Telefon quer halten, dann ist mehr Platz.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF444444))
        }
        SignaturePad(strokes, title, Modifier.fillMaxWidth().weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = { strokes.clear() }, enabled = !strokes.isEmpty) { Text("Löschen") }
            OutlinedButton(onClick = { if (strokes.isEmpty) onCancel() else askCancel = true }) { Text("Abbrechen") }
            Button(onClick = { strokes.toStrokes()?.let(onConfirm) }, enabled = !strokes.isEmpty) { Text("Bestätigen") }
        }
    }
    if (askCancel) {
        AlertDialog(
            onDismissRequest = { askCancel = false },
            title = { Text("Unterschrift verwerfen?") },
            confirmButton = { TextButton(onClick = onCancel) { Text("Verwerfen") } },
            dismissButton = { TextButton(onClick = { askCancel = false }) { Text("Weiter unterschreiben") } },
        )
    }
}

private fun now(): String = OffsetDateTime.now().withNano(0).toString()

/** Was die Gegenseite mit ihrer Unterschrift bestätigt (wie auf dem Server). */
fun confirmationText(receives: Boolean, amount: String, unitName: String, purpose: String): String {
    val money = formatMoney(amount)
    val text = if (receives) "Ich habe $money an $unitName gezahlt." else "Ich habe $money von $unitName erhalten."
    return "$text Zweck: $purpose"
}

/** Quittungen, die auf dem Telefon warten, senden und abschließen. Rückgabe: wie viele. */
suspend fun sendReceipts(client: AdminClient, store: ReceiptStore): Int {
    var sent = 0
    for (draft in store.list().filter { it.unitId == client.unitId }) {
        var current = draft
        try {
            client.submitReceipt(current) { id ->
                current = current.copy(serverId = id)
                store.save(current)
            }
            store.delete(draft.key)
            sent++
        } catch (e: ApiException) {
            if (e.status == 0) break
            if (e.status == 401) throw e
            store.save(current.copy(error = e.message))
        }
    }
    return sent
}

/** Quittung ausstellen: Angaben, zwei Unterschriften, Bestätigung, Abschluss. */
@Composable
fun ReceiptScreen(
    client: AdminClient,
    unitName: String,
    store: ReceiptStore,
    errors: AdminErrors,
    onBack: () -> Unit,
    onChanged: OnChanged,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var choices by remember(client.unitId) { mutableStateOf(store.choices(client.unitId)) }
    var offline by remember { mutableStateOf(false) }
    var receives by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var purpose by rememberSaveable { mutableStateOf("") }
    var accountId by rememberSaveable { mutableStateOf<Int?>(null) }
    var categoryId by rememberSaveable { mutableStateOf<Int?>(null) }
    // Unterschriften als JSON (übersteht das Drehen ins Querformat)
    var treasurerJson by rememberSaveable { mutableStateOf<String?>(null) }
    var treasurerAt by rememberSaveable { mutableStateOf("") }
    var useStored by rememberSaveable { mutableStateOf(true) }
    var otherJson by rememberSaveable { mutableStateOf<String?>(null) }
    var otherAt by rememberSaveable { mutableStateOf("") }
    var declined by rememberSaveable { mutableStateOf("") }
    var signing by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var sendMail by rememberSaveable { mutableStateOf(true) }
    var askDecline by remember { mutableStateOf(false) }
    var declineText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<CashReceiptItem?>(null) }
    var waiting by remember { mutableStateOf(false) }

    LaunchedEffect(client.unitId) {
        try {
            val accounts = client.accounts().filter { it.kind == "cash" }
            val categories = client.categories()
            // Fehler beim Laden heißt nicht „keine hinterlegt“ (Gesamtaudit 30.10., AND-F02)
            val signature = try {
                client.storedSignature().signature
            } catch (e: ApiException) {
                if (e.status == 0) throw e
                error = "Die hinterlegte Unterschrift ließ sich nicht laden: " + errors.message(e)
                choices?.signature
            }
            val fresh = ReceiptChoices(accounts, categories, signature)
            choices = fresh
            withContext(Dispatchers.IO) { store.saveChoices(client.unitId, fresh) }
            offline = false
        } catch (e: ApiException) {
            if (e.status == 0) offline = true else error = errors.message(e)
        }
    }

    val c = choices
    val accounts: List<FinanceAccount> = c?.accounts.orEmpty()
    val kind = if (receives) "income" else "expense"
    val categories: List<FinanceCategory> = c?.categories.orEmpty().filter { it.kind == kind || it.kind == "both" }
    val account = accounts.firstOrNull { it.id == accountId } ?: accounts.singleOrNull()
    val category = categories.firstOrNull { it.id == categoryId }
    val stored = c?.signature
    val treasurer = treasurerJson?.let { json.decodeFromString(SignatureStrokes.serializer(), it) }
    val other = otherJson?.let { json.decodeFromString(SignatureStrokes.serializer(), it) }
    val parsedAmount = parseAmount(amount)
    val treasurerReady = treasurer != null || (useStored && stored != null)
    val otherReady = other != null || (receives && declined.isNotBlank())
    val formReady = name.isNotBlank() && purpose.isNotBlank() && parsedAmount != null && account != null && category != null

    // Ändern sich die unterschriebenen Angaben, gelten die Unterschriften nicht mehr
    fun signedChanged() {
        treasurerJson = null
        otherJson = null
        declined = ""
        confirmed = false
    }

    val signRole = signing
    if (signRole != null) {
        val text = if (signRole == "treasurer") {
            if (receives) "${formatMoney(parsedAmount)} von $name erhalten. Zweck: $purpose" else "${formatMoney(parsedAmount)} an $name ausgezahlt. Zweck: $purpose"
        } else {
            confirmationText(receives, parsedAmount ?: "0", unitName, purpose)
        }
        SignatureFullscreen(
            title = if (signRole == "treasurer") "Kassierer" else name,
            text = text,
            onConfirm = { s ->
                val encoded = json.encodeToString(SignatureStrokes.serializer(), s)
                if (signRole == "treasurer") {
                    treasurerJson = encoded
                    treasurerAt = now()
                    useStored = false
                } else {
                    otherJson = encoded
                    otherAt = now()
                    declined = ""
                }
                signing = null
            },
            onCancel = { signing = null },
        )
        return
    }

    fun finish() {
        val acc = account ?: return
        val cat = category ?: return
        val draft = ReceiptDraft(
            key = UUID.randomUUID().toString(),
            finalizeKey = UUID.randomUUID().toString(),
            unitId = client.unitId,
            direction = if (receives) "in" else "out",
            counterpartyName = name.trim(),
            email = email.trim(),
            amount = parsedAmount ?: return,
            purpose = purpose.trim(),
            account = acc.id,
            accountName = acc.name,
            category = cat.id,
            categoryName = cat.name,
            useStored = treasurer == null,
            treasurer = treasurer,
            treasurerAt = treasurerAt,
            counterparty = other,
            counterpartyAt = otherAt,
            declined = if (other == null) declined else "",
            sendMail = sendMail && email.isNotBlank(),
        )
        scope.launch {
            busy = true
            error = null
            withContext(Dispatchers.IO) { store.save(draft) }
            var current = draft
            try {
                val result = client.submitReceipt(current) { id ->
                    current = current.copy(serverId = id)
                    store.save(current)
                }
                withContext(Dispatchers.IO) { store.delete(draft.key) }
                done = result
                onChanged("Quittung ${result.number} abgeschlossen", null)
            } catch (e: ApiException) {
                if (e.status == 0) {
                    waiting = true
                } else {
                    // Noch nicht auf dem Server: Entwurf verwerfen, die Angaben bleiben im Formular.
                    // Schon angelegt: in der Kasse mit Fehler stehen lassen (dort verwerfen oder erneut senden)
                    withContext(Dispatchers.IO) {
                        if (current.serverId == null) store.delete(draft.key) else store.save(current.copy(error = e.message))
                    }
                    error = errors.message(e)
                }
            }
            busy = false
        }
    }

    fun pdf(id: Int, share: Boolean, number: String) {
        scope.launch {
            busy = true
            val bytes = client.cashReceiptPdf(id)
            if (bytes == null) {
                error = "Die Quittung ließ sich nicht laden."
            } else {
                val safe = number.replace(Regex("[^A-Za-z0-9-]"), "-")
                val file = withContext(Dispatchers.IO) {
                    ReceiptCache.write(context, client.accountKey, "Quittung-$safe.pdf", bytes)
                }
                openPdf(context, file, share)?.let { error = it }
            }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onBack) { Text("‹ Zurück") }
        Text("Quittung", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.asHeading())
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.announce(urgent = true)) }

        val result = done
        if (result != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Quittung ${result.number} abgeschlossen", style = MaterialTheme.typography.titleMedium, modifier = Modifier.asHeading())
                    Text("${formatMoney(result.amount)} · ${result.counterpartyName}")
                    Text("Gebucht in der ${result.accountName}. Das PDF hängt als Beleg an der Buchung.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { pdf(result.id, true, result.number) }, enabled = !busy) { Text("Quittung teilen") }
                        OutlinedButton(onClick = { pdf(result.id, false, result.number) }, enabled = !busy) { Text("Ansehen") }
                    }
                    TextButton(onClick = onBack) { Text("Fertig") }
                }
            }
            return@Column
        }
        if (waiting) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ohne Netz gespeichert", style = MaterialTheme.typography.titleMedium, modifier = Modifier.asHeading())
                    Text(
                        "Die Quittung liegt verschlüsselt auf diesem Telefon und bekommt ihre Belegnummer, sobald wieder Netz da ist " +
                            "(unter Kasse, spätestens nach 30 Tagen). Das Datum ist der Zeitpunkt der Unterschrift.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onBack) { Text("Fertig") }
                }
            }
            return@Column
        }
        if (c == null) {
            if (offline) Text("Ohne Netz und ohne gespeicherte Auswahl. Bitte einmal mit Netz öffnen.")
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            return@Column
        }
        if (offline) Text("Ohne Netz: Die Quittung wird gespeichert und später abgeschlossen.", style = MaterialTheme.typography.bodySmall)
        if (accounts.isEmpty()) {
            Text("Es gibt keine Barkasse. Bitte im Browser unter Finanzen › Einrichtung › Konten eine anlegen.")
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = receives, onClick = {
                if (!receives) {
                    receives = true
                    categoryId = null
                    signedChanged()
                }
            }, label = { Text("Verein erhält Geld") })
            FilterChip(selected = !receives, onClick = {
                if (receives) {
                    receives = false
                    categoryId = null
                    signedChanged()
                }
            }, label = { Text("Verein zahlt aus") })
        }
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it
                signedChanged()
            },
            label = { Text(if (receives) "Zahler (Name)" else "Empfänger (Name)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = amount,
            onValueChange = {
                amount = it
                signedChanged()
            },
            label = { Text("Betrag in €") },
            singleLine = true,
            isError = amount.isNotBlank() && parsedAmount == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = purpose,
            onValueChange = {
                purpose = it
                signedChanged()
            },
            label = { Text("Zweck") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (accounts.size > 1) {
            ChoicePicker("Barkasse", accounts.map { it to it.name }, account, { it.name }) { accountId = it.id }
        } else {
            Text("in die ${accounts.first().name}", style = MaterialTheme.typography.bodySmall)
        }
        ChoicePicker("Kategorie", categories.map { it to it.name }, category, { it.name }) { categoryId = it.id }
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("E-Mail für das PDF (optional)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )

        Text(if (receives) "Betrag erhalten (Kassierer)" else "Betrag ausgezahlt (Kassierer)", style = MaterialTheme.typography.labelMedium)
        SignatureBox(
            lines = treasurer?.lines() ?: if (useStored) stored?.lines() else null,
            hint = "Antippen zum Unterschreiben",
            enabled = formReady && !busy,
        ) { signing = "treasurer" }
        if (treasurer == null && useStored && stored != null) {
            Text("Deine hinterlegte Unterschrift. Antippen, um neu zu unterschreiben.", style = MaterialTheme.typography.bodySmall)
        }

        Text(if (receives) "Betrag gezahlt: ${name.ifBlank { "Zahler" }}" else "Betrag erhalten: ${name.ifBlank { "Empfänger" }}", style = MaterialTheme.typography.labelMedium)
        if (other == null && declined.isNotBlank()) {
            Text("ohne Unterschrift: $declined", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { declined = "" }) { Text("Doch unterschreiben lassen") }
        } else {
            SignatureBox(
                lines = other?.lines(),
                hint = "Antippen: im Vollbild unterschreiben lassen",
                enabled = formReady && !busy,
            ) { signing = "counterparty" }
            if (receives && other == null) {
                TextButton(onClick = {
                    declineText = ""
                    askDecline = true
                }) { Text("Zahler unterschreibt nicht") }
            }
        }

        LabeledCheckbox(
            if (receives) "Ich bestätige, ${formatMoney(parsedAmount)} erhalten zu haben." else "Ich bestätige, ${formatMoney(parsedAmount)} ausgezahlt zu haben.",
            confirmed,
            { confirmed = it },
        )
        if (email.isNotBlank()) {
            LabeledCheckbox("PDF an ${email.trim()} senden", sendMail, { sendMail = it })
        }
        Button(
            onClick = { finish() },
            enabled = !busy && formReady && treasurerReady && otherReady && confirmed,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Quittung abschließen") }
        Text(
            "Ändern sich Betrag, Name, Zweck oder Richtung, müssen beide neu unterschreiben. Abgeschlossene Quittungen lassen sich nur im Browser stornieren.",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    if (askDecline) {
        AlertDialog(
            onDismissRequest = { askDecline = false },
            title = { Text("Zahler unterschreibt nicht") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Dann gilt nur deine Unterschrift als Kassierer, wie bei einer Papierquittung.")
                    OutlinedTextField(value = declineText, onValueChange = { declineText = it }, label = { Text("Grund") })
                }
            },
            confirmButton = {
                TextButton(enabled = declineText.trim().length >= 3, onClick = {
                    declined = declineText.trim()
                    otherJson = null
                    askDecline = false
                }) { Text("Übernehmen") }
            },
            dismissButton = { TextButton(onClick = { askDecline = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun SignatureBox(lines: List<List<Pair<Float, Float>>>?, hint: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(110.dp)
            .background(Color.White, RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFFBBBBBB), RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (lines != null && lines.isNotEmpty()) {
            SignatureView(lines, Modifier.fillMaxSize())
        } else {
            Text(if (enabled) hint else "Erst Name, Betrag, Zweck und Kategorie angeben", color = Color(0xFF666666))
        }
    }
}

/** Kasse: wartende Quittungen (ohne Netz unterschrieben). */
@Composable
fun PendingReceipts(
    drafts: List<ReceiptDraft>,
    sending: Boolean,
    onSend: () -> Unit,
    onFix: (ReceiptDraft) -> Unit,
    onDiscard: (ReceiptDraft) -> Unit,
) {
    if (drafts.isEmpty()) return
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Quittungen ohne Belegnummer (${drafts.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.asHeading())
            Text("Unterschrieben, liegen verschlüsselt auf diesem Telefon, bis sie abgeschlossen sind.", style = MaterialTheme.typography.bodySmall)
            drafts.forEach { d ->
                Text("${formatPlainDate(d.counterpartyAt.ifBlank { d.treasurerAt })} · ${formatMoney(d.amount)} · ${d.counterpartyName}")
                d.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.announce(urgent = true))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Schon auf dem Server angelegt: dort ist die Zuordnung festgelegt
                        if (d.serverId == null) OutlinedButton(onClick = { onFix(d) }) { Text("Korrigieren") }
                        TextButton(onClick = { onDiscard(d) }) { Text("Verwerfen …") }
                    }
                }
            }
            if (sending) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(onClick = onSend, enabled = !sending) { Text("Jetzt senden") }
        }
    }
}
