package io.github.ottershelf.feature.quotes

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Tesseract's English model: `eng.traineddata` from tesseract-ocr/tessdata_fast (Apache-2.0; the
 * source commit is in THIRD_PARTY_NOTICES.md), shipped in the APK's assets ([ASSET], compressed
 * there: about 2 MB of its 4 MB) and copied once to `noBackupFilesDir/tessdata/`, because
 * Tesseract reads its model from a file path. No backup rule applies there, and nothing to back up.
 * The copy is made again when it is missing or its size isn't [SIZE] (a newer model changes the
 * constant; `OcrLayoutTest` checks it against the asset).
 */
object Tessdata {
    const val LANGUAGE = "eng"

    internal const val ASSET = "tessdata/eng.traineddata"

    /** The asset's size in bytes. */
    internal const val SIZE = 4_113_088L

    /** One copy at a time: two page reads at once wait for the first one's copy. */
    private val lock = Mutex()

    /**
     * The folder to hand TessBaseAPI.init (it holds `tessdata/eng.traineddata`), the model copied
     * there first when needed: to a `.part` file, renamed over the old copy once it is whole, so a
     * copy cut short (the process killed) is never taken for the model.
     */
    suspend fun install(context: Context): File = withContext(Dispatchers.IO) {
        lock.withLock {
            val root = context.noBackupFilesDir
            val model = File(root, ASSET)
            if (!model.isFile || model.length() != SIZE) {
                val dir = checkNotNull(model.parentFile)
                dir.mkdirs()
                val part = File(dir, model.name + ".part")
                try {
                    context.assets.open(ASSET).use { input -> part.outputStream().use { input.copyTo(it) } }
                    check(part.length() == SIZE) { "The text recognition model is ${part.length()} bytes, not $SIZE" }
                    if (!part.renameTo(model)) {
                        model.delete()
                        check(part.renameTo(model)) { "Can't install the text recognition model" }
                    }
                } finally {
                    part.delete()
                }
            }
            root
        }
    }
}
