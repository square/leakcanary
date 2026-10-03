package shark.dive.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import shark.dive.Topic

/**
 * What the heap dump says about itself, as LeakCanary reports it: one row per key of
 * `shark.AndroidMetadataExtractor`, in the order that extractor writes them.
 *
 * **Its keys and its values, neither renamed nor reformatted**, which is the whole of what this screen is
 * worth. The same map is printed above every leak trace LeakCanary writes, so somebody reading a
 * `leaks.txt` beside this window is comparing one figure against itself — and a byte count rounded to
 * megabytes here, however much easier it is to read, is a figure that no longer matches the report it is
 * supposed to match. The `?` is where that is explained, along with what the odder numbers mean.
 *
 * Which is also why there are no column headers: the keys are sentences of LeakCanary's own
 * (`Build.VERSION.SDK_INT`, `Heap total bytes`), and a word invented here to head a column of them would be
 * this window's name for something it is deliberately not naming.
 */
@Composable
internal fun MetadataScreen(
  /** LeakCanary's map: null until it has been read, and empty for a dump that has none of it. */
  metadata: Map<String, String>?,
  isReading: Boolean,
  /** Where the `?` beside whose words these are goes. See [Explain]. */
  onExplain: (Topic) -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(modifier, color = MaterialTheme.colorScheme.surface) {
    Column(Modifier.fillMaxSize()) {
      Row(
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        // Whose words these are, which is the one thing about this screen that isn't on it: every row below
        // is a key somebody could otherwise take for Shark Dive's own reading of the dump.
        Explain(Topic.HEAP_DUMP_METADATA, onExplain) {
          Text(REPORTED_BY, style = MaterialTheme.typography.bodySmall, color = MUTED_TEXT)
        }
        if (isReading) {
          CircularProgressIndicator(Modifier.size(SPINNER_SIZE), strokeWidth = SPINNER_STROKE)
        }
      }
      HorizontalDivider()
      val rows = metadata.orEmpty().toList()
      // On the map having come back empty rather than on there being no rows yet: the read starts a frame
      // after this screen first draws, so a screen that said so while [metadata] was still null would open
      // every time on the answer for a dump that isn't Android's.
      if (metadata != null && rows.isEmpty()) {
        NoRows(NO_METADATA)
      }
      // Selectable, for the same reason an address is: a line of this is what gets pasted into a bug
      // report beside the leak trace it has to agree with.
      SelectionContainer {
        LazyColumn(Modifier.fillMaxWidth()) {
          items(rows, key = { (name, _) -> name }) { (name, value) ->
            MetadataRow(name, value)
          }
        }
      }
    }
  }
}

/**
 * One key and what it says, as two columns at the left of the window rather than one at either edge.
 *
 * The keys are a column wide enough for the longest of them and the values start where that ends, so a row
 * is read without crossing the window: a value pushed to the right edge of a window someone has dragged
 * wide is a figure a foot away from the name of what it measures.
 *
 * **And one node rather than two**, because a key and its value are one line of a report: two of them are
 * two strings with nothing saying which number belongs to which name, to anything reading this window
 * rather than looking at it.
 */
@Composable
private fun MetadataRow(
  name: String,
  value: String
) {
  Row(
    Modifier
      .fillMaxWidth()
      .padding(horizontal = 12.dp, vertical = 4.dp)
      .semantics(mergeDescendants = true) {},
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.Top
  ) {
    Text(
      name,
      Modifier.width(NAME_COLUMN_WIDTH),
      style = MaterialTheme.typography.bodySmall,
      color = MUTED_TEXT
    )
    Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
  }
}

/**
 * The label the `?` follows, which names where every row came from rather than what the screen is.
 *
 * What the screen is, the tab already says. Whose numbers they are, nothing else on it does — and that is
 * the question a reader has as soon as they wonder whether `Heap total bytes` is the same total the map
 * adds up. It isn't; the page behind the `?` says so.
 */
private const val REPORTED_BY = "LeakCanary"

/**
 * And what it says for a heap dump it has nothing to report about.
 *
 * Names the class, because that is the difference between a dump this app failed on and a dump that was
 * never an Android one — and the second is a file somebody opened expecting the first.
 */
internal const val NO_METADATA = "No metadata: this heap dump records no android.os.Build."

/** Wide enough for the longest key the extractor writes, which is `Large bitmap total bytes`. */
private val NAME_COLUMN_WIDTH = 180.dp

private val SPINNER_SIZE = 12.dp
private val SPINNER_STROKE = 2.dp
