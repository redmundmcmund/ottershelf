package io.github.ottershelf.ui.screenshots

import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ottershelf.R
import io.github.ottershelf.feature.home.HomeContent
import io.github.ottershelf.feature.home.HomeUiState
import io.github.ottershelf.feature.login.LoginContent
import io.github.ottershelf.feature.login.LoginError
import io.github.ottershelf.feature.login.LoginUiState
import io.github.ottershelf.testing.WithFakeCovers
import io.github.ottershelf.testing.captureLightAndDark
import io.github.ottershelf.ui.components.PlaceholderContent
import io.github.ottershelf.ui.nav.AppDrawerSheet
import io.github.ottershelf.ui.nav.DrawerAction
import io.github.ottershelf.ui.nav.DrawerEntry
import io.github.ottershelf.ui.nav.DrawerScrimColor
import io.github.ottershelf.ui.nav.DrawerUiState
import io.github.ottershelf.ui.nav.RootFrame
import io.github.ottershelf.ui.nav.SearchBarState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The shell: the drawer open over the Dashboard, and a root list's toolbar with its search open.
 * Record with `./gradlew recordRoborazziDebug`, then look at the PNGs in
 * app/build/outputs/roborazzi/.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class ShellScreenshotTest {

    @Test
    fun login() = captureLightAndDark("login/login") {
        LoginContent(LoginUiState(username = "reader", error = LoginError.WrongPassword))
    }

    @Test
    fun drawer() = captureLightAndDark("shell/drawer") {
        WithFakeCovers {
            ModalNavigationDrawer(
                drawerState = rememberDrawerState(DrawerValue.Open),
                scrimColor = DrawerScrimColor,
                drawerContent = {
                    AppDrawerSheet(
                        state = sampleDrawer,
                        selectedKey = "dashboard",
                        onPick = { _, _ -> },
                        onAction = {},
                        onSettings = {},
                        onSignOut = {},
                        drawerState = null,
                    )
                },
            ) {
                RootFrame(
                    title = "Dashboard",
                    search = remember { SearchBarState(mutableStateOf(false), mutableStateOf("")) },
                    onOpenDrawer = {},
                    onOpenSearch = {},
                    onSubmitSearch = {},
                    onCloseSearch = {},
                ) { HomeContent(HomeUiState(), it) }
            }
        }
    }

    @Test
    fun searchOpen() = captureLightAndDark("shell/search") {
        RootFrame(
            title = "All books",
            search = remember { SearchBarState(mutableStateOf(true), mutableStateOf("dracula")) },
            onOpenDrawer = {},
            onOpenSearch = {},
            onSubmitSearch = {},
            onCloseSearch = {},
        ) { PlaceholderContent(title = "All books", detail = "all / dracula", contentPadding = it) }
    }

    private val sampleDrawer = DrawerUiState(
        username = "reader",
        server = "https://books.example.net",
        entries = listOf(
            DrawerEntry.Item("dashboard", nameRes = R.string.nav_dashboard, icon = "LayoutDashboard", fallbackIcon = "LayoutDashboard"),
            DrawerEntry.Item("all", nameRes = R.string.nav_all_books, icon = "LibraryBig", fallbackIcon = "LibraryBig"),
            DrawerEntry.Item("downloaded", nameRes = R.string.nav_downloaded, count = 12, icon = "HardDriveDownload", fallbackIcon = "HardDriveDownload"),
            DrawerEntry.Action(DrawerAction.Requests, count = 2),
            DrawerEntry.Header(R.string.nav_tracking),
            DrawerEntry.Item("calendar", nameRes = R.string.nav_calendar, icon = "CalendarDays", fallbackIcon = "CalendarDays"),
            DrawerEntry.Separator,
            DrawerEntry.Item("authors", nameRes = R.string.nav_authors, count = 1234, icon = "Users", fallbackIcon = "Users"),
            DrawerEntry.Item("series", nameRes = R.string.nav_series, count = 87, icon = "Library", fallbackIcon = "Library"),
            DrawerEntry.Header(R.string.nav_libraries),
            DrawerEntry.Item("library:1", name = "Fiction", count = 812, icon = "BookOpen", fallbackIcon = "BookCopy"),
            DrawerEntry.Item("library:2", name = "Non-fiction", count = 240, icon = "Brain", fallbackIcon = "BookCopy"),
            DrawerEntry.Item("library:3", name = "Comics", count = 56, icon = "custom:marvel", fallbackIcon = "BookCopy"),
            DrawerEntry.Header(R.string.nav_smart_scopes),
            DrawerEntry.Item("scope:1", name = "Unread sci-fi", count = 64, icon = "Sparkles", fallbackIcon = "Aperture"),
            DrawerEntry.Header(R.string.nav_collections),
            DrawerEntry.Item("collection:1", name = "Favourites", count = 18, icon = "Heart", fallbackIcon = "FolderOpen"),
        ),
    )
}
