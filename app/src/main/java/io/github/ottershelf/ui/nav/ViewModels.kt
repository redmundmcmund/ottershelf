package io.github.ottershelf.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.ottershelf.AppContainer

/** The app's container, provided once by [AppRoot]. Screens don't read it directly: see [appViewModel]. */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("No AppContainer here: screens run inside AppRoot, screenshot tests render stateless content")
}

/**
 * THE way a screen gets its ViewModel: created from the [AppContainer] by [create], scoped to the
 * screen's navigation entry (cleared when the screen is popped, kept while another tab shows, kept
 * across rotation), and cleared on sign-out.
 *
 * ```
 * @Composable
 * fun BookDetailScreen(route: Route.BookDetail, navigator: AppNavigator) {
 *     val viewModel = appViewModel { container -> BookDetailViewModel(container, route.bookId) }
 *     val state by viewModel.state.collectAsStateWithLifecycle()
 *     BookDetailContent(state, onBack = { navigator.back() })
 * }
 * ```
 *
 * [create] runs with [CreationExtras] as receiver, so `createSavedStateHandle()` is available for
 * state that must survive process death. Pass [key] only to hold two ViewModels of the same class
 * in one screen.
 */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    key: String? = null,
    crossinline create: CreationExtras.(AppContainer) -> VM,
): VM {
    val container = LocalAppContainer.current
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}
