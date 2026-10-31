package de.hubiplan.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Wappen/Logo der Einheit eines Zugangs, lokal zwischengespeichert: für die Kopfzeile, die
 * Verknüpfung auf dem Startbildschirm und als großes Bild in Mitteilungen. Das Wappen ist
 * öffentlich (steht auch auf der Vereinswebseite), deshalb unverschlüsselt; es liegt aber im
 * Ordner des Kontos und wird beim Abmelden mit gelöscht.
 */
object ClubLogo {
    const val FOLDER = "wappen"

    /** Größte Kantenlänge des gespeicherten Bildes (reicht für Verknüpfung und Mitteilung). */
    private const val MAX_EDGE = 432

    private fun dir(context: Context, account: Account) =
        LocalAccountData.dir(context, FOLDER, LocalAccountData.key(account.server, account.deviceId))

    private fun image(context: Context, account: Account, accessId: Int) = File(dir(context, account), "$accessId.png")

    private fun source(context: Context, account: Account, accessId: Int) = File(dir(context, account), "$accessId.url")

    /** Ist das Wappen unter dieser Adresse schon gespeichert? (dann nicht neu laden) */
    fun isCurrent(context: Context, account: Account, accessId: Int, url: String?): Boolean {
        val file = source(context, account, accessId)
        return if (url == null) !file.exists() else file.exists() && file.readText() == url
    }

    /** Heruntergeladenes Bild verkleinert speichern; null oder Unlesbares entfernt das Wappen. */
    fun save(context: Context, account: Account, accessId: Int, url: String?, bytes: ByteArray?) {
        val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.let(::shrink)
        if (url == null || bitmap == null) {
            image(context, account, accessId).delete()
            if (url == null) source(context, account, accessId).delete()
            return
        }
        val folder = dir(context, account).apply { mkdirs() }
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val tmp = File(folder, "$accessId.png.tmp")
        tmp.writeBytes(png)
        LocalAccountData.replace(tmp, image(context, account, accessId))
        source(context, account, accessId).writeText(url)
    }

    fun load(context: Context, account: Account?, accessId: Int): Bitmap? {
        account ?: return null
        val file = image(context, account, accessId)
        if (!file.exists()) return null
        return runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()
    }

    private fun shrink(bitmap: Bitmap): Bitmap {
        val edge = maxOf(bitmap.width, bitmap.height)
        if (edge <= MAX_EDGE) return bitmap
        val scale = MAX_EDGE.toFloat() / edge
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }
}
