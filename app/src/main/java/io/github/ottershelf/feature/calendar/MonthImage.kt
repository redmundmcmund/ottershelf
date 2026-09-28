package io.github.ottershelf.feature.calendar

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.YearMonth

/**
 * Saves the month card (recorded in a [GraphicsLayer]) as a PNG in Pictures/Ottershelf through
 * MediaStore: no storage permission is needed on Android 10 and later.
 */
internal object MonthImage {

    private const val PADDING_PX = 48

    suspend fun save(context: Context, layer: GraphicsLayer, background: Color, month: YearMonth): Boolean = try {
        if (layer.size.width <= 0 || layer.size.height <= 0) {
            false
        } else {
            // The layer may render to a hardware bitmap: copy it into one we can draw and compress.
            val card = layer.toImageBitmap().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
            withContext(Dispatchers.IO) { write(context, compose(card, background), month) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /** The card on the page colour with a margin (the card itself is translucent). */
    private fun compose(card: Bitmap, background: Color): Bitmap {
        val out = Bitmap.createBitmap(card.width + PADDING_PX * 2, card.height + PADDING_PX * 2, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(background.toArgb())
            drawBitmap(card, PADDING_PX.toFloat(), PADDING_PX.toFloat(), null)
        }
        card.recycle()
        return out
    }

    private fun write(context: Context, bitmap: Bitmap, month: YearMonth): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Ottershelf calendar $month.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Ottershelf")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return false
        return try {
            val ok = resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } == true
            if (!ok) {
                resolver.delete(uri, null, null)
            } else {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            ok
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            false
        } finally {
            bitmap.recycle()
        }
    }
}
