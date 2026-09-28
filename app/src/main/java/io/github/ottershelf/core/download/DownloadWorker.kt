package io.github.ottershelf.core.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import io.github.ottershelf.appContainer
import io.github.ottershelf.core.network.ApiException
import java.io.IOException

/**
 * Runs one download started by [Downloads.start], as a dataSync foreground service with a progress
 * notification (Cancel stops the work). Progress goes to WorkManager ([KEY_DONE], [KEY_TOTAL]),
 * which [Downloads.active] reads. A dropped connection, or the server briefly unable to answer, is
 * retried a couple of times with back-off ([isTransient]; WorkManager waits for the network);
 * anything else is reported once through [Downloads.events].
 *
 * Stopped by WorkManager (Cancel, sign-out, or the network constraint lost): the transfer is
 * aborted, nothing is reported, and a constraint stop is rescheduled by WorkManager itself.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val downloads = applicationContext.appContainer.downloads
        val bookId = inputData.getLong(KEY_BOOK_ID, -1L)
        val account = inputData.getString(KEY_ACCOUNT) ?: return Result.failure()
        val request = downloads.readRequest(account, bookId) ?: return Result.failure()
        val notifications = DownloadNotifications(applicationContext)
        val title = request.book.title

        var foreground = true
        suspend fun report(progress: Downloads.Progress) {
            setProgress(workDataOf(KEY_DONE to progress.done, KEY_TOTAL to progress.total))
            if (!foreground) return
            try {
                setForeground(notifications.foreground(id, bookId, title, progress))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not allowed to start a foreground service right now (the app is in the background
                // when the network came back): carry on without one.
                foreground = false
            }
        }

        return try {
            val installed = coroutineScope {
                val live = MutableStateFlow(Downloads.Progress(0, request.file.sizeBytes ?: -1))
                val reporter = launch {
                    live.collect { progress ->
                        report(progress)
                        delay(REPORT_EVERY_MS) // the flow is conflated: the next one is the newest
                    }
                }
                try {
                    downloads.download(request) { done, total -> live.value = Downloads.Progress(done, total) }
                } finally {
                    reporter.cancel()
                }
            }
            // Not installed: stopped or removed while finishing up, so there's nothing to report.
            if (installed) downloads.onCompleted(request)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive() // stopped: the request failing is expected, say nothing
            if (isTransient(e) && runAttemptCount + 1 < MAX_ATTEMPTS) return Result.retry()
            downloads.onFailed(request, e.message ?: e.javaClass.simpleName)
            Result.failure()
        }
    }

    companion object {
        const val KEY_BOOK_ID = "bookId"
        const val KEY_ACCOUNT = "account"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        private const val REPORT_EVERY_MS = 500L
        private const val MAX_ATTEMPTS = 3

        /** The server briefly unable to answer: timed out, too many requests, a proxy's bad gateway, restarting or overloaded. */
        private val TRANSIENT_STATUS = setOf(408, 429, 502, 503, 504)

        /**
         * Whether [e] may pass by itself, so the download is tried again after WorkManager's back-off
         * (30 s, then 60 s): a dropped connection, or the server briefly unable to answer
         * ([TRANSIENT_STATUS]). Not a 500 (the server's answer for an EPUB it can't parse), any other
         * refusal, or a file that arrived whole but can't be kept ([PermanentDownloadException]):
         * downloading it again would only fetch the same.
         */
        internal fun isTransient(e: Exception): Boolean = when (e) {
            is PermanentDownloadException -> false
            is ApiException -> e.code in TRANSIENT_STATUS
            else -> e is IOException
        }
    }
}
