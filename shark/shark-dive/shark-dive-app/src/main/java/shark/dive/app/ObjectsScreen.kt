package shark.dive.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.util.Locale
import shark.dive.HeapObjectKind
import shark.dive.ObjectList
import shark.dive.ObjectListFilter
import shark.dive.formatObjectCount

/**
 * Every object of the heap dump as a list, largest first, filtered by class name and kind.
 *
 * The view a treemap can't be: a class with a thousand small instances is one line here and a thousand
 * rectangles too small to draw there. The sizes are the ones the treemap lays out from, so a row and a
 * rectangle agree to the byte, and clicking a row is the same move as clicking that rectangle.
 */
@Composable
internal fun ObjectsScreen(
  list: ObjectList,
  /** What a retained size here is a share of. See [shark.dive.HeapSizes.stronglyReachableByteCount]. */
  stronglyReachableByteCount: Long,
  filter: ObjectListFilter,
  isListing: Boolean,
  onFilterChange: (ObjectListFilter) -> Unit,
  onOpen: (Long, OpenIn) -> Unit,
  /** Puts a link to a row's object on the clipboard, beside opening it. See [OpenTarget]. */
  onCopyLink: (Long) -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(modifier, color = MaterialTheme.colorScheme.surface) {
    Column(Modifier.fillMaxSize()) {
      ObjectFilterBar(filter, onFilterChange)
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          list.countText(isListing),
          Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
          style = MaterialTheme.typography.bodySmall
        )
        if (isListing) {
          CircularProgressIndicator(Modifier.size(SPINNER_SIZE), strokeWidth = SPINNER_STROKE)
        }
      }
      ObjectRowHeader()
      HorizontalDivider()
      LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
        items(list.entries, key = { it.objectId }) { entry ->
          ObjectRow(entry, stronglyReachableByteCount, onOpen, onCopyLink)
        }
      }
    }
  }
}

/** What the list is filtered down to: a class name to look for, and which kinds of object to keep. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ObjectFilterBar(
  filter: ObjectListFilter,
  onFilterChange: (ObjectListFilter) -> Unit
) {
  Column(
    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp)
  ) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      OutlinedTextField(
        value = filter.query,
        onValueChange = { query -> onFilterChange(filter.copy(query = query)) },
        label = { Text(SEARCH_LABEL) },
        singleLine = true,
        modifier = Modifier.weight(1f)
      )
      CheckboxRow(
        label = EXACT_MATCH,
        isChecked = filter.isExactMatch,
        onCheckedChange = { isExact -> onFilterChange(filter.copy(isExactMatch = isExact)) }
      )
    }
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
      HeapObjectKind.values().forEach { kind ->
        CheckboxRow(
          label = kind.displayName,
          isChecked = kind in filter.kinds,
          onCheckedChange = { isChecked ->
            onFilterChange(
              filter.copy(
                kinds = if (isChecked) filter.kinds + kind else filter.kinds - kind
              )
            )
          }
        )
      }
    }
  }
}

/** A checkbox and its label, both of them the one thing to click. */
@Composable
private fun CheckboxRow(
  label: String,
  isChecked: Boolean,
  onCheckedChange: (Boolean) -> Unit
) {
  Row(
    Modifier.toggleable(
      value = isChecked,
      role = Role.Checkbox,
      onValueChange = onCheckedChange
    ).padding(end = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(2.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Checkbox(checked = isChecked, onCheckedChange = null)
    Text(label, style = MaterialTheme.typography.bodySmall)
  }
}

/** How much of the heap dump the filter matches, which is what says a search found little or nothing. */
private fun ObjectList.countText(isListing: Boolean): String = when {
  isListing -> LISTING
  hasMore -> "${matchesOfTotal()} · the largest ${entries.size} of them are listed"
  else -> matchesOfTotal()
}

/** Reads as "1,204 of 45,003 objects match", so the part and the whole are both there to compare. */
private fun ObjectList.matchesOfTotal() =
  "${String.format(Locale.US, "%,d", matchCount)} of ${formatObjectCount(totalCount)} match"

private const val SEARCH_LABEL = "Class name"

internal const val EXACT_MATCH = "Exact match"

/** Shown while the pass over every object of the heap dump is still running. */
private const val LISTING = "Going through the heap dump…"

private val SPINNER_SIZE = 12.dp
private val SPINNER_STROKE = 2.dp
