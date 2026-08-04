package shark.explorer.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import shark.explorer.HeapObjectSummary
import shark.explorer.ObjectDominator
import shark.explorer.formatByteSize
import shark.explorer.formatByteSizeOfTotal
import shark.explorer.hexObjectId

/**
 * The objects starred so far, everything about them read when they were starred.
 *
 * Comparing what two rectangles hold means looking at them one after the other, and a treemap has no room
 * to keep the first one on screen. Starring is how a handful of objects stay comparable.
 */
@Composable
internal fun StarredScreen(
  favourites: List<Favourite>,
  /** What a retained size here is a share of. See [shark.explorer.HeapSizes.stronglyReachableByteCount]. */
  stronglyReachableByteCount: Long,
  onOpen: (Long) -> Unit,
  onRemove: (Long) -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(modifier, color = MaterialTheme.colorScheme.surface) {
    Column(
      Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Text("$STARRED_GLYPH Starred objects", style = MaterialTheme.typography.titleMedium)
      if (favourites.isEmpty()) {
        Text(NOTHING_STARRED, style = MaterialTheme.typography.bodyMedium)
      }
      favourites.forEach { favourite ->
        Row(verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Inspectable(favourite.label, favourite.objectId, onOpen)
            Text(favourite.className, style = MaterialTheme.typography.bodySmall)
            SelectionContainer {
              Text(hexObjectId(favourite.objectId), style = MaterialTheme.typography.bodySmall)
            }
            Text(
              "Retained ${formatByteSizeOfTotal(favourite.retainedSize, stronglyReachableByteCount)} · " +
                "shallow ${formatByteSize(favourite.shallowSize)} · " +
                "dominated by ${favourite.dominatorLabel}",
              style = MaterialTheme.typography.bodySmall
            )
          }
          TextButton(onClick = { onRemove(favourite.objectId) }) {
            Text(STARRED_GLYPH)
          }
        }
      }
    }
  }
}

/** One starred object, with what the list shows about it kept rather than read again. */
internal data class Favourite(
  val objectId: Long,
  val label: String,
  val className: String,
  val shallowSize: Long,
  val retainedSize: Long,
  val dominatorLabel: String
) {
  companion object {
    fun of(
      summary: HeapObjectSummary,
      dominator: ObjectDominator?
    ) = Favourite(
      objectId = summary.objectId,
      label = summary.headline?.let { "${summary.label} · $it" } ?: summary.label,
      className = summary.className,
      shallowSize = summary.shallowSize,
      retainedSize = summary.retainedSize,
      dominatorLabel = dominator?.label ?: UNKNOWN_DOMINATOR
    )
  }
}

private const val NOTHING_STARRED =
  "Nothing is starred. The star next to an object's name in the panel keeps it here, so that two of " +
    "them can be compared without holding one in your head."

private const val UNKNOWN_DOMINATOR = "not read yet"
