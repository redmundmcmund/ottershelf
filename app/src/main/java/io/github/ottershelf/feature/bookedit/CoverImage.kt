package io.github.ottershelf.feature.bookedit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** A photo made ready to upload as a cover: a JPEG in the work folder, and its size. */
data class PreparedCover(val file: File, val width: Int, val height: Int)

/**
 * Photos turned into covers on the phone before they're sent: decoded upright (ImageDecoder applies
 * the EXIF rotation; HEIC and the rest the phone reads), a camera shot cut to the frame the user lined the
 * cover up in, at most [MAX_SIDE] px on the long side, and saved as a JPEG ([QUALITY]). The server
 * takes images up to 20 MB and keeps what it's sent (it makes the thumbnails itself); a phone photo
 * is several MB and far bigger than a cover needs, so the upload stays well under a megabyte.
 */
object CoverImage {
    /** Long side of an uploaded cover: sharper than the book page shows it, like a good online cover. */
    const val MAX_SIDE = 1600
    const val QUALITY = 90

    /** Decoding a camera shot before cutting it: enough for the frame to keep [MAX_SIDE]. */
    private const val DECODE_MAX_SIDE = 3200

    /** The work folder (`cacheDir/bookedit`); emptied of older photos when the screen opens. */
    fun workDir(context: Context): File = File(context.cacheDir, "bookedit").apply { mkdirs() }

    fun newWorkFile(context: Context, prefix: String): File = File(workDir(context), "$prefix-${System.currentTimeMillis()}.jpg")

    /** Deletes the work folder's files older than [maxAgeMs] (a screen closed by the process dying). */
    fun cleanUp(context: Context, maxAgeMs: Long = 24 * 60 * 60 * 1000L) {
        val now = System.currentTimeMillis()
        workDir(context).listFiles()?.filter { now - it.lastModified() > maxAgeMs }?.forEach { it.delete() }
    }

    /** A photo from the Photo Picker. */
    suspend fun fromPicker(context: Context, uri: Uri): PreparedCover = withContext(Dispatchers.IO) {
        val bitmap = decode(ImageDecoder.createSource(context.contentResolver, uri), MAX_SIDE)
        save(context, bitmap)
    }

    /**
     * A camera shot ([photo], deleted afterwards), cut to [frame] (the guide's place on the
     * viewfinder, which shows the photo filling it, centre-cropped) when it can be placed.
     */
    suspend fun fromCamera(context: Context, photo: File, frame: CameraFrame?): PreparedCover = withContext(Dispatchers.IO) {
        try {
            val full = decode(ImageDecoder.createSource(photo), DECODE_MAX_SIDE)
            val crop = frame?.let { CameraCrop.rect(it, full.width, full.height) }
            val cut = if (crop != null && (crop.width < full.width || crop.height < full.height)) {
                Bitmap.createBitmap(full, crop.left, crop.top, crop.width, crop.height)
            } else {
                full
            }
            save(context, cut)
        } finally {
            photo.delete()
        }
    }

    private fun decode(source: ImageDecoder.Source, maxSide: Int): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val (w, h) = CoverScale.target(info.size.width, info.size.height, maxSide)
            if (w != info.size.width || h != info.size.height) decoder.setTargetSize(w, h)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    private fun save(context: Context, bitmap: Bitmap): PreparedCover {
        val (jpeg, w, h) = encode(bitmap)
        val file = newWorkFile(context, "cover")
        file.writeBytes(jpeg)
        return PreparedCover(file, w, h)
    }

    /**
     * [bitmap] scaled down to [maxSide] on its long side (never up) and compressed as a JPEG, on white
     * where it was see-through (JPEG has no transparency). The bytes and their size.
     */
    fun encode(bitmap: Bitmap, maxSide: Int = MAX_SIDE, quality: Int = QUALITY): Triple<ByteArray, Int, Int> {
        val (w, h) = CoverScale.target(bitmap.width, bitmap.height, maxSide)
        val scaled = if (w != bitmap.width || h != bitmap.height) Bitmap.createScaledBitmap(bitmap, w, h, true) else bitmap
        val opaque = if (scaled.hasAlpha()) {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                Canvas(it).apply {
                    drawColor(android.graphics.Color.WHITE)
                    drawBitmap(scaled, 0f, 0f, null)
                }
            }
        } else {
            scaled
        }
        val out = ByteArrayOutputStream()
        opaque.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return Triple(out.toByteArray(), w, h)
    }
}

/** Sizes for downscaling (pure). */
object CoverScale {
    /** [width] x [height] scaled to fit [maxSide] on the long side, keeping the shape; never enlarged. */
    fun target(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width to height
        val long = max(width, height)
        if (long <= maxSide) return width to height
        val scale = maxSide.toDouble() / long
        return (width * scale).roundToInt().coerceIn(1, maxSide) to (height * scale).roundToInt().coerceIn(1, maxSide)
    }
}

/**
 * The camera's guide frame: [frame] (left, top, right, bottom in px) on a viewfinder [viewWidth] x
 * [viewHeight] px, which shows the photo filling it, centred and cropped (CameraXViewfinder's
 * default).
 */
data class CameraFrame(
    val viewWidth: Int,
    val viewHeight: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** A rectangle in a photo's pixels (right and bottom exclusive). */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** Where the guide frame falls on the photo (pure). */
object CameraCrop {
    /**
     * [frame] on an upright photo [imageWidth] x [imageHeight], in the photo's pixels, clamped to it;
     * null when it can't be placed: no sizes, or the photo turned the other way from the viewfinder
     * (the phone held sideways while the screen stayed upright), when the whole photo is kept.
     */
    fun rect(frame: CameraFrame, imageWidth: Int, imageHeight: Int): PixelRect? {
        val vw = frame.viewWidth.toFloat()
        val vh = frame.viewHeight.toFloat()
        if (vw <= 0f || vh <= 0f || imageWidth <= 0 || imageHeight <= 0) return null
        if ((vw > vh) != (imageWidth > imageHeight) && vw != vh && imageWidth != imageHeight) return null
        val scale = max(vw / imageWidth, vh / imageHeight)
        val offsetX = (vw - imageWidth * scale) / 2f
        val offsetY = (vh - imageHeight * scale) / 2f
        val left = ((frame.left - offsetX) / scale).roundToInt().coerceIn(0, imageWidth)
        val top = ((frame.top - offsetY) / scale).roundToInt().coerceIn(0, imageHeight)
        val right = ((frame.right - offsetX) / scale).roundToInt().coerceIn(0, imageWidth)
        val bottom = ((frame.bottom - offsetY) / scale).roundToInt().coerceIn(0, imageHeight)
        if (right <= left || bottom <= top) return null
        return PixelRect(left, top, right, bottom)
    }
}
