package io.github.ottershelf.devicetest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.memory.MemoryCache
import coil3.request.Options
import coil3.request.crossfade
import coil3.serviceLoaderEnabled
import okio.Buffer
import java.io.ByteArrayOutputStream

/** A made-up cover for the library tests' grid (no network, no disk cache: the app's covers are never touched). */
data class DeviceCover(val n: Long)

/**
 * Coil for the device tests: [DeviceCover]s decoded from JPEGs made here (400x600, like the
 * server's thumbnails), so the grid decodes and uploads real images as it does from the app's disk
 * cache. Set as the process' singleton loader for the test only (the app's own loader, with the
 * account's client and the covers cache, is never created).
 */
object DeviceCovers {

    private val jpegs: List<ByteArray> by lazy { (0 until 16).map(::render) }

    fun loader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .serviceLoaderEnabled(false)
        .components { add(Factory, DeviceCover::class) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
        .diskCache(null as DiskCache?)
        .crossfade(true)
        .build()

    private object Factory : Fetcher.Factory<DeviceCover> {
        override fun create(data: DeviceCover, options: Options, imageLoader: ImageLoader): Fetcher = object : Fetcher {
            override suspend fun fetch(): FetchResult {
                val bytes = jpegs[Math.floorMod(data.n, jpegs.size.toLong()).toInt()]
                return SourceFetchResult(ImageSource(Buffer().write(bytes), options.fileSystem), "image/jpeg", DataSource.DISK)
            }
        }
    }

    private val PALETTES = listOf(
        intArrayOf(0xFF1E3A5F.toInt(), 0xFF3D7EA6.toInt(), 0xFFF2C14E.toInt()),
        intArrayOf(0xFF5B1A18.toInt(), 0xFFB23A48.toInt(), 0xFFF7E1D7.toInt()),
        intArrayOf(0xFF20332B.toInt(), 0xFF4F7C5A.toInt(), 0xFFE9D8A6.toInt()),
        intArrayOf(0xFF2E1F47.toInt(), 0xFF7353BA.toInt(), 0xFFFFD166.toInt()),
        intArrayOf(0xFF3B2F2F.toInt(), 0xFFC97B3C.toInt(), 0xFFFFF3E0.toInt()),
        intArrayOf(0xFF0B1320.toInt(), 0xFF1C7C7D.toInt(), 0xFFE0FBFC.toInt()),
    )

    private fun render(n: Int): ByteArray {
        val (dark, mid, light) = PALETTES[n % PALETTES.size].let { Triple(it[0], it[1], it[2]) }
        val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, 600f, dark, mid, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 400f, 600f, paint)
        paint.shader = null
        paint.color = light
        canvas.drawCircle(200f, 250f, 90f + (n % 4) * 14f, paint)
        paint.color = dark
        canvas.drawRect(0f, 460f, 400f, 600f, paint)
        paint.color = light
        paint.textSize = 44f
        paint.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        canvas.drawText("Volume ${n + 1}", 40f, 530f, paint)
        paint.textSize = 26f
        canvas.drawText("DEVICE TEST PRESS", 40f, 572f, paint)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 86, out)
            out.toByteArray()
        }
    }
}
