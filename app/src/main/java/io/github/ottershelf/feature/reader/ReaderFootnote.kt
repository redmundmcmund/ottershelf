package io.github.ottershelf.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ottershelf.R
import io.github.ottershelf.ui.components.AccentButton
import io.github.ottershelf.ui.components.SecondaryButton
import io.github.ottershelf.ui.components.belowStatusBar
import io.github.ottershelf.ui.theme.OttershelfTheme

/**
 * A footnote over the page (reader.js openFootnote): its text in the reader's font, Close (or swipe
 * it away) back to the same place, and Open in book to follow the reference after all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FootnoteSheet(note: Footnote, prefs: ReaderPrefs, onOpenInBook: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.belowStatusBar(),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = OttershelfTheme.colors.card,
    ) {
        FootnoteContent(note, prefs, onOpenInBook, onDismiss)
    }
}

@Composable
internal fun FootnoteContent(note: Footnote, prefs: ReaderPrefs, onOpenInBook: () -> Unit = {}, onClose: () -> Unit = {}) {
    val colors = OttershelfTheme.colors
    val family = when (prefs.font) {
        ReaderPrefs.FONT_SANS -> FontFamily.SansSerif
        else -> FontFamily.Serif
    }
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            stringResource(R.string.reader_footnote_title),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = colors.mutedForeground,
        )
        Text(
            note.text,
            modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = family, fontSize = 17.sp, lineHeight = 26.sp),
            color = colors.foreground,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(stringResource(R.string.reader_footnote_open), onClick = onOpenInBook, modifier = Modifier.weight(1f), icon = "BookOpen")
            AccentButton(stringResource(R.string.reader_footnote_close), onClick = onClose, modifier = Modifier.weight(1f), icon = "Check")
        }
    }
}
