package shark.dive.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * What the two dialogs that reach into a device have in common.
 *
 * Both of them ask `adb` the same first question — which devices are connected — and both spend most of
 * their time waiting on a command that takes seconds. See [TakeHeapDumpDialog] and
 * [BitmapsFromDeviceDialog].
 *
 * **The spinner has to be given a square**, which is why this is [Modifier.size] and not a height. A
 * `CircularProgressIndicator` draws a circle of `size.width` and then spins it about the centre of
 * whatever box it was handed, so in a box that is wider than it is tall those two points are different
 * and the ring orbits that centre instead of turning on it — clipped to a sliver that wanders around,
 * which is what constraining the height alone used to produce here.
 */
@Composable
internal fun Waiting(message: String) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    CircularProgressIndicator(Modifier.size(SPINNER_SIZE), strokeWidth = SPINNER_STROKE)
    Text(message, style = MaterialTheme.typography.bodyMedium)
  }
}

/**
 * One thing to pick out of a list of them: what it is on the left, [detail] hard against the right.
 *
 * A row rather than a button, because thirty buttons stacked up read as thirty separate decisions where
 * a list reads as one. Lining the details up down the right hand edge is the other half of that: a pid
 * in a column is scanned, a pid trailing each name is read.
 */
@Composable
internal fun PickerRow(
  name: String,
  detail: String? = null,
  onClick: () -> Unit
) {
  Row(
    Modifier.fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = ROW_INSET, vertical = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    if (detail != null) {
      Text(
        detail,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  }
}

/** What the rows under it have in common, for a list that runs on past what was being looked for. */
@Composable
internal fun SectionHeader(text: String) {
  Text(
    text,
    Modifier.padding(start = ROW_INSET, top = 12.dp, bottom = 2.dp),
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant
  )
}

/**
 * What went wrong, for someone looking at a window rather than at a stack trace: everything thrown on
 * the way to a device is worded to be read, so the message is the whole of it.
 */
internal fun Throwable.messageForWindow(): String = message ?: toString()

internal const val LISTING_DEVICES = "Asking adb which devices are connected…"
internal const val NO_DEVICES = "adb is not connected to any device."
internal const val CLOSE = "Close"

internal val DIALOG_MAX_HEIGHT = 480.dp

/** What everything a dialog lists lines up on, headings included, so the left edge is one edge. */
internal val ROW_INSET = 8.dp

/** A line of text tall, so that the row it is in is the height of that line and not of a spinner. */
private val SPINNER_SIZE = 18.dp
private val SPINNER_STROKE = 2.dp
