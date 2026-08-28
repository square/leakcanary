package shark.explorer.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * Asks which heap dump a link is about, or where the one it names is — see [LinkedHeapDump].
 *
 * One dialog for both because the answer is the same either way: a path. So the rows are the paths this
 * machine knows of, and the button under them is the file picker for a heap dump that is at neither — or,
 * where there are no rows at all, for the one thing left to try.
 *
 * The rows are directories rather than paths. Everything being picked between has the file name the link
 * says, so the name on every row would be the same word repeated down the dialog with the answer hidden
 * inside it, and it is in the title anyway.
 */
@Composable
internal fun LinkedHeapDumpDialog(
  asked: LinkedHeapDump,
  /** The platform file picker, as anywhere else a heap dump is chosen. Overridden by tests. */
  chooseHeapDumpFile: () -> File?,
  /** The heap dump picked, or null for a question dismissed. See [ExplorerWindows.chooseLinkedHeapDump]. */
  onChosen: (File?) -> Unit
) {
  AlertDialog(
    onDismissRequest = { onChosen(null) },
    title = {
      Text(
        if (asked.choices.isEmpty()) {
          whereIsHeapDumpTitle(asked.heapDumpName)
        } else {
          whichHeapDumpTitle(asked.heapDumpName)
        }
      )
    },
    text = {
      Column(
        Modifier.heightIn(max = DIALOG_MAX_HEIGHT),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Text(asked.question, style = MaterialTheme.typography.bodyMedium)
        if (asked.choices.isNotEmpty()) {
          HorizontalDivider()
          // Scrolled, and only this part of it: what is being asked stays put while the places go past.
          Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            asked.choices.forEach { path ->
              PickerRow(name = path.parent ?: path.path, onClick = { onChosen(path) })
            }
          }
        }
      }
    },
    confirmButton = {
      // A file at neither of the places offered, which for a link from another machine is every heap dump.
      // Not worded like the button in the bar behind this, which opens a heap dump without answering the
      // question: two buttons saying `Open heap dump…` with one of them doing something else is a trap.
      TextButton(onClick = { chooseHeapDumpFile()?.let(onChosen) }) {
        Text(CHOOSE_HEAP_DUMP_FILE)
      }
    },
    dismissButton = {
      TextButton(onClick = { onChosen(null) }) {
        Text(CANCEL_LINK)
      }
    }
  )
}

internal fun whichHeapDumpTitle(heapDumpName: String): String = "Which $heapDumpName?"

internal fun whereIsHeapDumpTitle(heapDumpName: String): String = "Where is $heapDumpName?"

internal const val CHOOSE_HEAP_DUMP_FILE = "Choose file…"

internal const val CANCEL_LINK = "Cancel"
