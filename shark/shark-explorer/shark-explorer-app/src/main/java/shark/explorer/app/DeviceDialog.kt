package shark.explorer.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
 */
@Composable
internal fun Waiting(message: String) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    CircularProgressIndicator(Modifier.heightIn(max = SPINNER_SIZE).padding(2.dp))
    Text(message, style = MaterialTheme.typography.bodyMedium)
  }
}

/**
 * What went wrong, for someone looking at a window rather than at a stack trace: everything thrown on
 * the way to a device is worded to be read, so the message is the whole of it.
 */
internal fun Throwable.messageForWindow(): String = message ?: toString()

internal const val LISTING_DEVICES = "Asking adb which devices are connected…"
internal const val NO_DEVICES = "adb is not connected to any device."
internal const val CLOSE = "Close"

internal val DIALOG_MAX_HEIGHT = 400.dp
private val SPINNER_SIZE = 20.dp
