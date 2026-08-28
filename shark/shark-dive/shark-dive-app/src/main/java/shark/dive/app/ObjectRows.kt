package shark.dive.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import shark.dive.ObjectListEntry
import shark.dive.formatByteSize
import shark.dive.formatPercentOfTotal

/**
 * One object as a row of a list, and the header over a column of them.
 *
 * **The same row on every screen that lists objects**, so that a list of what matched a search and a list of
 * what somebody starred are one table with two contents rather than two tables. Which colour it is drawn in,
 * where the sizes sit, what a click does and what the right button offers are all answers a reader learns
 * once.
 *
 * The lists that are *not* built from this are the ones whose rows aren't objects: the leaks screen groups
 * objects under the leak they are instances of, and its rows carry why each one is leaking.
 */
@Composable
internal fun ObjectRowHeader(
  /** Whether a row of this list ends in something of its own, which is a column to leave room for. */
  hasTrailing: Boolean = false
) {
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    HeaderCell(CLASS_COLUMN, Modifier.weight(1f))
    HeaderCell(SHALLOW, Modifier.width(SIZE_COLUMN_WIDTH), TextAlign.End)
    HeaderCell(RETAINED, Modifier.width(SIZE_COLUMN_WIDTH), TextAlign.End)
    if (hasTrailing) {
      Box(Modifier.width(TRAILING_COLUMN_WIDTH))
    }
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
internal fun ObjectRow(
  entry: ObjectListEntry,
  /** What a retained size here is a share of. See [shark.dive.HeapSizes.stronglyReachableByteCount]. */
  stronglyReachableByteCount: Long,
  onOpen: (Long, OpenIn) -> Unit,
  /** Puts a link to the row's object on the clipboard, beside opening it. See [OpenTarget]. */
  onCopyLink: (Long) -> Unit,
  /** What this list has to offer about a row that the others don't, at the end of it. */
  trailing: (@Composable () -> Unit)? = null
) {
  val open: (OpenIn) -> Unit = { openIn -> onOpen(entry.objectId, openIn) }
  OpenTarget(open, { onCopyLink(entry.objectId) }) {
    Row(
      Modifier.fillMaxWidth()
        .openable(open)
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
      if (trailing != null) {
        Box(Modifier.width(TRAILING_COLUMN_WIDTH), contentAlignment = Alignment.CenterEnd) {
          trailing()
        }
      }
    }
  }
}

/** What a list of objects says when it has none, in the same place a row would be. */
@Composable
internal fun NoObjectRows(text: String) {
  Text(
    text,
    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    style = MaterialTheme.typography.bodySmall,
    color = MUTED_TEXT
  )
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

private const val CLASS_COLUMN = "Class"

/** Wide enough for a size in gigabytes, so that the numbers line up down the column. */
private val SIZE_COLUMN_WIDTH = 72.dp

/** Wide enough for one glyph to be pressed, which is all any list has put here. */
private val TRAILING_COLUMN_WIDTH = 32.dp
