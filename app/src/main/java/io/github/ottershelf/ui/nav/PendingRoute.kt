package io.github.ottershelf.ui.nav

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.ottershelf.core.tracking.TimerIntents

/**
 * A screen to open that came from outside the app (a timer notification). MainActivity [offer]s
 * it from the launching intent; the signed-in shell navigates there and [consume]s it. While
 * signed out it waits for the next sign-in.
 */
object PendingRoute {

    private val _route = MutableStateFlow<Route?>(null)
    val route: StateFlow<Route?> = _route.asStateFlow()

    fun offer(route: Route?) {
        if (route != null) _route.value = route
    }

    /** The shell has navigated to [route]. */
    fun consume(route: Route) {
        _route.compareAndSet(route, null)
    }

    /**
     * The route a notification's intent asks for (TimerIntents), or null. MainActivity is exported
     * (the launcher), so any app can send these extras: a session id is taken only in the shape
     * TimerEngine makes (a random UUID). Anything else, such as one big enough to overflow the
     * saved back stack when the app goes to the background, opens the book's timer as a missing
     * one does.
     */
    fun fromIntent(intent: Intent?): Route? {
        if (intent == null) return null
        val bookId = intent.getLongExtra(TimerIntents.EXTRA_BOOK_ID, -1L).takeIf { it > 0 } ?: return null
        return when (intent.getStringExtra(TimerIntents.EXTRA_OPEN)) {
            TimerIntents.OPEN_TIMER -> Route.Timer(bookId)
            TimerIntents.OPEN_TIMER_RESULT ->
                intent.getStringExtra(TimerIntents.EXTRA_SESSION_ID)
                    ?.takeIf { it.length == SESSION_ID_LENGTH && SESSION_ID.matches(it) }
                    ?.let { Route.TimerResult(bookId, it) }
                    ?: Route.Timer(bookId)
            else -> null
        }
    }

    private const val SESSION_ID_LENGTH = 36
    private val SESSION_ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
}
