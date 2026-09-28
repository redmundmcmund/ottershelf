package io.github.ottershelf.feature.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ottershelf.R
import io.github.ottershelf.core.theme.ThemeMode
import io.github.ottershelf.ui.components.DetailTopBar
import io.github.ottershelf.ui.nav.AppNavigator
import io.github.ottershelf.ui.nav.Route
import io.github.ottershelf.ui.nav.appViewModel
import io.github.ottershelf.ui.theme.OttershelfFonts
import io.github.ottershelf.ui.theme.OttershelfTheme
import io.github.ottershelf.ui.theme.atAlpha

@Composable
fun SettingsScreen(navigator: AppNavigator) {
    val viewModel = appViewModel { SettingsViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsContent(
        state = state,
        onBack = { navigator.back() },
        onOpenAppearance = { navigator.navigate(Route.Appearance) },
        onOpenGoals = { navigator.navigate(Route.ReadingGoals) },
        onOpenAbout = { navigator.navigate(Route.About) },
        onNextInSeries = viewModel::setNextInSeries,
        onSignOut = viewModel::signOut,
    )
}

/**
 * A pushed screen in the Nexus style (the toolbar of activity_books.xml over the page) with the
 * web's settings cards: who is signed in, Display > Appearance, Reading > goals and the next-in-series
 * switch, Account > Sign out, About (the notices BookOrbit requires: AboutScreen), and the app's
 * badge, name, version and "Powered by BookOrbit" at the bottom.
 */
@Composable
fun SettingsContent(
    state: SettingsUiState,
    onBack: () -> Unit = {},
    onOpenAppearance: () -> Unit = {},
    onOpenGoals: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onNextInSeries: (Boolean) -> Unit = {},
    onSignOut: () -> Unit = {},
) {
    val colors = OttershelfTheme.colors
    val direction = LocalLayoutDirection.current
    Scaffold(
        topBar = { DetailTopBar(title = stringResource(R.string.settings_title), onBack = onBack) },
        containerColor = colors.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = padding.calculateStartPadding(direction),
                    end = padding.calculateEndPadding(direction),
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding(),
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = SETTINGS_MAX_WIDTH).fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                AccountCard(state)

                Column {
                    SettingsGroupLabel(stringResource(R.string.settings_group_display))
                    SettingsCard {
                        SettingsNavRow(
                            icon = "Palette",
                            title = stringResource(R.string.settings_appearance),
                            summary = stringResource(
                                R.string.settings_appearance_summary,
                                themeModeLabel(state.prefs.theme),
                                state.prefs.accent.label,
                                state.prefs.background.label,
                            ),
                            onClick = onOpenAppearance,
                        )
                    }
                }

                Column {
                    SettingsGroupLabel(stringResource(R.string.calendar_settings_group))
                    SettingsCard {
                        SettingsNavRow(
                            icon = "Target",
                            title = stringResource(R.string.calendar_goals_title),
                            summary = stringResource(R.string.calendar_settings_goals_summary),
                            onClick = onOpenGoals,
                        )
                        SettingsDivider()
                        SettingsSwitchRow(
                            icon = "Library",
                            title = stringResource(R.string.settings_next_in_series),
                            summary = stringResource(R.string.settings_next_in_series_hint),
                            checked = state.nextInSeries,
                            onChange = onNextInSeries,
                        )
                    }
                }

                Column {
                    SettingsGroupLabel(stringResource(R.string.settings_group_account))
                    SettingsCard {
                        SettingsNavRow(
                            icon = "LogOut",
                            title = stringResource(if (state.signingOut) R.string.settings_signing_out else R.string.settings_sign_out),
                            summary = stringResource(R.string.settings_sign_out_hint),
                            onClick = onSignOut,
                            danger = true,
                            chevron = false,
                            enabled = !state.signingOut,
                        )
                    }
                }

                Column {
                    SettingsGroupLabel(stringResource(R.string.settings_group_about))
                    SettingsCard {
                        SettingsNavRow(
                            icon = "Info",
                            title = stringResource(R.string.settings_about),
                            summary = stringResource(R.string.settings_about_summary),
                            onClick = onOpenAbout,
                        )
                    }
                }

                AboutFooter(state)
            }
        }
    }
}

/** The account: an initial on an accent disc, the name (or username), then "username on server". */
@Composable
private fun AccountCard(state: SettingsUiState) {
    val colors = OttershelfTheme.colors
    SettingsCard {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val display = state.name ?: state.username
            Box(
                Modifier.size(48.dp).background(colors.primary.atAlpha(0.15f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    display.firstOrNull()?.uppercase().orEmpty(),
                    style = TextStyle(fontFamily = OttershelfFonts.Sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
                    color = colors.primary,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    display,
                    style = TextStyle(fontFamily = OttershelfFonts.Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
                    color = colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.settings_account, state.username, state.server),
                    style = TextStyle(fontFamily = OttershelfFonts.Sans, fontSize = 13.sp, lineHeight = 19.sp),
                    color = colors.mutedForeground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The app's badge, name and version, dim and centred under the cards, then "Powered by BookOrbit". */
@Composable
private fun AboutFooter(state: SettingsUiState) {
    val colors = OttershelfTheme.colors
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.ottershelf_badge), contentDescription = null, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.settings_app_name),
            style = TextStyle(fontFamily = OttershelfFonts.Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp),
            color = colors.mutedForeground,
        )
        Text(
            stringResource(if (state.debugBuild) R.string.settings_version_debug else R.string.settings_version, state.version),
            style = TextStyle(fontFamily = OttershelfFonts.Sans, fontSize = 12.sp),
            color = colors.mutedForeground,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.about_powered_by),
            modifier = Modifier
                .clickable(role = Role.Button) { openWebLink(context, BOOKORBIT_URL) }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            style = TextStyle(fontFamily = OttershelfFonts.Sans, fontSize = 12.sp, textDecoration = TextDecoration.Underline),
            color = colors.primary,
        )
    }
}

@Composable
internal fun themeModeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
        ThemeMode.SYSTEM -> R.string.settings_theme_system
    },
)

/** Settings stay a readable column in landscape and on wide screens. */
internal val SETTINGS_MAX_WIDTH = 640.dp
