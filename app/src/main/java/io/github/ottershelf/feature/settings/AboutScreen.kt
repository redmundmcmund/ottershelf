package io.github.ottershelf.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ottershelf.BuildConfig
import io.github.ottershelf.R
import io.github.ottershelf.ui.components.DetailTopBar
import io.github.ottershelf.ui.nav.AppNavigator
import io.github.ottershelf.ui.theme.OttershelfFonts
import io.github.ottershelf.ui.theme.OttershelfTheme

/** BookOrbit's repository: where "Powered by BookOrbit" must link (ADDITIONAL_TERMS.md, section 1). */
const val BOOKORBIT_URL = "https://github.com/bookorbit/bookorbit"

private const val AGPL_URL = "https://www.gnu.org/licenses/agpl-3.0.html"
private const val APACHE_URL = "https://www.apache.org/licenses/LICENSE-2.0"

/** A component the app is built with, and where its licence is. */
internal data class Component(val name: String, val licence: String, val url: String)

/** The code in the app that isn't the app's own (THIRD_PARTY_NOTICES.md has their full notices). */
internal val COMPONENTS = listOf(
    Component("foliate-js (John Factotum)", "MIT", "https://github.com/johnfactotum/foliate-js/blob/main/LICENSE"),
    Component("zip.js (Gildas Lormeau)", "BSD-3-Clause", "https://github.com/gildas-lormeau/zip.js/blob/master/LICENSE"),
    Component("fflate (Arjun Barrett)", "MIT", "https://github.com/101arrowz/fflate/blob/master/LICENSE"),
    Component("Lucide icons", "ISC", "https://lucide.dev/license"),
    Component("Jetpack Compose, AndroidX and CameraX", "Apache-2.0", APACHE_URL),
    Component("Kotlin, kotlinx.coroutines and kotlinx.serialization", "Apache-2.0", APACHE_URL),
    Component("OkHttp", "Apache-2.0", APACHE_URL),
    Component("Coil", "Apache-2.0", APACHE_URL),
    Component("Telephoto", "Apache-2.0", APACHE_URL),
    Component("zxing-cpp", "Apache-2.0", "https://github.com/zxing-cpp/zxing-cpp/blob/master/LICENSE"),
    Component("Tesseract OCR and tessdata_fast", "Apache-2.0", "https://github.com/tesseract-ocr/tesseract/blob/main/LICENSE"),
    Component("Tesseract4Android (Adaptech)", "Apache-2.0", "https://github.com/adaptech-cz/Tesseract4Android/blob/master/LICENSE"),
    Component("Leptonica", "BSD-2-Clause style", "https://github.com/DanBloomberg/leptonica/blob/master/leptonica-license.txt"),
    Component("libpng", "PNG Reference Library License v2", "https://www.libpng.org/pub/png/src/libpng-LICENSE.txt"),
    // The IJG licence wants this sentence in the documentation of any program shipping its code.
    Component("libjpeg: this software is based in part on the work of the Independent JPEG Group", "IJG licence", "https://www.ijg.org/"),
)

@Composable
fun AboutScreen(navigator: AppNavigator) {
    AboutContent(
        version = BuildConfig.VERSION_NAME,
        modified = BuildConfig.MODIFIED_DATE,
        onBack = { navigator.back() },
    )
}

/**
 * About Ottershelf, opened from Settings: the badge, name and version, then the notices BookOrbit's
 * additional terms require of every interface of a modified version (ADDITIONAL_TERMS.md): the
 * attribution with "Powered by BookOrbit" linking to its repository (section 1), that this is a
 * modified, unofficial version and when it was modified (section 3); then this version's source,
 * the licence and the components the app is built with.
 */
@Composable
fun AboutContent(version: String, modified: String, onBack: () -> Unit = {}) {
    val colors = OttershelfTheme.colors
    val direction = LocalLayoutDirection.current
    val context = LocalContext.current
    val open: (String) -> Unit = { url -> openWebLink(context, url) }
    Scaffold(
        topBar = { DetailTopBar(title = stringResource(R.string.about_title), onBack = onBack) },
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
                Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(painterResource(R.drawable.ottershelf_badge), contentDescription = null, modifier = Modifier.size(112.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.settings_app_name),
                        style = TextStyle(fontFamily = OttershelfFonts.Serif, fontWeight = FontWeight.Bold, fontSize = 26.sp),
                        color = colors.foreground,
                    )
                    Text(
                        stringResource(R.string.settings_version, version),
                        style = TextStyle(fontFamily = OttershelfFonts.Sans, fontSize = 13.sp),
                        color = colors.mutedForeground,
                    )
                    Text(
                        stringResource(R.string.about_tagline),
                        modifier = Modifier.padding(top = 6.dp),
                        style = TextStyle(fontFamily = OttershelfFonts.Sans, fontSize = 14.sp, lineHeight = 20.sp),
                        color = colors.foreground,
                        textAlign = TextAlign.Center,
                    )
                }

                // ADDITIONAL_TERMS.md sections 1 and 3, word for word.
                SettingsCard {
                    Column(SettingsRowPadding, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResource(R.string.about_powered_by),
                            modifier = Modifier.clickable(role = Role.Button) { open(BOOKORBIT_URL) },
                            style = TextStyle(
                                fontFamily = OttershelfFonts.Sans,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp,
                                textDecoration = TextDecoration.Underline,
                            ),
                            color = colors.primary,
                        )
                        NoticeText(stringResource(R.string.about_bookorbit_copyright))
                        NoticeText(stringResource(R.string.about_bookorbit_contributors))
                        NoticeText(stringResource(R.string.about_bookorbit_project))
                        NoticeText(stringResource(R.string.about_bookorbit_licence))
                    }
                    SettingsDivider()
                    Column(SettingsRowPadding, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SettingsLabel(stringResource(R.string.about_modified))
                        NoticeText(stringResource(R.string.about_modified_date, modified))
                        NoticeText(stringResource(R.string.about_independent))
                    }
                }

                Column {
                    SettingsGroupLabel(stringResource(R.string.about_group_source))
                    SettingsCard {
                        Column(SettingsRowPadding, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            NoticeText(stringResource(R.string.about_copyright))
                            NoticeText(stringResource(R.string.about_licence))
                        }
                        SettingsDivider()
                        SettingsNavRow(icon = "Code", title = stringResource(R.string.about_source), summary = BuildConfig.SOURCE_URL, onClick = { open(BuildConfig.SOURCE_URL) })
                        SettingsDivider()
                        SettingsNavRow(icon = "Scale", title = stringResource(R.string.about_licence_text), summary = AGPL_URL, onClick = { open(AGPL_URL) })
                        SettingsDivider()
                        SettingsNavRow(icon = "FileText", title = stringResource(R.string.about_terms), summary = null, onClick = { open(BuildConfig.ADDITIONAL_TERMS_URL) })
                    }
                }

                Column {
                    SettingsGroupLabel(stringResource(R.string.about_group_components))
                    SettingsHint(stringResource(R.string.about_components_hint), Modifier.padding(start = 2.dp, bottom = 8.dp))
                    SettingsCard {
                        COMPONENTS.forEachIndexed { i, component ->
                            if (i > 0) SettingsDivider()
                            ComponentRow(component.name, component.licence) { open(component.url) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeText(text: String) {
    Text(
        text,
        style = TextStyle(fontFamily = OttershelfFonts.Sans, fontSize = 13.sp, lineHeight = 19.sp),
        color = OttershelfTheme.colors.mutedForeground,
    )
}

@Composable
private fun ComponentRow(name: String, licence: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        SettingsLabel(name)
        SettingsHint(licence)
    }
}

/** Opens [url] in the browser; says so in a toast when nothing can open it. */
internal fun openWebLink(context: Context, url: String) {
    val opened = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
    if (!opened) Toast.makeText(context, R.string.about_link_failed, Toast.LENGTH_SHORT).show()
}
