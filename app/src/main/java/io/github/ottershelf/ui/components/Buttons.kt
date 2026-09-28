package io.github.ottershelf.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.theme.OttershelfTheme

/**
 * The main action (Read, Sign in, Retry): accent fill with its text colour, the md radius (Material's
 * buttons are pills; the web's and the Nexus app's are not). [icon] is a Lucide name.
 */
@Composable
fun AccentButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: String? = null,
    enabled: Boolean = true,
) {
    val colors = OttershelfTheme.colors
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(OttershelfTheme.radii.md),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.primary,
            contentColor = colors.onPrimary,
            disabledContainerColor = colors.muted,
            disabledContentColor = colors.mutedForeground,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        ButtonContent(text, icon)
    }
}

/**
 * A secondary action (the book page's status and download buttons, Nexus `DetailAction`): card fill,
 * a 1dp border, the md radius, foreground text. [icon] is a Lucide name.
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: String? = null,
    enabled: Boolean = true,
) {
    val colors = OttershelfTheme.colors
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(OttershelfTheme.radii.md),
        border = BorderStroke(1.dp, colors.border),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = colors.card,
            contentColor = colors.foreground,
            disabledContainerColor = colors.card,
            disabledContentColor = colors.mutedForeground,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        ButtonContent(text, icon)
    }
}

@Composable
private fun ButtonContent(text: String, icon: String?) {
    if (icon != null) {
        LucideIcon(icon, contentDescription = null, size = 18.dp)
        Spacer(Modifier.width(8.dp))
    }
    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
