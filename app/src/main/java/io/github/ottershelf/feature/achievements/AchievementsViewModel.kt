package io.github.ottershelf.feature.achievements

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.ottershelf.AppContainer

/**
 * The achievements screen: the catalogue as sections of badges, the filter, and the counts.
 * [totalEarned] / [totalAvailable] are the server's, in tiers (Spine Scout and Ink Initiate are
 * two tiers of one badge), as the web's header; the filter chips count badges, as the web's.
 */
@Immutable
data class AchievementsUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val sections: List<BadgeSection> = emptyList(),
    val totalEarned: Int = 0,
    val totalAvailable: Int = 0,
    val filter: AchievementFilter = AchievementFilter.ALL,
) {
    private val all get() = sections.flatMap { it.badges }
    val earnedBadges: Int get() = all.count { it.earned }
    val inProgressBadges: Int get() = all.count { it.inProgress }
    val totalBadges: Int get() = all.size
}

class AchievementsViewModel(private val container: AppContainer) : ViewModel() {

    private val remote: AchievementsRemote = ApiAchievementsRemote(container.api)
    private val _state = MutableStateFlow(AchievementsUiState())
    val state: StateFlow<AchievementsUiState> = _state.asStateFlow()
    private var loading: Job? = null
    /** A change came in while a load was under way: load once more after it. */
    private var again = false

    init {
        load()
        viewModelScope.launch {
            merge(
                // A session saved or a book finished can earn something, once the server has evaluated it.
                container.tracking.version.drop(1).map { EVALUATION_DELAY_MS },
                // Something was claimed (the toast), so something was earned.
                container.tracking.claimed.map { 0L },
            ).collectLatest { wait ->
                delay(wait)
                load(quiet = true)
            }
        }
    }

    fun setFilter(filter: AchievementFilter) = _state.update { it.copy(filter = filter) }

    fun refresh() = load(pull = true)

    fun retry() = load()

    private fun load(pull: Boolean = false, quiet: Boolean = false) {
        if (loading?.isActive == true) {
            if (quiet) again = true
            return
        }
        _state.update { if (pull) it.copy(refreshing = true) else if (quiet || it.sections.isNotEmpty()) it else it.copy(loading = true, error = null) }
        loading = viewModelScope.launch {
            try {
                val catalogue = remote.catalogue()
                _state.update {
                    it.copy(
                        loading = false, refreshing = false, error = null,
                        sections = AchievementLogic.sections(catalogue),
                        totalEarned = catalogue.totalEarned, totalAvailable = catalogue.totalAvailable,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, refreshing = false, error = if (it.sections.isEmpty()) (e.message ?: e.javaClass.simpleName) else null) }
            }
            loading = null
            if (again) {
                again = false
                load(quiet = true)
            }
        }
    }
}
