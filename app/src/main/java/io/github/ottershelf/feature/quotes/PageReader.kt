package io.github.ottershelf.feature.quotes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.googlecode.tesseract.android.TessBaseAPI
import com.googlecode.tesseract.android.TessBaseAPI.PageIteratorLevel
import com.googlecode.tesseract.android.TessBaseAPI.PageSegMode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reads the text of a photographed page on the phone: Tesseract 5 (through Tesseract4Android,
 * Apache-2.0) with the English tessdata_fast model ([Tessdata]), all native code in the APK: the
 * photo and its text stay on the phone, and nothing reports to anyone.
 * The photo is decoded upright (ImageDecoder applies the EXIF rotation) and at most [MAX_SIDE] px on
 * its long side, and recognised as that bitmap, so the line boxes are in the bitmap's pixels.
 */
object PageReader {
    const val MAX_SIDE = 2400

    /** The photos being worked on: `cacheDir/quotes/`. Kept ones move to [QuotePhotos]. */
    fun workDir(context: Context): File = File(context.cacheDir, "quotes").apply { mkdirs() }

    fun newWorkFile(context: Context): File = File(workDir(context), "page-${System.currentTimeMillis()}.jpg")

    /** Copies a picked photo into the work folder, so it can be kept later. */
    suspend fun copyIn(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        val file = newWorkFile(context)
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Can't open the photo" }
                file.outputStream().use { input.copyTo(it) }
            }
        } catch (e: Throwable) {
            file.delete() // a partial copy
            throw e
        }
        file
    }

    suspend fun decode(file: File): Bitmap = withContext(Dispatchers.IO) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = MAX_SIDE.toFloat() / maxOf(w, h)
            if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    /**
     * The lines Tesseract finds in [bitmap], grouped by paragraph ([OcrLayout.lines]). Runs on
     * Dispatchers.Default (the model is copied out on IO the first time, [Tessdata.install]); a
     * cancelled coroutine stops the recognition part-way. Each page gets its own engine, recycled
     * as soon as the page is read: loading the model takes a fraction of the reading, and its
     * native memory isn't held while the user picks lines or after.
     */
    suspend fun recognise(context: Context, bitmap: Bitmap): OcrPage {
        val dataPath = Tessdata.install(context).absolutePath
        return withContext(Dispatchers.Default) {
            // Tesseract reads ARGB_8888 only (a 10-bit HEIF may decode to another config).
            val image = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
            try {
                OcrPage(bitmap.width, bitmap.height, OcrLayout.lines(read(dataPath, image)))
            } finally {
                if (image !== bitmap) image.recycle()
            }
        }
    }

    /** One engine for one page: set up, read, then recycled. */
    private suspend fun read(dataPath: String, image: Bitmap): List<TessLine> {
        val tess = TessBaseAPI()
        val lock = Any()
        var open = true
        try {
            check(tess.init(dataPath, Tessdata.LANGUAGE, TessBaseAPI.OEM_LSTM_ONLY)) { "Can't load the text recognition model" }
            // A book page: find the columns, paragraphs and lines on it (the wrapper's default is
            // one uniform block).
            tess.pageSegMode = PageSegMode.PSM_AUTO
            // Print is dark on light: don't try every doubtful line again inverted (slow).
            tess.setVariable("tessedit_do_invert", "0")
            // Sauvola's local threshold instead of one Otsu threshold for the whole photo, so a
            // shadow across the page or the dark gutter doesn't swallow the lines in it.
            tess.setVariable("thresholding_method", "2")
            tess.setImage(image)
            coroutineScope {
                // Cancelling this coroutine stops Tesseract from another thread; the engine is
                // recycled only after (the lock), never under a stop.
                val stopper = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        synchronized(lock) { if (open) tess.stop() }
                    }
                }
                try {
                    ensureActive()
                    // The recognition itself, the one call Tesseract can stop (its hOCR is unused).
                    tess.getHOCRText(0)
                } finally {
                    synchronized(lock) { open = false }
                    stopper.cancel()
                }
                ensureActive()
            }
            // No text at all: the iterator would have no line to stand on.
            if (tess.getUTF8Text().isNullOrBlank()) return emptyList()
            val lines = ArrayList<TessLine>()
            val iterator = tess.getResultIterator() ?: return emptyList()
            try {
                iterator.begin()
                do {
                    val box = iterator.getBoundingRect(PageIteratorLevel.RIL_TEXTLINE)
                    lines += TessLine(
                        text = iterator.getUTF8Text(PageIteratorLevel.RIL_TEXTLINE),
                        left = box.left,
                        top = box.top,
                        right = box.right,
                        bottom = box.bottom,
                        startsParagraph = iterator.isAtBeginningOf(PageIteratorLevel.RIL_PARA),
                    )
                } while (iterator.next(PageIteratorLevel.RIL_TEXTLINE))
            } finally {
                iterator.delete()
            }
            return lines
        } finally {
            synchronized(lock) { open = false }
            tess.recycle()
        }
    }
}
