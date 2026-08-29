package shark.dive.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import shark.dive.ObjectListEntry

/**
 * The objects starred so far, as the same list of objects every other list on this window is.
 *
 * Comparing what two rectangles hold means looking at them one after the other, and a treemap has no room
 * to keep the first one on screen. Starring is how a handful of objects stay comparable.
 *
 * **Read out of the heap dump, not remembered from the moment they were starred.** The list used to keep a
 * copy of each object's sizes, which made it a screen of its own with columns of its own, and made a row go
 * stale the moment a status set by hand changed what an object retains. What is kept is the addresses, in
 * `~/.shark-dive/starred` — see [shark.dive.StarredFile].
 */
@Composable
internal fun StarredScreen(
  entries: List<ObjectListEntry>,
  /** What a retained size here is a share of. See [shark.dive.HeapSizes.stronglyReachableByteCount]. */
  stronglyReachableByteCount: Long,
  onOpen: (Long, OpenIn) -> Unit,
  /** Puts a link to a starred object on the clipboard, beside opening it. See [OpenTarget]. */
  onCopyLink: (Long) -> Unit,
  onRemove: (Long) -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(modifier, color = MaterialTheme.colorScheme.surface) {
    Column(Modifier.fillMaxSize()) {
      ObjectRowHeader(hasTrailing = true)
      HorizontalDivider()
      if (entries.isEmpty()) {
        NoObjectRows(NOTHING_STARRED)
      }
      LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
        items(entries, key = { it.objectId }) { entry ->
          ObjectRow(entry, stronglyReachableByteCount, onOpen, onCopyLink) {
            TextButton(onClick = { onRemove(entry.objectId) }) {
              Text(STARRED_GLYPH)
            }
          }
        }
      }
    }
  }
}

/**
 * What the list says when it has none, which is also what says the star is what fills it.
 *
 * The glyph and nothing about where it is: naming the panel it sits in is a second thing to find, and one
 * that goes stale the day the star moves.
 */
internal const val NOTHING_STARRED = "Nothing starred. $UNSTARRED_GLYPH puts an object here."
