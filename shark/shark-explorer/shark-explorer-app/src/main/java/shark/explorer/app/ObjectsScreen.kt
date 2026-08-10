package shark.explorer.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.util.Locale
import shark.explorer.HeapObjectKind
import shark.explorer.ObjectList
import shark.explorer.ObjectListEntry
import shark.explorer.ObjectListFilter
import shark.explorer.formatByteSize
import shark.explorer.formatObjectCount
import shark.explorer.formatPercentOfTotal

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
  /** What a retained size here is a share of. See [shark.explorer.HeapSizes.stronglyReachableByteCount]. */
  stronglyReachableByteCount: Long,
  filter: ObjectListFilter,
  isListing: Boolean,
  onFilterChange: (ObjectListFilter) -> Unit,
  onOpen: (Long, OpenIn) -> Unit,
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
          ObjectRow(entry, stronglyReachableByteCount, onOpen)
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

@Composable
private fun ObjectRowHeader() {
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    HeaderCell(CLASS_COLUMN, Modifier.weight(1f))
    HeaderCell(SHALLOW_COLUMN, Modifier.width(SIZE_COLUMN_WIDTH), TextAlign.End)
    HeaderCell(RETAINED_COLUMN, Modifier.width(SIZE_COLUMN_WIDTH), TextAlign.End)
  }
}

@Composable
private fun HeaderCell(
  text: String,
  modifier: Modifier = Modifier,
  textAlign: TextAlign = TextAlign.Start
) {
  Text(text, modifier, style = MaterialTheme.typography.labelSmall, textAlign = textAlign)
}

/** One object: what it is, what tells it apart from the next of its class, and what it holds. */
@Composable
private fun ObjectRow(
  entry: ObjectListEntry,
  stronglyReachableByteCount: Long,
  onOpen: (Long, OpenIn) -> Unit
) {
  Row(
    Modifier.fillMaxWidth()
      .openable { openIn -> onOpen(entry.objectId, openIn) }
      .padding(horizontal = 12.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Box(Modifier.size(SWATCH_SIZE).background(objectStrengthColor(entry.strength)))
    Column(Modifier.weight(1f)) {
      Text(
        entry.classNameText(),
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      entry.headline?.let { headline ->
        Text(
          headline,
          style = MaterialTheme.typography.bodySmall,
          color = MUTED_TEXT,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
    Text(
      formatByteSize(entry.shallowSize),
      Modifier.width(SIZE_COLUMN_WIDTH),
      style = MaterialTheme.typography.bodySmall,
      textAlign = TextAlign.End
    )
    // The share under the size rather than beside it: the column is as wide as a size and no wider,
    // and a table's numbers only line up while every cell in the column is the same shape.
    Column(Modifier.width(SIZE_COLUMN_WIDTH)) {
      Text(
        formatByteSize(entry.retainedSize),
        Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.End
      )
      Text(
        formatPercentOfTotal(entry.retainedSize, stronglyReachableByteCount),
        Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.labelSmall,
        color = MUTED_TEXT,
        textAlign = TextAlign.End
      )
    }
  }
}

/** The package greyed out, the class name in full, and which kind of thing it is. */
private fun ObjectListEntry.classNameText() = buildAnnotatedString {
  val packageName = className.substringBeforeLast('.', missingDelimiterValue = "")
  if (packageName.isNotEmpty()) {
    withStyle(SpanStyle(color = MUTED_TEXT)) { append("$packageName.") }
  }
  withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(className.substringAfterLast('.')) }
  withStyle(SpanStyle(color = MUTED_TEXT)) { append(" ${kind.typeName}") }
}

private const val SEARCH_LABEL = "Class name"

internal const val EXACT_MATCH = "Exact match"

/** Shown while the pass over every object of the heap dump is still running. */
private const val LISTING = "Going through the heap dump…"

private const val CLASS_COLUMN = "Class"
private const val SHALLOW_COLUMN = "Shallow"
private const val RETAINED_COLUMN = "Retained"

/** Wide enough for a size in gigabytes, so that the numbers line up down the column. */
private val SIZE_COLUMN_WIDTH = 72.dp

private val SPINNER_SIZE = 12.dp
private val SPINNER_STROKE = 2.dp
