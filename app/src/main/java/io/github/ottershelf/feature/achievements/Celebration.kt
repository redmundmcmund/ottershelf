package io.github.ottershelf.feature.achievements

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import io.github.ottershelf.AppContainer
import io.github.ottershelf.R
import io.github.ottershelf.core.tracking.CelebrationClaim
import io.github.ottershelf.ui.components.DashCard
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.nav.Route
import io.github.ottershelf.ui.nav.appViewModel
import io.github.ottershelf.ui.nav.isReader
import io.github.ottershelf.ui.theme.OttershelfTheme

/** How long the server takes to evaluate achievements after a session or status change lands. */
internal const val EVALUATION_DELAY_MS = 2_500L

/** The server holds a claim for 15 minutes, then offers it again: one held longer is dropped unshown. */
internal const val CLAIM_HOLD_MS = 14 * 60_000L

/**
 * The unlock celebration's helper: claims the next achievement waiting to be celebrated
 * (`POST achievements/celebrations/claim`, so it shows on one device only) and acknowledges it once
 * the user has seen it, then claims the next.
 *
 * It claims only while the app is in the foreground ([onStart] / [onStop]), so a claim is never
 * held unseen in the background: on coming to the foreground, [EVALUATION_DELAY_MS] after any
 * tracking write or anything the reader's sync sent ([writes], [sent]: the server evaluates after
 * it answers), and when the user leaves a screen that runs its own flow ([setBlocked]: the book page's
 * finish celebration claims for itself, and the reader shouldn't be interrupted). A trigger while a
 * claim waits restarts its wait; one while the request is out claims again if it found nothing.
 * A request already sent is never cancelled (the server has claimed by then); what it brings back
 * while blocked waits, but not past most of the server's lease ([CLAIM_HOLD_MS]). A claim the book
 * page got as it closed ([unshown]) shows here instead.
 */
class CelebrationViewModel(
    private val claimNext: suspend () -> CelebrationClaim?,
    private val acknowledge: suspend (claimId: String) -> Unit,
    writes: Flow<Any>,
    sent: Flow<Any>,
    unshown: Flow<List<CelebrationClaim>>,
    private val takeUnshown: () -> CelebrationClaim?,
    /** Where acknowledgements run, so leaving doesn't cancel them (the app scope). */
    private val ackScope: CoroutineScope,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : ViewModel() {

    constructor(container: AppContainer) : this(
        claimNext = container.tracking::claimCelebration,
        acknowledge = container.tracking::acknowledgeCelebration,
        writes = container.tracking.version.drop(1),
        sent = container.readingChanges.changes.drop(1),
        unshown = container.tracking.unshownClaims,
        takeUnshown = container.tracking::takeUnshownClaim,
        ackScope = container.appScope,
    )

    private val _current = MutableStateFlow<CelebrationClaim?>(null)

    /** The achievement to show now, or null. */
    val current: StateFlow<CelebrationClaim?> = _current.asStateFlow()

    private var foreground = false
    private var blocked = false
    private var claiming: Job? = null
    /** The claim's POST is out: it runs to the end whatever happens meanwhile. */
    private var requesting = false
    /** When the waiting claim goes out ([clock]). */
    private var dueAt = 0L
    /** A trigger came while the request was out. */
    private var again = false
    /** When [current] arrived ([clock]). */
    private var heldSince = 0L

    init {
        viewModelScope.launch { merge(writes, sent).collect { check(EVALUATION_DELAY_MS) } }
        viewModelScope.launch { unshown.collect { if (it.isNotEmpty()) showHandedOver() } }
    }

    /** The app came to the foreground; [blockedHere]: a blocking screen is on top (known before [setBlocked] runs). */
    fun onStart(blockedHere: Boolean) {
        foreground = true
        dropStale()
        showHandedOver()
        if (!blockedHere) check()
    }

    /** The app left the foreground: a claim still waiting to go out is dropped (one on its way lands). */
    fun onStop() {
        foreground = false
        if (!requesting) claiming?.cancel()
    }

    /**
     * Claims the next celebration [delayMs] from now, unless one is showing or held, the app is in
     * the background, or a screen blocks it.
     */
    fun check(delayMs: Long = 0) {
        if (!foreground || blocked || _current.value != null) return
        if (claiming?.isActive == true) {
            if (requesting) {
                again = true
                return
            }
            // Still waiting: go at the later of the two (each write needs its evaluation time).
            if (clock() + delayMs <= dueAt) return
            claiming?.cancel()
        }
        val due = clock() + delayMs
        dueAt = due
        claiming = viewModelScope.launch {
            val wait = due - clock()
            if (wait > 0) delay(wait)
            if (blocked || !foreground) return@launch
            requesting = true
            again = false
            val claim = try {
                claimNext()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null // offline or refused: the next foreground or save tries again
            } finally {
                requesting = false
            }
            claiming = null
            if (claim != null) {
                show(claim)
            } else if (!showHandedOver() && again) {
                again = false
                check(EVALUATION_DELAY_MS)
            }
        }
    }

    /** While blocked nothing new is claimed (one already claimed, or on its way, waits); unblocking checks. */
    fun setBlocked(value: Boolean) {
        val was = blocked
        blocked = value
        if (value && !requesting) claiming?.cancel()
        if (was && !value) {
            dropStale()
            check(EVALUATION_DELAY_MS)
        }
    }

    /** The user has seen it: acknowledge (in the app scope, so leaving doesn't cancel it) and look for the next. */
    fun seen() {
        val claim = _current.value ?: return
        _current.value = null
        ackScope.launch { runCatching { acknowledge(claim.claimId) } }
        if (!showHandedOver()) check(600)
    }

    private fun show(claim: CelebrationClaim) {
        _current.value = claim
        heldSince = clock()
    }

    /** Shows a handed-over claim when nothing is showing and no request is out; true if it did. */
    private fun showHandedOver(): Boolean {
        if (_current.value != null || requesting) return false
        show(takeUnshown() ?: return false)
        return true
    }

    /**
     * A claim held (behind a blocking screen, or across the background) for most of the server's
     * lease may be offered again elsewhere, such as the book page's finish flow: drop it without
     * acknowledging, and the next check claims afresh.
     */
    private fun dropStale() {
        if (_current.value != null && clock() - heldSince > CLAIM_HOLD_MS) _current.value = null
    }
}

/**
 * The shell's part: shows each claimed achievement as a toast card at the top of the screen
 * ([CelebrationToast]); tapping it opens Achievements, swiping it up or the close button
 * dismisses it, and it goes by itself after a few seconds. Put it in the shell over the content,
 * aligned to the top; [current] is the top route.
 */
@Composable
fun AchievementCelebrationHost(current: Route, onOpenAchievements: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel = appViewModel { CelebrationViewModel(it) }
    val claim by viewModel.current.collectAsStateWithLifecycle()
    val blocked = current is Route.BookDetail || current.isReader || current is Route.Login
    LaunchedEffect(blocked) { viewModel.setBlocked(blocked) }
    // The first ON_START can come before the effect above has run, so it says whether it's blocked.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onStart(blockedHere = blocked) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onStop() }

    var shown by remember { mutableStateOf<CelebrationClaim?>(null) }
    LaunchedEffect(claim, blocked) { if (claim != null && !blocked) shown = claim }
    val visible = claim != null && !blocked
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(visible, claim) {
        if (visible) {
            val rarity = claim?.achievement?.rarity
            val shownFor = if (rarity == "epic" || rarity == "legendary") 9_000L else 6_500L
            // Counted only while the user can see it: leaving the app stops it, and it starts again on return.
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                delay(shownFor)
                viewModel.seen()
            }
        }
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        shown?.let { c ->
            CelebrationToast(
                claim = c,
                onOpen = {
                    viewModel.seen()
                    onOpenAchievements()
                },
                onDismiss = viewModel::seen,
                modifier = Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * The toast: a card (card fill, 1dp edge in the rarity colour, the xl radius, a soft shadow) with
 * the badge's medallion, "Achievement unlocked", its name, description and rarity, and a close
 * button. Stateless, for screenshots.
 */
@Composable
fun CelebrationToast(claim: CelebrationClaim, onOpen: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = OttershelfTheme.colors
    val a = claim.achievement
    val rarity = a.rarity ?: "common"
    val tint = rarityColor(rarity)
    val shape = RoundedCornerShape(OttershelfTheme.radii.xl2)
    DashCard(
        modifier = modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .shadow(10.dp, shape)
            .pointerInput(Unit) { detectVerticalDragGestures { change, dy -> if (dy < -12f) { change.consume(); onDismiss() } } },
        onClick = onOpen,
        containerColor = colors.card,
        borderColor = tint.copy(alpha = 0.7f),
        shape = shape,
        contentPadding = PaddingValues(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BadgeMedallion(AchievementLogic.iconName(a.iconName), rarity, earned = true, fraction = null, size = 58.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LucideIcon("Sparkles", contentDescription = null, tint = colors.primary, size = 13.dp)
                    Spacer(Modifier.width(5.dp))
                    Text(stringResource(R.string.achievements_unlocked), style = MaterialTheme.typography.labelMedium, color = colors.primary, maxLines = 1)
                }
                Text(
                    a.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                a.description?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.mutedForeground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.size(2.dp))
                RarityPill(rarity)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.Top)) {
                LucideIcon("X", contentDescription = stringResource(R.string.achievements_dismiss), tint = colors.mutedForeground, size = 18.dp)
            }
        }
    }
}

/** A tappable text link in the accent (used by the year in review). */
@Composable
internal fun TextLink(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = OttershelfTheme.colors.primary,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp),
    )
}
