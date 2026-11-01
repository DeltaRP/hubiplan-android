package de.hubiplan.app.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Foto auf dem Gerät vorbereiten: Ausrichtung übernehmen, auf höchstens [MAX_SIDE] Pixel
 * verkleinern und als JPEG neu speichern. Dabei fallen alle EXIF-Angaben weg (Ort, Kamera).
 */
object PhotoPrep {
    private const val MAX_SIDE = 1600
    private const val QUALITY = 88

    fun load(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // Mit inJustDecodeBounds liefert decodeStream immer null und füllt nur die Größe;
        // fehlen darf hier nur der Datenstrom selbst (Gesamtaudit 30.10., AND-F01)
        val stream = resolver.openInputStream(uri) ?: return null
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val exif = resolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        return scale(orient(bitmap, Orientation.of(exif)))
    }

    /**
     * Alle acht EXIF-Ausrichtungen (Gesamtaudit 01.11., GA-F05): erst im Uhrzeigersinn
     * drehen, dann waagerecht spiegeln. Werte wie in ExifInterface (1 normal … 8 270°).
     */
    data class Orientation(val degrees: Int, val mirror: Boolean) {
        companion object {
            fun of(exif: Int): Orientation = when (exif) {
                2 -> Orientation(0, true) // FLIP_HORIZONTAL
                3 -> Orientation(180, false) // ROTATE_180
                4 -> Orientation(180, true) // FLIP_VERTICAL
                5 -> Orientation(90, true) // TRANSPOSE
                6 -> Orientation(90, false) // ROTATE_90
                7 -> Orientation(270, true) // TRANSVERSE
                8 -> Orientation(270, false) // ROTATE_270
                else -> Orientation(0, false)
            }
        }
    }

    fun orient(bitmap: Bitmap, orientation: Orientation): Bitmap {
        if (orientation.degrees % 360 == 0 && !orientation.mirror) return bitmap
        val matrix = Matrix().apply {
            postRotate(orientation.degrees.toFloat())
            if (orientation.mirror) postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun rotate(bitmap: Bitmap, degrees: Int): Bitmap = orient(bitmap, Orientation(degrees, false))

    private fun scale(bitmap: Bitmap): Bitmap {
        val side = max(bitmap.width, bitmap.height)
        if (side <= MAX_SIDE) return bitmap
        val factor = MAX_SIDE.toFloat() / side
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * factor).toInt(), (bitmap.height * factor).toInt(), true)
    }

    fun jpeg(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }.toByteArray()
}
