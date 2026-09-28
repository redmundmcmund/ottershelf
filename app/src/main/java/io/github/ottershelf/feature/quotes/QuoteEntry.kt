package io.github.ottershelf.feature.quotes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ottershelf.R
import io.github.ottershelf.ui.components.SecondaryButton
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.nav.AppNavigator
import io.github.ottershelf.ui.nav.Route

/** The book page's entry: "Add quote" (typed) and "Scan a page" (the camera straight away). */
@Composable
fun QuoteButtons(bookId: Long, title: String?, navigator: AppNavigator, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton(
            stringResource(R.string.quotes_add),
            onClick = { navigator.navigate(Route.AddQuote(bookId, title, bookFixed = true)) },
            icon = "Quote",
            modifier = Modifier.weight(1f),
        )
        SecondaryButton(
            stringResource(R.string.quotes_scan),
            onClick = { navigator.navigate(Route.AddQuote(bookId, title, bookFixed = true, scan = true)) },
            icon = "ScanLine",
            modifier = Modifier.weight(1f),
        )
    }
}

/** The Notes feed's toolbar action: a quote for the book it is filtered to, else one the user picks. */
@Composable
fun AddQuoteAction(navigator: AppNavigator, bookId: Long? = null, title: String? = null) {
    IconButton(onClick = { navigator.navigate(Route.AddQuote(bookId, title)) }) {
        LucideIcon("Quote", contentDescription = stringResource(R.string.quotes_add), tint = LocalContentColor.current, size = 22.dp)
    }
}
