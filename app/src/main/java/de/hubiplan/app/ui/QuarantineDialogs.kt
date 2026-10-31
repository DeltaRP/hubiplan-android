package de.hubiplan.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hubiplan.app.data.LocalAccountData

/**
 * Hinweise zur Quarantäne nicht gesendeter Belege (Fachfrage 16, Audit 31.10.):
 * nach erneuter Anmeldung zurückgeholt; nach 90 Tagen erst nach Rückfrage gelöscht.
 */
@Composable
fun QuarantineDialogs(vm: AppViewModel) {
    val restored by vm.restored.collectAsStateWithLifecycle()
    val stale by vm.staleQuarantine.collectAsStateWithLifecycle()
    if (restored > 0) {
        AlertDialog(
            onDismissRequest = vm::dismissRestored,
            title = { Text("Belege zurückgeholt") },
            text = {
                Text(
                    (if (restored == 1) "1 nicht gesendeter Vorgang" else "$restored nicht gesendete Vorgänge") +
                        " aus einer früheren Anmeldung sind wieder da. Sie stehen unter „Verwaltung“ und " +
                        "werden wie gewohnt gesendet.",
                )
            },
            confirmButton = { TextButton(onClick = vm::dismissRestored) { Text("OK") } },
        )
    } else if (stale > 0) {
        AlertDialog(
            onDismissRequest = vm::keepStaleQuarantine,
            title = { Text("Alte, nicht gesendete Belege") },
            text = {
                Text(
                    (if (stale == 1) "1 Vorgang" else "$stale Vorgänge") +
                        " einer früheren Anmeldung liegen seit über ${LocalAccountData.STALE_DAYS} Tagen " +
                        "verschlüsselt auf diesem Gerät. Nach erneuter Anmeldung mit demselben Konto bei " +
                        "derselben HUBIplan-Instanz werden sie wieder angeboten. Jetzt endgültig löschen?",
                )
            },
            confirmButton = { TextButton(onClick = vm::discardStaleQuarantine) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = vm::keepStaleQuarantine) { Text("Behalten") } },
        )
    }
}
