package de.hubiplan.app.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.hubiplan.app.data.ReceiptCache
import androidx.core.content.FileProvider
import de.hubiplan.app.data.AdminClient
import de.hubiplan.app.data.ApiException
import de.hubiplan.app.data.BillingAccount
import de.hubiplan.app.data.InvoiceItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import de.hubiplan.app.tr

// Verwaltung in der App, Schritt B5: offene Rechnungen suchen, bar kassieren, Quittung teilen


private val DAY_INPUT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")


/** Rechnungen der Einheit: zuerst die offenen, Suche nach Name, Betreff oder Nummer. */
@Composable
fun BillingSection(client: AdminClient, errors: AdminErrors, reload: Int, onOpen: (Int) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var search by remember(client.unitId) { mutableStateOf("") }
    var openOnly by remember(client.unitId) { mutableStateOf(true) }
    var rows by remember(client.unitId) { mutableStateOf<List<InvoiceItem>?>(null) }
    var count by remember(client.unitId) { mutableIntStateOf(0) }
    var page by remember(client.unitId) { mutableIntStateOf(1) }
    var hasMore by remember(client.unitId) { mutableStateOf(false) }
    var error by remember(client.unitId) { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { ReceiptCache.clear(context) } }
    LaunchedEffect(client.unitId, search, openOnly, reload) {
        delay(300) // erst suchen, wenn die Eingabe ruht
        try {
            val first = client.invoices(search, 1, openOnly)
            rows = first.results
            count = first.count
            hasMore = first.next != null
            page = 1
            error = null
        } catch (e: ApiException) {
            error = errors.message(e)
        }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text(tr("Name, Betreff oder Rechnungsnummer")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = openOnly, onClick = { openOnly = true }, label = { Text(tr("Offen")) })
                    FilterChip(selected = !openOnly, onClick = { openOnly = false }, label = { Text(tr("Alle")) })
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.announce(urgent = true)) }
                val list = rows
                if (list == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (list != null) {
                    Text(
                        when {
                            list.isEmpty() && openOnly -> tr("Keine offenen Rechnungen gefunden.")
                            list.isEmpty() -> tr("Keine Rechnungen gefunden.")
                            else -> "$count Rechnungen"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        items(rows.orEmpty(), key = { "rechnung-${it.id}" }) { inv ->
            InvoiceRow(inv) { onOpen(inv.id) }
            HorizontalDivider()
        }
        if (hasMore) {
            item {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            val next = client.invoices(search, page + 1, openOnly)
                            rows = rows.orEmpty() + next.results
                            hasMore = next.next != null
                            page++
                        } catch (e: ApiException) {
                            error = errors.message(e)
                        }
                    }
                }, modifier = Modifier.padding(8.dp)) { Text(tr("Weitere laden")) }
            }
        }
        item {
            Text(
                tr("Beitragsläufe, Mahnungen und SEPA im Browser."),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun InvoiceRow(inv: InvoiceItem, onClick: () -> Unit) {
    val open = inv.status == "open"
    ListItem(
        headlineContent = { Text(inv.payer) },
        supportingContent = {
            Text(listOfNotNull(inv.number, inv.subject.ifBlank { null }, inv.departmentName).joinToString(" · "))
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(formatMoney(if (open) inv.outstanding else inv.total), style = MaterialTheme.typography.titleSmall)
                Text(
                    if (open && inv.overdue) tr("überfällig") else inv.statusLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (open && inv.overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Eine Rechnung: Posten, Zahlungen mit Quittung, bar kassieren. */
@Composable
fun InvoiceScreen(
    client: AdminClient,
    id: Int,
    canEdit: Boolean,
    errors: AdminErrors,
    onBack: () -> Unit,
    onChanged: OnChanged,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inv by remember(id) { mutableStateOf<InvoiceItem?>(null) }
    var cashAccounts by remember { mutableStateOf<List<BillingAccount>?>(null) }
    var account by remember { mutableStateOf<BillingAccount?>(null) }
    var amount by remember(id) { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now().format(DAY_INPUT)) }
    // Gleicher Schlüssel bis zum Erfolg: ein erneuter Versuch bucht nicht doppelt
    var key by remember(id) { mutableStateOf(UUID.randomUUID().toString()) }
    var lastPayment by remember(id) { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember(id) { mutableStateOf<String?>(null) }

    fun receipt(paymentId: Int, share: Boolean) {
        val current = inv ?: return
        scope.launch {
            busy = true
            val bytes = client.cashReceipt(paymentId)
            if (bytes == null) {
                error = tr("Die Quittung ließ sich nicht laden.")
            } else {
                val file = withContext(Dispatchers.IO) {
                    ReceiptCache.write(context, client.accountKey, receiptFileName(current.number, paymentId), bytes)
                }
                openPdf(context, file, share)?.let { error = it }
            }
            busy = false
        }
    }

    LaunchedEffect(id) {
        try {
            val loaded = client.invoice(id)
            inv = loaded
            amount = amountInput(loaded.outstanding)
            if (canEdit) {
                val cash = client.billingInfo().accounts.filter { it.kind == "cash" }
                cashAccounts = cash
                account = cash.firstOrNull()
            }
        } catch (e: ApiException) {
            error = errors.message(e)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onBack) { Text(tr("‹ Zurück")) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.announce(urgent = true)) }
        val d = inv ?: return@Column
        Text(d.payer, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.asHeading())
        Text(listOfNotNull(d.number, d.subject.ifBlank { null }).joinToString(" · "))
        Text(
            tr("vom {x1} · fällig {x2} · {statusLabel}", "x1" to formatPlainDate(d.date), "x2" to formatPlainDate(d.dueDate), "statusLabel" to d.statusLabel),
            style = MaterialTheme.typography.bodySmall,
            color = if (d.overdue && d.status == "open") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
        d.lines.forEach { line ->
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(line.text)
                    if (line.detail.isNotBlank()) Text(line.detail, style = MaterialTheme.typography.bodySmall)
                }
                Text(formatMoney(line.amount))
            }
        }
        HorizontalDivider()
        AmountRow(tr("Gesamt"), d.total)
        if (d.paid.toBigDecimalOrNull()?.signum() == 1) AmountRow("bezahlt", d.paid)
        if (d.status == "open") AmountRow("offen", d.outstanding, bold = true)

        val newPayment = lastPayment
        if (newPayment != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Bar kassiert und gebucht."), style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { receipt(newPayment, share = true) }, enabled = !busy) { Text(tr("Quittung teilen")) }
                        OutlinedButton(onClick = { receipt(newPayment, share = false) }, enabled = !busy) { Text(tr("Ansehen")) }
                    }
                }
            }
        }

        if (canEdit && d.status == "open" && d.outstanding.toBigDecimalOrNull()?.signum() == 1) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Bar kassiert"), style = MaterialTheme.typography.titleSmall)
                    val accounts = cashAccounts
                    when {
                        accounts == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        accounts.isEmpty() -> Text(tr("Es gibt noch keine Barkasse. Bitte im Browser unter Kasse eine anlegen."))
                        else -> {
                            val amountError = if (amount.isBlank()) null else cashAmountError(amount, d.outstanding)
                            val dateValue = parseGermanDate(date)
                            OutlinedTextField(
                                value = amount,
                                onValueChange = { amount = it },
                                label = { Text(tr("Betrag in €")) },
                                singleLine = true,
                                isError = amountError != null,
                                supportingText = if (amountError != null) {
                                    { Text(amountError) }
                                } else {
                                    null
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = date,
                                onValueChange = { date = it },
                                label = { Text(tr("Datum (TT.MM.JJJJ)")) },
                                singleLine = true,
                                isError = dateValue == null,
                                trailingIcon = { DatePickButton(date) { date = it } },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (accounts.size > 1) {
                                ChoicePicker(tr("Barkasse"), accounts.map { it to it.name }, account, { it.name }) { account = it }
                            } else {
                                Text(tr("in die {x1}", "x1" to accounts.first().name), style = MaterialTheme.typography.bodySmall)
                            }
                            Button(
                                enabled = !busy && amount.isNotBlank() && amountError == null && dateValue != null && account != null,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    val acc = account ?: return@Button
                                    scope.launch {
                                        busy = true
                                        try {
                                            val r = client.payCash(d.id, acc.id, parseAmount(amount), dateValue!!, key)
                                            inv = r.value.invoice
                                            lastPayment = r.value.payment
                                            amount = amountInput(r.value.invoice.outstanding)
                                            key = UUID.randomUUID().toString()
                                            error = null
                                            onChanged(tr("Barzahlung zu {x1} gebucht", "x1" to (d.number ?: tr("der Rechnung"))), r.changeSet)
                                        } catch (e: ApiException) {
                                            error = if (e.status == 0) tr("Keine Verbindung. Bitte erneut versuchen; doppelt gebucht wird nicht.") else errors.message(e)
                                        }
                                        busy = false
                                    }
                                },
                            ) { Text(tr("Bar kassiert buchen")) }
                        }
                    }
                }
            }
        }

        if (d.payments.isNotEmpty()) {
            Text(tr("Zahlungen"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.asHeading())
            d.payments.forEach { p ->
                ListItem(
                    headlineContent = { Text("${formatPlainDate(p.date)} · ${formatMoney(p.amount)}") },
                    supportingContent = { Text("${p.methodLabel} · ${p.accountName} · Buchung ${p.bookingNumber}") },
                    trailingContent = { TextButton(onClick = { receipt(p.id, share = true) }, enabled = !busy) { Text(tr("Quittung")) } },
                )
            }
        }
        Text(tr("Storno, Mahnungen und Zahlungen entfernen im Browser."), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AmountRow(label: String, amount: String, bold: Boolean = false) {
    val style = if (bold) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = style, modifier = Modifier.weight(1f))
        Text(formatMoney(amount), style = style)
    }
}

/** PDF teilen oder ansehen; Fehlertext, wenn keine App dafür da ist. */
internal fun openPdf(context: Context, file: File, share: Boolean): String? {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.dateien", file)
    val intent = if (share) {
        Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
    } else {
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
    }
    intent.clipData = ClipData.newRawUri(file.name, uri)
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        context.startActivity(if (share) Intent.createChooser(intent, tr("Quittung teilen")) else intent)
        null
    } catch (e: ActivityNotFoundException) {
        tr("Keine App zum Öffnen von PDF gefunden.")
    }
}
