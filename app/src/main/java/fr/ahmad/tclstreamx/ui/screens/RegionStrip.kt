package fr.ahmad.tclstreamx.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import fr.ahmad.tclstreamx.R
import fr.ahmad.tclstreamx.data.db.RegionCount
import fr.ahmad.tclstreamx.ui.components.TallyRow
import fr.ahmad.tclstreamx.ui.theme.LocalTallyType
import fr.ahmad.tclstreamx.ui.theme.Palette
import java.util.Locale

/**
 * Country and language filter shared by the live and on-demand sections: one row of buttons,
 * "all" first, then the countries and languages found in the library, most represented first.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RegionStrip(
    regions: List<RegionCount>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp),
) {
    if (regions.isEmpty()) return
    LazyRow(
        modifier.focusRestorer(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(key = "r:all") { RegionChip(stringResource(R.string.region_all), selected == null) { onSelect(null) } }
        items(regions, key = { "r:" + it.code }) { r ->
            RegionChip(regionLabel(r.code) + "  " + "%,d".format(Locale.ROOT, r.count), selected == r.code) { onSelect(r.code) }
        }
    }
}

@Composable
private fun RegionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    TallyRow(onClick = onClick, selected = selected, modifier = Modifier.height(40.dp)) { focused ->
        Text(
            label,
            style = LocalTallyType.current.label.copy(color = if (focused || selected) Palette.Text else Palette.TextDim),
            maxLines = 1,
            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp),
        )
    }
}
