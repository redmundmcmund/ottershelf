package io.github.ottershelf.feature.quotes

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.ottershelf.core.settings.AppSettingsRepository
import java.io.File

/**
 * The source photos the user chose to keep: `files/quote-photos/<account>/<annotationId>.jpg`, never
 * uploaded (and not backed up: `allowBackup` is off). The settings key links them
 * (`AppSettings.quotePhotos`: annotation id -> file name), so the link travels with the account
 * while the photo stays on this phone. The key lives on the server, so a link's name is trusted
 * only when it is the one [keep] writes ([linkedFile]): nothing else is ever read or deleted.
 */
object QuotePhotos {
    fun dir(context: Context, account: String): File =
        File(context.filesDir, "quote-photos/${account.filter { it.isLetterOrDigit() || it == '-' || it == '_' }}")

    /** Moves [photo] in as [annotationId]'s and links it; false when it couldn't be stored. */
    suspend fun keep(context: Context, account: String, settings: AppSettingsRepository?, annotationId: Long, photo: File): Boolean {
        val name = nameOf(annotationId)
        val stored = withContext(Dispatchers.IO) {
            runCatching {
                val target = File(dir(context, account).apply { mkdirs() }, name)
                if (!photo.renameTo(target)) {
                    photo.copyTo(target, overwrite = true)
                    photo.delete()
                }
                true
            }.getOrDefault(false)
        }
        if (stored && settings != null) {
            // Past the limit the oldest links go, and their photos with them (nothing could reach them).
            var dropped: Map<Long, String> = emptyMap()
            settings.update { s ->
                val all = s.quotePhotos + (annotationId to name)
                val kept = all.newest(MAX_PHOTOS)
                dropped = all - kept.keys
                s.copy(quotePhotos = kept)
            }
            if (dropped.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    val dir = dir(context, account)
                    dropped.forEach { (id, linked) -> runCatching { linkedFile(dir, id, linked)?.delete() } }
                }
            }
        }
        return stored
    }

    /** Deletes [annotationId]'s kept photo from this phone and its link (the quote itself stays). */
    suspend fun remove(context: Context, account: String, settings: AppSettingsRepository, annotationId: Long) {
        val name = settings.settings.value.quotePhotos[annotationId]
        settings.update { it.copy(quotePhotos = it.quotePhotos - annotationId) }
        if (name != null) withContext(Dispatchers.IO) { runCatching { linkedFile(dir(context, account), annotationId, name)?.delete() } }
    }

    /** [annotationId]'s kept photo on this phone, if [name] links it and it is there. */
    private fun file(context: Context, account: String, annotationId: Long, name: String?): File? =
        linkedFile(dir(context, account), annotationId, name)?.takeIf { it.isFile }

    /** The only name [keep] gives [annotationId]'s photo. */
    private fun nameOf(annotationId: Long): String = "$annotationId.jpg"

    /**
     * Where [annotationId]'s photo is in [dir], or null unless its link [name] is exactly the name
     * [keep] gives it and the file lies directly in [dir]. The links come from the settings key on
     * the server, which any of the user's devices (or whoever holds the user's token) can write, so a name like
     * `../../shared_prefs/session.xml` must never reach a read or a delete.
     */
    internal fun linkedFile(dir: File, annotationId: Long, name: String?): File? {
        if (name != nameOf(annotationId)) return null
        val file = File(dir, name)
        val inside = runCatching { file.canonicalFile.parentFile == dir.canonicalFile }.getOrDefault(false)
        return file.takeIf { inside }
    }

    /**
     * The annotation ids in [links] whose photo is on this phone (the links travel with the account,
     * the photos don't).
     */
    suspend fun onThisPhone(context: Context, account: String, links: Map<Long, String>): Set<Long> =
        if (links.isEmpty()) emptySet() else withContext(Dispatchers.IO) {
            links.filter { (id, name) -> file(context, account, id, name) != null }.keys
        }

    /** The kept photo of [annotationId] for the signed-in account, if it is linked and on this phone. */
    fun of(context: Context, account: String, settings: AppSettingsRepository, annotationId: Long): File? =
        file(context, account, annotationId, settings.settings.value.quotePhotos[annotationId])

    private fun Map<Long, String>.newest(max: Int): Map<Long, String> =
        if (size <= max) this else entries.sortedByDescending { it.key }.take(max).associate { it.key to it.value }

    /** At most this many links in the settings key (each is ~20 bytes). */
    const val MAX_PHOTOS = 400
}
