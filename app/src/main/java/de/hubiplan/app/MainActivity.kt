package de.hubiplan.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hubiplan.app.ui.AppViewModel
import de.hubiplan.app.ui.ConnectScreen
import de.hubiplan.app.ui.HubiplanTheme
import de.hubiplan.app.ui.Loading
import de.hubiplan.app.ui.MainScreen
import de.hubiplan.app.ui.QuarantineDialogs
import de.hubiplan.app.ui.Screen
import de.hubiplan.app.sync.Notifications

// FragmentActivity statt ComponentActivity: nötig für die Abfrage von Fingerabdruck/PIN (Verwaltung)
class MainActivity : FragmentActivity() {
    private val vm: AppViewModel by viewModels()

    // Bildschirm aus (auch Sperren des Telefons): Verwaltung sofort sperren (Fachfrage 18)
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            vm.lockAdminNow()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // App-Sprache aus den Android-Einstellungen kann sich geändert haben
        Texte.init(this)
        ContextCompat.registerReceiver(
            this,
            screenOff,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        enableEdgeToEdge()
        openFrom(intent)
        setContent {
            val theme by vm.theme.collectAsStateWithLifecycle()
            val screen by vm.screen.collectAsStateWithLifecycle()
            val connect by vm.connect.collectAsStateWithLifecycle()
            HubiplanTheme(theme) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        when (val s = screen) {
                            Screen.Loading -> Loading()
                            Screen.Connect -> ConnectScreen(connect, vm)
                            is Screen.Main -> MainScreen(s, vm)
                        }
                        QuarantineDialogs(vm)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(screenOff)
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        vm.cameToForeground()
    }

    override fun onStop() {
        vm.wentToBackground()
        super.onStop()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        vm.userInteracted()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
    }

    /** Aus einer Benachrichtigung oder Verknüpfung: Mitteilung öffnen bzw. zum passenden Konto wechseln. */
    private fun openFrom(intent: Intent?) {
        val slot = intent?.getIntExtra(Notifications.EXTRA_SLOT, -1) ?: -1
        val access = intent?.getIntExtra(Notifications.EXTRA_ACCESS, -1) ?: -1
        val circular = intent?.getIntExtra(Notifications.EXTRA_CIRCULAR, -1) ?: -1
        val shortcut = intent?.getStringExtra(Notifications.EXTRA_SHORTCUT_ACCOUNT)
        if (shortcut != null && access > 0) {
            // Verknüpfung auf dem Startbildschirm: Konto und Einheit dieser Verknüpfung
            vm.openShortcut(shortcut, access)
        } else if (access > 0 && circular > 0) {
            vm.openCircular(slot.coerceAtLeast(0), access, circular)
        } else if (slot >= 0) {
            vm.switchAccount(slot)
        }
        intent?.removeExtra(Notifications.EXTRA_CIRCULAR)
        intent?.removeExtra(Notifications.EXTRA_SLOT)
        intent?.removeExtra(Notifications.EXTRA_SHORTCUT_ACCOUNT)
    }
}
