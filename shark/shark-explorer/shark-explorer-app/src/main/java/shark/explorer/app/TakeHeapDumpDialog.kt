package shark.explorer.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import shark.SharkLog
import shark.explorer.AndroidDevice
import shark.explorer.DeviceHeapDumps
import shark.explorer.DeviceProcess
import shark.explorer.NativeBitmapPixels

/**
 * Picks a live process off a connected device, dumps its heap and hands the file over to be opened.
 *
 * Two questions, one screen each, because neither has an answer worth guessing: which device, then which
 * of its processes. The dump comes with the pixels of its bitmaps in it wherever the device is new enough
 * for `am dumpheap -b png` — which is why this is worth having over dumping by hand, on top of not having
 * to find the pid. Where it isn't, the bitmaps can be fetched with a debugger in the same go, which is
 * what the checkbox is for.
 *
 * `adb` blocks for tens of seconds on a dump, so all of it runs on [Dispatchers.IO]. Closing the dialog
 * while one is being taken abandons it: the device finishes writing and the file is left in the temp
 * directory, with nothing opening it.
 */
@Composable
internal fun TakeHeapDumpDialog(
  deviceHeapDumps: DeviceHeapDumps,
  /** Hands over the pulled heap dump and, when they were asked for and came back, its bitmaps' pixels. */
  onDumped: (File, NativeBitmapPixels?) -> Unit,
  onDismiss: () -> Unit
) {
  var step: DumpStep by remember { mutableStateOf(DumpStep.ListingDevices) }
  // Kept so that going back from a device's processes doesn't ask adb all over again.
  var devices: List<AndroidDevice> by remember { mutableStateOf(emptyList()) }
  var requestedDevice: AndroidDevice? by remember { mutableStateOf(null) }
  var requestedDump: RequestedDump? by remember { mutableStateOf(null) }
  var fetchesBitmaps by remember { mutableStateOf(false) }

  LaunchedEffect(deviceHeapDumps) {
    step = try {
      devices = withContext(Dispatchers.IO) { deviceHeapDumps.readyDevices() }
      DumpStep.Devices
    } catch (throwable: Throwable) {
      SharkLog.d(throwable) { "Could not list the devices adb is connected to" }
      DumpStep.Failed(throwable.messageForWindow())
    }
  }

  LaunchedEffect(requestedDevice) {
    val device = requestedDevice ?: return@LaunchedEffect
    step = DumpStep.ListingProcesses(device)
    step = try {
      DumpStep.Processes(device, withContext(Dispatchers.IO) { deviceHeapDumps.appProcesses(device) })
    } catch (throwable: Throwable) {
      SharkLog.d(throwable) { "Could not list the processes of ${device.description}" }
      DumpStep.Failed(throwable.messageForWindow())
    }
    requestedDevice = null
  }

  LaunchedEffect(requestedDump) {
    val dump = requestedDump ?: return@LaunchedEffect
    try {
      val heapDumpFile = withContext(Dispatchers.IO) {
        deviceHeapDumps.dumpHeap(dump.device, dump.process) { progress ->
          step = DumpStep.Working(progress)
        }
      }
      // The path, because this dump is kept and nothing else says where: it's a temp file, and the only
      // name the window shows for it is the file name in the bar.
      SharkLog.d { "Pulled a heap dump of ${dump.process.name} to ${heapDumpFile.absolutePath}" }
      val bitmapPixels = if (dump.fetchesBitmaps) {
        deviceHeapDumps.fetchedPixelsOrNull(dump) { progress -> step = DumpStep.Working(progress) }
      } else {
        null
      }
      onDumped(heapDumpFile, bitmapPixels)
    } catch (throwable: Throwable) {
      SharkLog.d(throwable) { "Could not dump the heap of ${dump.process.name}" }
      step = DumpStep.Failed(throwable.messageForWindow())
    }
    requestedDump = null
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(TAKE_HEAP_DUMP_TITLE) },
    text = {
      Column(
        Modifier.heightIn(max = DIALOG_MAX_HEIGHT).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Text(TAKE_HEAP_DUMP_EXPLANATION, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        when (val currentStep = step) {
          DumpStep.ListingDevices -> Waiting(LISTING_DEVICES)
          is DumpStep.ListingProcesses -> Waiting("Asking ${currentStep.device.description} what it runs…")
          is DumpStep.Working -> Waiting(currentStep.message)
          DumpStep.Devices -> Devices(
            devices = devices,
            onPick = { device -> requestedDevice = device }
          )
          is DumpStep.Processes -> Processes(
            processes = currentStep.processes,
            // Only worth offering where the dump can't carry the pixels itself: where it can, it does.
            offersBitmaps = !currentStep.device.canDumpBitmaps,
            fetchesBitmaps = fetchesBitmaps,
            onFetchesBitmapsChange = { fetchesBitmaps = it },
            onPick = { process ->
              requestedDump = RequestedDump(
                device = currentStep.device,
                process = process,
                // A tick survives going back to the device list, and a newer device needs no fetch.
                fetchesBitmaps = fetchesBitmaps && !currentStep.device.canDumpBitmaps
              )
            },
            onBack = { step = DumpStep.Devices }
          )
          is DumpStep.Failed -> Text(
            currentStep.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
          )
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(CLOSE)
      }
    }
  )
}

/** One button per device, saying what a dump of it would and wouldn't have in it. */
@Composable
private fun Devices(
  devices: List<AndroidDevice>,
  onPick: (AndroidDevice) -> Unit
) {
  if (devices.isEmpty()) {
    Text(NO_DEVICES, style = MaterialTheme.typography.bodyMedium)
    return
  }
  devices.forEach { device ->
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
      TextButton(onClick = { onPick(device) }) {
        Text(device.description)
      }
      Text(device.bitmapExplanation, style = MaterialTheme.typography.bodySmall)
    }
  }
}

/**
 * One button per app process of the device picked, plus the way back to the device list.
 *
 * Picking a process starts the dump, so anything to decide about it has to be decided here — which is
 * why the bitmap checkbox is above the buttons rather than on a screen of its own.
 */
@Composable
private fun Processes(
  processes: List<DeviceProcess>,
  offersBitmaps: Boolean,
  fetchesBitmaps: Boolean,
  onFetchesBitmapsChange: (Boolean) -> Unit,
  onPick: (DeviceProcess) -> Unit,
  onBack: () -> Unit
) {
  if (processes.isEmpty()) {
    Text(NO_APP_PROCESSES, style = MaterialTheme.typography.bodyMedium)
  } else {
    Text(PICK_A_PROCESS, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    if (offersBitmaps) {
      FetchBitmapsCheckbox(checked = fetchesBitmaps, onCheckedChange = onFetchesBitmapsChange)
    }
    processes.forEach { process ->
      TextButton(onClick = { onPick(process) }) {
        Text("${process.name} · pid ${process.processId}")
      }
    }
  }
  HorizontalDivider()
  TextButton(onClick = onBack) {
    Text(BACK_TO_DEVICES)
  }
}

/**
 * The offer to have the process compress its bitmaps for a debugger straight after the dump, for a
 * device whose dump can't carry them.
 *
 * It says what it costs, because it's minutes of a suspended app for a large one and there's no way to
 * tell from here how many bitmaps a process holds.
 */
@Composable
private fun FetchBitmapsCheckbox(
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit
) {
  Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
    Row(
      Modifier.fillMaxWidth()
        .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange),
      verticalAlignment = Alignment.CenterVertically
    ) {
      // Null, so the row is the one thing a click lands on: a box that handles its own clicks and a
      // label that doesn't is two targets for one answer.
      Checkbox(checked = checked, onCheckedChange = null)
      Text(FETCH_BITMAPS_WITH_DUMP, style = MaterialTheme.typography.bodyMedium)
    }
    Text(FETCH_BITMAPS_WITH_DUMP_EXPLANATION, style = MaterialTheme.typography.bodySmall)
  }
}

/**
 * Where the dialog is: asking `adb` something, offering what it answered, or working on a device —
 * dumping a heap, and then fetching bitmaps if that was asked for.
 */
private sealed interface DumpStep {

  object ListingDevices : DumpStep

  object Devices : DumpStep

  class ListingProcesses(val device: AndroidDevice) : DumpStep

  class Processes(
    val device: AndroidDevice,
    val processes: List<DeviceProcess>
  ) : DumpStep

  class Working(val message: String) : DumpStep

  class Failed(val message: String) : DumpStep
}

private class RequestedDump(
  val device: AndroidDevice,
  val process: DeviceProcess,
  /** Whether to go back to the process for its bitmaps' pixels once the dump has been pulled. */
  val fetchesBitmaps: Boolean
)

/**
 * The pixels of the bitmaps of [dump]'s process, or null when they couldn't be read.
 *
 * A failed fetch is not a failed dump: the dump is tens of megabytes already pulled, and the window
 * offers the fetch again by itself — with whatever went wrong, if it's pressed then. Throwing the dump
 * away over the extra would be the wrong way round.
 */
private suspend fun DeviceHeapDumps.fetchedPixelsOrNull(
  dump: RequestedDump,
  onProgress: (String) -> Unit
): NativeBitmapPixels? = try {
  withContext(Dispatchers.IO) { fetchBitmaps(dump.device, dump.process, onProgress) }
} catch (throwable: Throwable) {
  SharkLog.d(throwable) { "Could not fetch the bitmaps of ${dump.process.name}, so the dump has none" }
  null
}

/**
 * The devices worth offering: the ones ready to be talked to.
 *
 * An `offline` or `unauthorized` device can't be asked anything — `adb shell` against one blocks until
 * someone taps the dialog on it — and there is nothing to choose about it either way.
 */
private fun DeviceHeapDumps.readyDevices(): List<AndroidDevice> = connectedDevices().filter { it.isReady }

/** Whether a dump of this device will have the pixels of its bitmaps in it, which is most of the point. */
private val AndroidDevice.bitmapExplanation: String
  get() = if (canDumpBitmaps) {
    "The dump will include the pixels of its bitmaps."
  } else {
    "API $sdkInt can't put the pixels of a bitmap in a heap dump, so the dump will have none — they " +
      "have to be fetched off the process, either with the dump or once it's open."
  }

internal const val TAKE_HEAP_DUMP = "Take heap dump…"
internal const val TAKE_HEAP_DUMP_TITLE = "Take a heap dump off a device"
internal const val TAKE_HEAP_DUMP_EXPLANATION =
  "Dumping a heap freezes the app for a moment and pulls tens of megabytes over adb. Only a debuggable " +
    "app can be dumped."
internal const val PICK_A_PROCESS = "Which process?"
internal const val FETCH_BITMAPS_WITH_DUMP = "Fetch the pixels of its bitmaps too"
internal const val FETCH_BITMAPS_WITH_DUMP_EXPLANATION =
  "Slow: a debugger attaches to the app and has it compress every bitmap, a few seconds of fixed cost " +
    "plus a fraction of a second per bitmap, and the app is suspended for all of it. The same fetch is " +
    "offered once the dump is open, so tick this when there's time to get everything in one go."
internal const val NO_APP_PROCESSES = "No app is running on this device."
internal const val BACK_TO_DEVICES = "← Other devices"
