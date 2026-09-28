package io.github.ottershelf.ui.nav

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ottershelf.core.tracking.TimerIntents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** The routes MainActivity takes from an intent: any app can send one (it's the launcher). */
@RunWith(AndroidJUnit4::class) // for android.content.Intent's extras
class PendingRouteTest {

    private fun open(what: String?, bookId: Long = 7, sessionId: String? = null) = Intent()
        .putExtra(TimerIntents.EXTRA_BOOK_ID, bookId)
        .apply { what?.let { putExtra(TimerIntents.EXTRA_OPEN, it) } }
        .apply { sessionId?.let { putExtra(TimerIntents.EXTRA_SESSION_ID, it) } }

    @Test
    fun theTimersNotificationsOpenTheirScreens() {
        val id = UUID.randomUUID().toString()
        assertEquals(Route.TimerResult(7, id), PendingRoute.fromIntent(open(TimerIntents.OPEN_TIMER_RESULT, sessionId = id)))
        assertEquals(Route.Timer(7), PendingRoute.fromIntent(open(TimerIntents.OPEN_TIMER)))
        assertEquals(Route.Timer(7), PendingRoute.fromIntent(open(TimerIntents.OPEN_TIMER_RESULT)))
    }

    @Test
    fun aSessionIdNotShapedAsTheTimersIsDropped() {
        listOf(
            "x".repeat(300_000),
            UUID.randomUUID().toString() + "0",
            "session-1",
            "",
            "../../etc",
        ).forEach {
            assertEquals(it.take(20), Route.Timer(7), PendingRoute.fromIntent(open(TimerIntents.OPEN_TIMER_RESULT, sessionId = it)))
        }
    }

    @Test
    fun otherIntentsOpenNothing() {
        assertNull(PendingRoute.fromIntent(null))
        assertNull(PendingRoute.fromIntent(Intent(Intent.ACTION_MAIN)))
        assertNull(PendingRoute.fromIntent(open(TimerIntents.OPEN_TIMER, bookId = 0)))
        assertNull(PendingRoute.fromIntent(open("reader")))
    }
}
