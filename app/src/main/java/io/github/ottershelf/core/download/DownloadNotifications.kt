package io.github.ottershelf.core.download

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.text.format.Formatter
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import io.github.ottershelf.R
import java.util.UUID

/** The downloads notification channel: a progress notification per download, and results. */
internal class DownloadNotifications(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    /** Once, at app start. */
    fun ensureChannel() {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.core_download_channel))
                .setDescription(context.getString(R.string.core_download_channel_description))
                .setShowBadge(false)
                .build(),
        )
    }

    /** The worker's foreground notification: progress, and Cancel (which cancels [workId]). */
    fun foreground(workId: UUID, bookId: Long, title: String?, progress: Downloads.Progress): ForegroundInfo {
        val cancel = WorkManager.getInstance(context).createCancelPendingIntent(workId)
        val known = progress.total > 0
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle(context.getString(R.string.core_download_progress, label(title)))
            .apply {
                if (known) {
                    setContentText(
                        "${Formatter.formatShortFileSize(context, progress.done)} / " +
                            Formatter.formatShortFileSize(context, progress.total),
                    )
                }
            }
            .setProgress(PROGRESS_MAX, if (known) (progress.done * PROGRESS_MAX / progress.total).toInt() else 0, !known)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp())
            .addAction(0, context.getString(R.string.core_download_cancel), cancel)
            .build()
        return ForegroundInfo(progressId(bookId), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /** A finished download's result, when no screen is there to show it. */
    fun showResult(event: DownloadEvent) {
        if (!canNotify()) return
        val text = when (event) {
            is DownloadEvent.Completed -> context.getString(R.string.core_download_done, label(event.title))
            is DownloadEvent.Failed -> context.getString(R.string.core_download_failed, label(event.title), event.message)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        try {
            manager.notify(resultId(event.bookId), notification)
        } catch (_: SecurityException) {
            // The permission was revoked in between.
        }
    }

    private fun canNotify() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            manager.areNotificationsEnabled()

    private fun label(title: String?) = title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.core_download_untitled)

    private fun openApp(): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun progressId(bookId: Long) = ("download-progress:$bookId").hashCode()
    private fun resultId(bookId: Long) = ("download-result:$bookId").hashCode()

    private companion object {
        const val CHANNEL = "downloads"
        const val PROGRESS_MAX = 1000
    }
}
