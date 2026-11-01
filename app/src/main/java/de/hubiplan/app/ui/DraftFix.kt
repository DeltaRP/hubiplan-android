package de.hubiplan.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import de.hubiplan.app.data.BookingDraft
import de.hubiplan.app.data.FinanceAccount
import de.hubiplan.app.data.FinanceCategory
import de.hubiplan.app.data.ReceiptDraft
import de.hubiplan.app.tr

// Lokale Entwürfe der Kasse korrigieren statt nur verwerfen (UX-Audit 31.10. A10, F08):
// Konto und Kategorie lassen sich ändern; Betrag, Person und Zweck nicht, weil die
// Unterschriften einer Quittung genau diese Angaben bestätigen.

/** Kategorien, die zu einer Buchungsart passen (auch „Einnahmen und Ausgaben“, A16). */
fun categoriesFor(categories: List<FinanceCategory>, kind: String): List<FinanceCategory> =
    categories.filter { it.isActive && (it.kind == kind || it.kind == "both") }

/** Verwerfen nur nach Rückfrage. */
@Composable
fun DiscardDraftDialog(what: String, signed: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$what verwerfen?") },
        text = {
            Text(
                if (signed) {
                    tr("Die Quittung ist schon unterschrieben. Verworfen ist sie endgültig weg; für den Vorgang braucht es dann eine neue Quittung mit neuen Unterschriften.")
                } else {
                    tr("Der Entwurf mit Belegfoto wird von diesem Telefon gelöscht und nicht gebucht.")
                },
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(tr("Endgültig verwerfen")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Behalten")) } },
    )
}

@Composable
fun BookingDraftFixDialog(
    draft: BookingDraft,
    accounts: List<FinanceAccount>,
    categories: List<FinanceCategory>,
    onDismiss: () -> Unit,
    onSave: (BookingDraft) -> Unit,
) {
    val fitting = categoriesFor(categories, draft.kind)
    var account by remember { mutableStateOf(accounts.firstOrNull { it.id == draft.account }) }
    var category by remember { mutableStateOf(fitting.firstOrNull { it.id == draft.category }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Entwurf korrigieren")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${formatPlainDate(draft.date)} · ${formatMoney(draft.amount)} · ${draft.text}")
                draft.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (accounts.isEmpty()) Text(tr("Konten lassen sich gerade nicht laden; bitte mit Verbindung erneut öffnen."))
                ChoicePicker(tr("Konto"), accounts.map { it to it.name }, account, { it.name }) { account = it }
                if (fitting.isNotEmpty()) ChoicePicker(tr("Kategorie"), fitting.map { it to it.name }, category, { it.name }) { category = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = account != null && (fitting.isEmpty() || category != null),
                onClick = {
                    val acc = account ?: return@TextButton
                    onSave(
                        draft.copy(
                            account = acc.id,
                            accountName = acc.name,
                            category = category?.id,
                            categoryName = category?.name.orEmpty(),
                            error = null,
                        ),
                    )
                },
            ) { Text(tr("Speichern und senden")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Abbrechen")) } },
    )
}

@Composable
fun ReceiptDraftFixDialog(
    draft: ReceiptDraft,
    accounts: List<FinanceAccount>,
    categories: List<FinanceCategory>,
    onDismiss: () -> Unit,
    onSave: (ReceiptDraft) -> Unit,
) {
    val fitting = categoriesFor(categories, if (draft.direction == "in") "income" else "expense")
    var account by remember { mutableStateOf(accounts.firstOrNull { it.id == draft.account }) }
    var category by remember { mutableStateOf(fitting.firstOrNull { it.id == draft.category }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Quittung korrigieren")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${formatMoney(draft.amount)} · ${draft.counterpartyName} · ${draft.purpose}")
                draft.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text(
                    tr("Betrag, Name und Zweck sind unterschrieben und bleiben. Stimmen sie nicht, Quittung verwerfen und neu ausstellen."),
                    style = MaterialTheme.typography.bodySmall,
                )
                ChoicePicker(tr("Barkasse"), accounts.map { it to it.name }, account, { it.name }) { account = it }
                ChoicePicker(tr("Kategorie"), fitting.map { it to it.name }, category, { it.name }) { category = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = account != null && category != null,
                onClick = {
                    val acc = account ?: return@TextButton
                    val cat = category ?: return@TextButton
                    onSave(
                        draft.copy(
                            account = acc.id,
                            accountName = acc.name,
                            category = cat.id,
                            categoryName = cat.name,
                            error = null,
                        ),
                    )
                },
            ) { Text(tr("Speichern und senden")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Abbrechen")) } },
    )
}
