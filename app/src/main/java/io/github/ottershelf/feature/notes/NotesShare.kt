package io.github.ottershelf.feature.notes

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Hands highlights to other apps: a book's Markdown export and quote card images go through the
 * share sheet from `cacheDir/notes-share` (the FileProvider `<applicationId>.notes.share`,
 * res/xml/notes_share_paths.xml); a card saved to the gallery goes to Pictures/Ottershelf through
 * MediaStore (no permission needed).
 */
internal object NotesShare {

    private const val DIR = "notes-share"

    /** Small enough to go as text too, so apps that take only text (notes, chat) get it. */
    private const val INLINE_TEXT_MAX = 60_000

    private fun authority(context: Context) = "${context.packageName}.notes.share"

    /** How long a shared file stays for the receiving app to read it (it may read the URI later). */
    private const val KEEP_MS = 24 * 60 * 60 * 1000L

    /**
     * A file to share, in the share folder. Shares older than [KEEP_MS] are deleted first (never
     * [name] itself), so the folder doesn't grow with every note shared. Call off the main thread.
     */
    private fun file(context: Context, name: String): File {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - KEEP_MS
        dir.listFiles()?.forEach { if (it.name != name && it.lastModified() < cutoff) it.delete() }
        return dir.resolve(name)
    }

    /** Shares [markdown] as a `.md` file (and as text when it's short). */
    suspend fun shareMarkdown(context: Context, markdown: String, fileName: String, subject: String, chooserTitle: String) {
        val file = withContext(Dispatchers.IO) { file(context, fileName).apply { writeText(markdown) } }
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            if (markdown.length <= INLINE_TEXT_MAX) putExtra(Intent.EXTRA_TEXT, markdown)
            clipData = ClipData.newUri(context.contentResolver, fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        start(context, send, chooserTitle)
    }

    /** Shares [bitmap] as a PNG. */
    suspend fun shareImage(context: Context, bitmap: Bitmap, name: String, chooserTitle: String) {
        val file = withContext(Dispatchers.IO) {
            file(context, "$name.png").apply { outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        val uri: Uri = FileProvider.getUriForFile(context, authority(context), file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        start(context, send, chooserTitle)
    }

    private fun start(context: Context, send: Intent, title: String) {
        val chooser = Intent.createChooser(send, title).apply {
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    /** Writes [bitmap] to Pictures/Ottershelf; false when it couldn't. */
    suspend fun saveImage(context: Context, bitmap: Bitmap, name: String): Boolean = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Ottershelf")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: return@withContext false
        try {
            val ok = resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } == true
            if (ok) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } else {
                resolver.delete(uri, null, null)
            }
            ok
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    }
}
