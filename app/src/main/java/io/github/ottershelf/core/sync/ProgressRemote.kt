package io.github.ottershelf.core.sync

import io.github.ottershelf.core.model.FileProgress
import io.github.ottershelf.core.model.ReadingSession
import io.github.ottershelf.core.model.SaveProgress

/**
 * The three server calls [ProgressStore] makes, implemented by the Api. An interface only so the
 * store's conflict logic can be unit-tested against a fake server.
 */
interface ProgressRemote {
    /** `GET books/files/:fileId/progress` */
    suspend fun fileProgress(fileId: Long): FileProgress

    /** `POST books/files/:fileId/progress` */
    suspend fun saveProgress(fileId: Long, body: SaveProgress)

    /** `POST books/files/:fileId/sessions` */
    suspend fun saveSession(fileId: Long, body: ReadingSession)
}
