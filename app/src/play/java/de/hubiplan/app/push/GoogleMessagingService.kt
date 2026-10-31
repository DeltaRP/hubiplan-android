package de.hubiplan.app.push

import android.util.Base64
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking

/** Nimmt Nachrichten über Google entgegen (Feld `b`: verschlüsselter Web-Push vom Gateway). */
class GoogleMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val body = message.data["b"] ?: return
        val bytes = runCatching { Base64.decode(body, Base64.DEFAULT) }.getOrNull() ?: return
        // Läuft schon im Hintergrund-Thread des Dienstes; kurz blockieren ist hier erlaubt
        runBlocking { PushSetup.onGoogleMessage(applicationContext, bytes) }
    }

    override fun onNewToken(token: String) {
        runBlocking { PushSetup.onGoogleToken(applicationContext, token) }
    }
}
