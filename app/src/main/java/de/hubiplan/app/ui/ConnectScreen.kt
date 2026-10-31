package de.hubiplan.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@Composable
fun ConnectScreen(state: ConnectState, vm: AppViewModel) {
    // Lizenzen auch ohne Anmeldung (Lizenzaudit 31.10.2026, L01)
    var licenses by rememberSaveable { mutableStateOf(false) }
    if (licenses) LicensesDialog(onClose = { licenses = false })
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(vm::scanned)
    }
    state.pendingPairing?.let { link ->
        AlertDialog(
            onDismissRequest = vm::cancelPairing,
            title = { Text("Mit dieser Adresse verbinden?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(link.server.removePrefix("https://"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Verbinde die App nur, wenn das die Adresse deines Vereins ist und du den QR-Code " +
                            "selbst gerade im Browser erzeugt hast.",
                    )
                }
            },
            confirmButton = { Button(onClick = vm::confirmPairing) { Text("Ja, verbinden") } },
            dismissButton = { TextButton(onClick = vm::cancelPairing) { Text("Abbrechen") } },
        )
    }
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("HUBIplan", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                if (state.adding) {
                    Text(
                        "Weiteres Konto verbinden, zum Beispiel für einen zweiten Verein mit eigener HUBIplan-Adresse. " +
                            "Für weitere Einheiten derselben Adresse ist kein zweites Konto nötig.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.announce(urgent = true))
                }
                if (state.pendingSession != null) {
                    SecondFactor(state, vm)
                    return@Column
                }
                Text(
                    "Am einfachsten verbindest du die App mit dem QR-Code aus HUBIplan: im Browser unter " +
                        "„Mein Konto › App auf dem Telefon“ auf „App verbinden“ tippen.",
                )
                Button(
                    onClick = {
                        vm.clearError()
                        scanner.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt("QR-Code aus „Mein Konto“ scannen")
                                .setBeepEnabled(false)
                                .setOrientationLocked(false),
                        )
                    },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Mit QR-Code verbinden") }
                HorizontalDivider()
                PasswordLogin(state, vm)
                if (state.adding) {
                    TextButton(onClick = vm::cancelAdd, enabled = !state.busy) { Text("Abbrechen, zurück zum bisherigen Konto") }
                }
                TextButton(onClick = { licenses = true }) { Text("Lizenzen") }
            }
        }
    }
}

@Composable
private fun PasswordLogin(state: ConnectState, vm: AppViewModel) {
    var server by rememberSaveable { mutableStateOf(state.server.removePrefix("https://")) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    Text("Oder mit E-Mail und Passwort anmelden", style = MaterialTheme.typography.titleMedium, modifier = Modifier.asHeading())
    OutlinedTextField(
        value = server,
        onValueChange = { server = it },
        label = { Text("Adresse von HUBIplan") },
        placeholder = { Text("verein.example.de") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { Text("E-Mail") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Passwort") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(
        onClick = { vm.login(server, email, password) },
        enabled = !state.busy && server.isNotBlank() && email.isNotBlank() && password.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Anmelden") }
}

@Composable
private fun SecondFactor(state: ConnectState, vm: AppViewModel) {
    var code by rememberSaveable { mutableStateOf("") }
    Text("Zwei-Faktor-Anmeldung", style = MaterialTheme.typography.titleMedium, modifier = Modifier.asHeading())
    Text("Bitte den Code aus deiner Authenticator-App oder einen Wiederherstellungscode eingeben.")
    OutlinedTextField(
        value = code,
        onValueChange = { code = it },
        label = { Text("Code") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { vm.secondFactor(code) },
        enabled = !state.busy && code.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Bestätigen") }
    TextButton(onClick = vm::cancelSecondFactor) { Text("Abbrechen") }
}
