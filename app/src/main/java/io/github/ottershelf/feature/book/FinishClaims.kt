package io.github.ottershelf.feature.book

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import io.github.ottershelf.core.tracking.CelebrationClaim

/**
 * The finish flow's achievements ([BookTracker]): the next waiting one is claimed in [appScope],
 * because once the server has answered it holds the claim, and the achievement must show somewhere.
 * It shows in the page's flow ([show]) when the flow asked for it on the page and the page is still
 * open ([pageScope]); otherwise the shell's celebration shows it ([handOver]): the page closed while
 * the claim was out, or the user left it from the flow (Read or Details on the next book in the series),
 * so the page is no longer on screen even though it is still on the back stack.
 */
internal class FinishClaims(
    private val appScope: CoroutineScope,
    private val pageScope: CoroutineScope,
    private val claim: suspend () -> CelebrationClaim?,
    private val show: (CelebrationClaim) -> Unit,
    private val handOver: (CelebrationClaim) -> Unit,
) {
    /** Claims the next achievement; [onPage]: the flow goes on on the page, rather than ending as the user leaves it. */
    fun next(onPage: Boolean) {
        appScope.launch {
            val claimed = try {
                claim()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return@launch
            // Both scopes run on the main thread, so the page can't close between the check and the update.
            if (onPage && pageScope.isActive) show(claimed) else handOver(claimed)
        }
    }
}
