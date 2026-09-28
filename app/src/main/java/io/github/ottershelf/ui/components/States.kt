package io.github.ottershelf.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ottershelf.R
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.theme.OttershelfTheme

/**
 * Nothing to show: a Lucide [icon] on a muted disc over a dim line of text, centred (the web's
 * empty shelves, the Nexus dashboard cards). [compact] is the in-card size (40dp disc, 12sp); the
 * default fills a screen (48dp disc, 14sp). [action] goes underneath (a button).
 */
@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    icon: String? = "Inbox",
    compact: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(24.dp),
    action: (@Composable () -> Unit)? = null,
) {
    val colors = OttershelfTheme.colors
    Column(
        modifier = modifier.padding(contentPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Box(
                Modifier
                    .size(if (compact) 40.dp else 48.dp)
                    .background(colors.muted, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                LucideIcon(icon, contentDescription = null, tint = colors.mutedForeground, size = if (compact) 16.dp else 20.dp)
            }
            Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
        }
        Text(
            text = message,
            modifier = Modifier.widthIn(max = 360.dp),
            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = colors.mutedForeground,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

/**
 * Loading failed: the [message] (and a [detail] line, such as the server's error) in dim text over
 * the accent "Retry" link, and the whole area retries when tapped (the Nexus dashboard's failed
 * cards, the web's "Failed to load / Retry").
 */
@Composable
fun ErrorState(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    message: String = stringResource(R.string.components_load_failed),
    detail: String? = null,
    compact: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(24.dp),
) {
    val colors = OttershelfTheme.colors
    val retryLabel = stringResource(R.string.components_tap_to_retry)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(OttershelfTheme.radii.lg))
            .clickable(onClickLabel = retryLabel, onClick = onRetry)
            .padding(contentPadding)
            .semantics { contentDescription = listOfNotNull(message, detail).joinToString(". ") },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            modifier = Modifier.widthIn(max = 360.dp),
            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = colors.mutedForeground,
            textAlign = TextAlign.Center,
        )
        if (detail != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = detail,
                modifier = Modifier.widthIn(max = 360.dp),
                style = MaterialTheme.typography.bodySmall,
                color = colors.mutedForeground.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LucideIcon("RefreshCw", contentDescription = null, tint = colors.primary, size = if (compact) 12.dp else 14.dp)
            Text(
                text = stringResource(R.string.components_retry),
                style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                color = colors.primary,
            )
        }
    }
}

/** Waiting for the first page: an accent spinner, centred in what's given. */
@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    val label = stringResource(R.string.components_loading)
    Box(modifier.semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        if (LocalInspectionMode.current) {
            // Previews and screenshot tests can't settle on an endless animation: a still frame.
            CircularProgressIndicator(
                progress = { 0.3f },
                modifier = Modifier.size(32.dp),
                color = OttershelfTheme.colors.primary,
                trackColor = OttershelfTheme.colors.muted,
                strokeWidth = 3.dp,
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                color = OttershelfTheme.colors.primary,
                trackColor = OttershelfTheme.colors.muted,
                strokeWidth = 3.dp,
            )
        }
    }
}

/** [LoadingState] filling the screen (the usual first-load state of a list). */
@Composable
fun FullScreenLoading(modifier: Modifier = Modifier) = LoadingState(modifier.fillMaxSize())

/**
 * A placeholder block where content will appear (the web's `animate-pulse rounded bg-muted`
 * skeletons): give it the size of what it stands for, so nothing moves when the data arrives.
 */
@Composable
fun SkeletonBox(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(OttershelfTheme.radii.sm), pulse: Boolean = true) {
    // The pulse is read in the layer, not in composition: each frame only redraws the block.
    val alpha: State<Float>? = if (pulse && !LocalInspectionMode.current) {
        rememberInfiniteTransition(label = "skeleton").animateFloat(
            initialValue = 1f,
            targetValue = 0.5f,
            animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
            label = "skeleton-alpha",
        )
    } else null
    Box(
        modifier
            .then(if (alpha != null) Modifier.graphicsLayer { this.alpha = alpha.value } else Modifier)
            .background(OttershelfTheme.colors.muted, shape),
    )
}
