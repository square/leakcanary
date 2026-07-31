package shark.explorer.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import shark.SharkLog
import shark.explorer.AndroidDevice
import shark.explorer.DeviceHeapDumps
import shark.explorer.DeviceProcess

/**
 * Picks a live process off a connected device, dumps its heap and hands the file over to be opened.
 *
 * Two questions, one screen each, because neither has an answer worth guessing: which device, then which
 * of its processes. The dump comes with the pixels of its bitmaps in it wherever the device is new enough
 * for `am dumpheap -b png` — which is why this is worth having over dumping by hand, on top of not having
 * to find the pid.
 *
 * `adb` blocks for tens of seconds on a dump, so all of it runs on [Dispatchers.IO]. Closing the dialog
 * while one is being taken abandons it: the device finishes writing and the file is left in the temp
 * directory, with nothing opening it.
 */
@Composable
internal fun TakeHeapDumpDialog(
  deviceHeapDumps: DeviceHeapDumps,
  /** Hands over the pulled heap dump, which the window then opens. */
  onDumped: (File) -> Unit,
  onDismiss: () -> Unit
) {
  var step: DumpStep by remember { mutableStateOf(DumpStep.ListingDevices) }
  // Kept so that going back from a device's processes doesn't ask adb all over again.
  var devices: List<AndroidDevice> by remember { mutableStateOf(emptyList()) }
  var requestedDevice: AndroidDevice? by remember { mutableStateOf(null) }
  var requestedDump: RequestedDump? by remember { mutableStateOf(null) }

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
          step = DumpStep.Dumping(progress)
        }
      }
      // The path, because this dump is kept and nothing else says where: it's a temp file, and the only
      // name the window shows for it is the file name in the bar.
      SharkLog.d { "Pulled a heap dump of ${dump.process.name} to ${heapDumpFile.absolutePath}" }
      onDumped(heapDumpFile)
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
          is DumpStep.Dumping -> Waiting(currentStep.message)
          DumpStep.Devices -> Devices(
            devices = devices,
            onPick = { device -> requestedDevice = device }
          )
          is DumpStep.Processes -> Processes(
            processes = currentStep.processes,
            onPick = { process -> requestedDump = RequestedDump(currentStep.device, process) },
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

/** One button per app process of the device picked, plus the way back to the device list. */
@Composable
private fun Processes(
  processes: List<DeviceProcess>,
  onPick: (DeviceProcess) -> Unit,
  onBack: () -> Unit
) {
  if (processes.isEmpty()) {
    Text(NO_APP_PROCESSES, style = MaterialTheme.typography.bodyMedium)
  } else {
    Text(PICK_A_PROCESS, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
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

/** Where the dialog is: asking `adb` something, offering what it answered, or dumping a heap. */
private sealed interface DumpStep {

  object ListingDevices : DumpStep

  object Devices : DumpStep

  class ListingProcesses(val device: AndroidDevice) : DumpStep

  class Processes(
    val device: AndroidDevice,
    val processes: List<DeviceProcess>
  ) : DumpStep

  class Dumping(val message: String) : DumpStep

  class Failed(val message: String) : DumpStep
}

private class RequestedDump(
  val device: AndroidDevice,
  val process: DeviceProcess
)

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
      "can be fetched off the process once it's open."
  }

internal const val TAKE_HEAP_DUMP = "Take heap dump…"
internal const val TAKE_HEAP_DUMP_TITLE = "Take a heap dump off a device"
internal const val TAKE_HEAP_DUMP_EXPLANATION =
  "Dumping a heap freezes the app for a moment and pulls tens of megabytes over adb. Only a debuggable " +
    "app can be dumped."
internal const val PICK_A_PROCESS = "Which process?"
internal const val NO_APP_PROCESSES = "No app is running on this device."
internal const val BACK_TO_DEVICES = "← Other devices"
