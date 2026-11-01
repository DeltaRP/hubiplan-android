package de.hubiplan.app.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Nimmt Broadcasts der UnifiedPush-Verteiler-App entgegen. Jede App kann hierher senden;
 * darum zählt nur, was die eigene Kennung trägt und sich mit dem eigenen Schlüssel
 * entschlüsseln lässt.
 */
class PushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra("token")
        val pending = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    UnifiedPush.ACTION_NEW_ENDPOINT -> PushSetup.onNewEndpoint(context, token, intent.getStringExtra("endpoint"))
                    UnifiedPush.ACTION_REGISTRATION_FAILED -> PushSetup.onFailed(context, token, intent.getStringExtra("reason"))
                    UnifiedPush.ACTION_UNREGISTERED -> PushSetup.onUnregistered(context, token)
                    UnifiedPush.ACTION_MESSAGE -> PushSetup.onMessage(
                        context,
                        token,
                        intent.getByteArrayExtra("bytesMessage"),
                        intent.getStringExtra("id"),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Eine fehlerhafte Nachricht darf die App nicht beenden (GA-S05)
                Log.w("HUBIplan", "Push-Nachricht verworfen: ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
