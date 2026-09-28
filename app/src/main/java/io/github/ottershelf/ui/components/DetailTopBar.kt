package io.github.ottershelf.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ottershelf.R
import io.github.ottershelf.ui.icons.AppIcons
import io.github.ottershelf.ui.theme.OttershelfTheme

/**
 * The Nexus toolbar of a pushed screen (activity_books.xml, activity_book_detail.xml): Back, a
 * 19sp medium [title] with an optional dim [subtitle] under it ("Author", "Offline copy"),
 * [actions], 56dp on the shell surface, and a 1dp divider underneath. It takes the status bar
 * inset itself (edge to edge), so put it in a Scaffold's `topBar`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = OttershelfTheme.colors
    val bar = colors.shellSurface.compositeOver(colors.background)
    Column(modifier) {
        TopAppBar(
            title = {
                Column {
                    if (title.isNotEmpty()) {
                        Text(
                            title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
                        )
                    }
                    if (!subtitle.isNullOrEmpty()) {
                        Text(
                            subtitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 18.sp),
                            color = colors.mutedForeground,
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(AppIcons.Back, contentDescription = stringResource(R.string.nav_back))
                }
            },
            actions = actions,
            expandedHeight = 56.dp,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = bar,
                scrolledContainerColor = bar,
                navigationIconContentColor = colors.foreground,
                titleContentColor = colors.foreground,
                actionIconContentColor = colors.foreground,
            ),
        )
        HorizontalDivider(thickness = 1.dp, color = colors.border)
    }
}
