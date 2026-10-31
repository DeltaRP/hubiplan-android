package de.hubiplan.app.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import de.hubiplan.app.BuildConfig
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Play-Fassung: Push über Firebase Cloud Messaging, wenn keine UnifiedPush-App da ist.
 * Google bekommt nur den verschlüsselten Web-Push (Inhalt ist für das Gerät verschlüsselt).
 * Die Firebase-Angaben kommen beim Bauen aus `firebase.properties` (nicht im Repo); fehlen
 * sie, gibt es nur UnifiedPush und Abruf.
 */
object GooglePush {
    const val ID = "google"
    const val LABEL = "Google"
    private const val GMS = "com.google.android.gms"

    private val configured: Boolean
        get() = BuildConfig.FIREBASE_APP_ID.isNotBlank() && BuildConfig.FIREBASE_PROJECT_ID.isNotBlank() &&
            BuildConfig.FIREBASE_API_KEY.isNotBlank() && BuildConfig.FIREBASE_SENDER_ID.isNotBlank()

    fun available(context: Context): Boolean =
        configured && runCatching { context.packageManager.getPackageInfo(GMS, 0) }.isSuccess

    /** Beim Start der App: Firebase vorbereiten (ohne Netz), damit Nachrichten ankommen. */
    fun init(context: Context) {
        if (configured) runCatching { ensureApp(context) }
    }

    private fun ensureApp(context: Context) {
        if (FirebaseApp.getApps(context).isEmpty()) {
            val options = FirebaseOptions.Builder()
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                .build()
            FirebaseApp.initializeApp(context.applicationContext, options)
        }
    }

    private fun messaging(context: Context): FirebaseMessaging {
        ensureApp(context)
        return FirebaseMessaging.getInstance()
    }

    suspend fun token(context: Context): String? {
        if (!available(context)) return null
        return runCatching {
            val messaging = messaging(context)
            messaging.isAutoInitEnabled = true
            suspendCancellableCoroutine { cont ->
                messaging.token.addOnCompleteListener { task ->
                    cont.resume(if (task.isSuccessful) task.result else null)
                }
            }
        }.getOrNull()
    }

    suspend fun delete(context: Context) {
        if (!configured) return
        runCatching {
            val messaging = messaging(context)
            messaging.isAutoInitEnabled = false
            suspendCancellableCoroutine { cont ->
                messaging.deleteToken().addOnCompleteListener { cont.resume(Unit) }
            }
        }
    }
}
