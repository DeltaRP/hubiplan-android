package de.hubiplan.app.push

import android.content.Context

/** F-Droid-Fassung: ohne Google. Push nur über eine UnifiedPush-App, sonst Abruf. */
object GooglePush {
    const val ID = "google"
    const val LABEL = "Google"

    fun init(context: Context) {}

    fun available(context: Context): Boolean = false

    suspend fun token(context: Context): String? = null

    suspend fun delete(context: Context) {}
}
