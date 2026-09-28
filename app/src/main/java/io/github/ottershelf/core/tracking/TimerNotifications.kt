package io.github.ottershelf.core.tracking

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import io.github.ottershelf.R
import io.github.ottershelf.appContainer
import java.util.Locale

/**
 * How the app is opened from a timer notification: the launcher activity with [EXTRA_OPEN] (and
 * the book / session), which MainActivity turns into a route (ui.nav.PendingRoute).
 */
object TimerIntents {
    const val EXTRA_OPEN = "io.github.ottershelf.extra.OPEN"
    const val EXTRA_BOOK_ID = "io.github.ottershelf.extra.BOOK_ID"
    const val EXTRA_SESSION_ID = "io.github.ottershelf.extra.SESSION_ID"

    /** Opens the timer screen for EXTRA_BOOK_ID. */
    const val OPEN_TIMER = "timer"

    /** Opens the result screen for EXTRA_BOOK_ID and EXTRA_SESSION_ID. */
    const val OPEN_TIMER_RESULT = "timer_result"
}

/**
 * The timer's notifications: an ongoing one while a timer is on (a chronometer counting up, or
 * down to the target, with Pause/Resume and Stop), "save your session" once it stops, and "time's
 * up" when a countdown reaches its target (an inexact alarm, [AlarmManager.setAndAllowWhileIdle]:
 * no exact-alarm permission, so it can be a little late in Doze).
 *
 * No foreground service: the time is wall-clock arithmetic ([ActiveTimer.activeMs]) and the
 * chronometer is drawn by the system. Android 14+ lets the ongoing one be swiped away; the timer
 * keeps running and the notification comes back with the next change or app start (after a reboot
 * or an app update, [TimerRestoreReceiver] starts the app for that).
 */
internal class TimerNotifications(private val context: Context) : TimerNotifier {

    private val manager = NotificationManagerCompat.from(context)
    private val alarms = context.getSystemService(AlarmManager::class.java)

    /** Once, at app start. */
    fun ensureChannels() {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.core_timer_channel))
                .setDescription(context.getString(R.string.core_timer_channel_description))
                .setShowBadge(false)
                .build(),
        )
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(ALERT_CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.core_timer_alert_channel))
                .setDescription(context.getString(R.string.core_timer_alert_channel_description))
                .build(),
        )
    }

    /**
     * Private: the lock screen shows the book's title only if the user lets it show sensitive content.
     * Otherwise it shows the public version, "Reading timer" with the clock and the buttons (the user can
     * still pause or stop it there), not what the user is reading.
     */
    override fun showActive(timer: ActiveTimer, now: Long) {
        val public = activeBuilder(timer, now, context.getString(R.string.core_timer_public_title)).build()
        val builder = activeBuilder(timer, now, label(timer.title))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
        notify(ACTIVE_ID, builder)
    }

    /** The running timer's notification under [title]: its clock, or its time while paused, and its buttons. */
    private fun activeBuilder(timer: ActiveTimer, now: Long, title: String): NotificationCompat.Builder {
        val remaining = timer.remainingMs(now)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_timer)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setContentIntent(openTimer(timer.bookId))
        if (timer.paused) {
            builder.setShowWhen(false)
                .setContentText(context.getString(R.string.core_timer_paused, clock(timer.activeMs(now))))
                .addAction(0, context.getString(R.string.core_timer_resume), action(ACTION_RESUME))
        } else {
            builder.setShowWhen(true).setUsesChronometer(true)
            if (remaining != null && remaining > 0) {
                builder.setChronometerCountDown(true).setWhen(now + remaining)
                    .setContentText(context.getString(R.string.core_timer_countdown, ((timer.targetMs ?: 0) / 60_000).toInt()))
            } else {
                builder.setWhen(now - timer.activeMs(now))
                    .setContentText(context.getString(if (remaining != null) R.string.core_timer_overtime else R.string.core_timer_reading))
            }
            builder.addAction(0, context.getString(R.string.core_timer_pause), action(ACTION_PAUSE))
        }
        return builder.addAction(0, context.getString(R.string.core_timer_stop), action(ACTION_STOP))
    }

    /** The timer is gone (stopped, saved, discarded, signed out): so is a "time's up" still showing. */
    override fun cancelActive() {
        manager.cancel(ACTIVE_ID)
        manager.cancel(TIMES_UP_ID)
    }

    override fun showFinished(timer: FinishedTimer) {
        val text = context.getString(R.string.core_timer_finished_text)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_timer)
            .setContentTitle(context.getString(R.string.core_timer_finished_title, clock(timer.activeMs), label(timer.title)))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openResult(timer.bookId, timer.sessionId))
        notify(FINISHED_ID, builder)
    }

    override fun cancelFinished() = manager.cancel(FINISHED_ID)

    override fun scheduleTimesUp(atMs: Long) {
        alarms?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, action(ACTION_TIMES_UP))
    }

    override fun cancelTimesUp() {
        alarms?.cancel(action(ACTION_TIMES_UP))
    }

    override fun showTimesUp(timer: ActiveTimer) {
        val builder = NotificationCompat.Builder(context, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_timer)
            .setContentTitle(context.getString(R.string.core_timer_times_up_title))
            .setContentText(context.getString(R.string.core_timer_times_up_text, label(timer.title)))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openTimer(timer.bookId))
        notify(TIMES_UP_ID, builder)
    }

    private fun notify(id: Int, builder: NotificationCompat.Builder) {
        if (!canNotify()) return
        try {
            manager.notify(id, builder.build())
        } catch (_: SecurityException) {
            // The permission was revoked in between.
        }
    }

    private fun canNotify() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun label(title: String) = title.ifBlank { context.getString(R.string.core_timer_untitled) }

    private fun action(action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(context, TimerActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun openTimer(bookId: Long): PendingIntent? = open(REQUEST_TIMER) {
        putExtra(TimerIntents.EXTRA_OPEN, TimerIntents.OPEN_TIMER)
        putExtra(TimerIntents.EXTRA_BOOK_ID, bookId)
    }

    private fun openResult(bookId: Long, sessionId: String): PendingIntent? = open(REQUEST_RESULT) {
        putExtra(TimerIntents.EXTRA_OPEN, TimerIntents.OPEN_TIMER_RESULT)
        putExtra(TimerIntents.EXTRA_BOOK_ID, bookId)
        putExtra(TimerIntents.EXTRA_SESSION_ID, sessionId)
    }

    /** The launcher activity, brought forward (onNewIntent) rather than started again. */
    private fun open(requestCode: Int, extras: Intent.() -> Unit): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP).extras()
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val ACTION_PAUSE = "io.github.ottershelf.timer.PAUSE"
        const val ACTION_RESUME = "io.github.ottershelf.timer.RESUME"
        const val ACTION_STOP = "io.github.ottershelf.timer.STOP"
        const val ACTION_TIMES_UP = "io.github.ottershelf.timer.TIMES_UP"

        private const val CHANNEL = "timer"
        private const val ALERT_CHANNEL = "timer_alerts"
        private const val ACTIVE_ID = 7_301
        private const val FINISHED_ID = 7_302
        private const val TIMES_UP_ID = 7_303
        private const val REQUEST_TIMER = 7_311
        private const val REQUEST_RESULT = 7_312

        /** 1:05:09 or 12:34. */
        fun clock(ms: Long): String {
            val total = (ms / 1000).coerceAtLeast(0)
            val h = total / 3600
            val m = total % 3600 / 60
            val s = total % 60
            return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
        }
    }
}

/**
 * The timer notification's buttons and the countdown alarm. Runs the engine's change in the app
 * scope and holds the broadcast open ([goAsync]) until it is written.
 */
class TimerActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = context.appContainer
        val timer = container.timer
        val pending = goAsync()
        container.appScope.launch {
            try {
                when (intent.action) {
                    TimerNotifications.ACTION_PAUSE -> timer.pause()
                    TimerNotifications.ACTION_RESUME -> timer.resume()
                    TimerNotifications.ACTION_STOP -> timer.stop()
                    TimerNotifications.ACTION_TIMES_UP -> timer.onTimesUp()
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * After a reboot (or an app update) the timer's notifications and countdown alarm are gone and
 * nothing starts the app. Starting it is enough: OttershelfApp.onCreate starts the container, whose
 * TimerEngine posts the ongoing notification (or the waiting "save your session") again and sets
 * the alarm. Held open until the timer has been read, at most a few seconds.
 */
class TimerRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = context.appContainer
        if (!container.session.isSignedIn) return
        val pending = goAsync()
        container.appScope.launch {
            try {
                withTimeoutOrNull(RESTORE_WAIT_MS) { container.timer.loaded.first { it } }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val RESTORE_WAIT_MS = 5_000L
    }
}
