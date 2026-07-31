package shark.explorer.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import shark.SharkLog
import shark.explorer.AndroidDevice
import shark.explorer.BitmapCounts
import shark.explorer.DeviceHeapDumps
import shark.explorer.DeviceMatch
import shark.explorer.DeviceProcess
import shark.explorer.HeapDumpOrigin
import shark.explorer.NativeBitmapPixels

/**
 * Picks the live process to fetch the bitmaps of, and fetches them.
 *
 * The steps are the ones the person at the window has to see: which devices `adb` is connected to,
 * which of them is the one this heap dump was written on, which of its processes wrote it. Nothing is
 * picked automatically even when only one thing matches, because pulling the pixels of the wrong
 * process would draw the wrong pictures and nothing about the treemap would say so.
 *
 * `adb` blocks for seconds at a time and dumping a heap for tens of them, so all of it runs on
 * [Dispatchers.IO] and only the pixels come back here.
 */
@Composable
internal fun BitmapsFromDeviceDialog(
  origin: HeapDumpOrigin,
  counts: BitmapCounts,
  deviceHeapDumps: DeviceHeapDumps,
  /** Hands the pixels to the open heap dump, which is a read of it, and says what they matched. */
  onFetched: suspend (NativeBitmapPixels) -> BitmapCounts,
  onDismiss: () -> Unit
) {
  var step: FetchStep by remember { mutableStateOf(FetchStep.Listing) }
  var requestedFetch: RequestedFetch? by remember { mutableStateOf(null) }

  LaunchedEffect(deviceHeapDumps) {
    step = try {
      FetchStep.Devices(withContext(Dispatchers.IO) { deviceHeapDumps.candidatesFor(origin) })
    } catch (throwable: Throwable) {
      SharkLog.d(throwable) { "Could not list the devices adb is connected to" }
      FetchStep.Failed(throwable.messageForWindow())
    }
  }

  LaunchedEffect(requestedFetch) {
    val fetch = requestedFetch ?: return@LaunchedEffect
    step = try {
      val pixels = withContext(Dispatchers.IO) {
        deviceHeapDumps.fetchBitmaps(fetch.device, fetch.process) { progress ->
          step = FetchStep.Fetching(progress)
        }
      }
      FetchStep.Fetched(onFetched(pixels))
    } catch (throwable: Throwable) {
      SharkLog.d(throwable) { "Could not fetch the bitmaps of ${fetch.process.name}" }
      FetchStep.Failed(throwable.messageForWindow())
    }
    requestedFetch = null
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(FETCH_BITMAPS_TITLE) },
    text = {
      Column(
        Modifier.heightIn(max = DIALOG_MAX_HEIGHT).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Text(counts.summary(), style = MaterialTheme.typography.bodyMedium)
        Text(
          "This heap dump was written by ${origin.description}.",
          style = MaterialTheme.typography.bodySmall
        )
        HorizontalDivider()
        when (val currentStep = step) {
          FetchStep.Listing -> Waiting(LISTING_DEVICES)
          is FetchStep.Fetching -> Waiting(currentStep.message)
          is FetchStep.Devices -> Candidates(
            candidates = currentStep.candidates,
            onFetch = { device, process -> requestedFetch = RequestedFetch(device, process) }
          )
          is FetchStep.Fetched -> Text(
            currentStep.counts.fetchedSummary(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
          )
          is FetchStep.Failed -> Text(
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

/** One line per device, and one button per process of it that could have written this heap dump. */
@Composable
private fun Candidates(
  candidates: List<DeviceCandidate>,
  onFetch: (AndroidDevice, DeviceProcess) -> Unit
) {
  if (candidates.isEmpty()) {
    Text(NO_DEVICES, style = MaterialTheme.typography.bodyMedium)
    return
  }
  candidates.forEach { candidate ->
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
      Text(
        candidate.device.description,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold
      )
      Text(candidate.match.explanation, style = MaterialTheme.typography.bodySmall)
      Text(candidate.device.fetchExplanation, style = MaterialTheme.typography.bodySmall)
      if (candidate.processes.isEmpty()) {
        Text(NO_MATCHING_PROCESS, style = MaterialTheme.typography.bodySmall)
      } else {
        candidate.processes.forEach { process ->
          Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onFetch(candidate.device, process) }) {
              Text("Fetch from ${process.name} · pid ${process.processId}")
            }
          }
        }
      }
    }
  }
}

/** Where the dialog is: listing devices, waiting on one, or done with it one way or the other. */
private sealed interface FetchStep {

  object Listing : FetchStep

  class Devices(val candidates: List<DeviceCandidate>) : FetchStep

  class Fetching(val message: String) : FetchStep

  class Fetched(val counts: BitmapCounts) : FetchStep

  class Failed(val message: String) : FetchStep
}

private class RequestedFetch(
  val device: AndroidDevice,
  val process: DeviceProcess
)

/** One connected device, how well it matches the heap dump, and which of its processes could have written it. */
private class DeviceCandidate(
  val device: AndroidDevice,
  val match: DeviceMatch,
  val processes: List<DeviceProcess>
)

/**
 * Every ready device with what it takes to choose one, the closest match to the dump first.
 *
 * Devices that match nothing are listed too rather than filtered out: `adb` is often connected to more
 * than one, and a list that silently drops the one you meant looks like `adb` not seeing it.
 */
private fun DeviceHeapDumps.candidatesFor(origin: HeapDumpOrigin): List<DeviceCandidate> =
  connectedDevices()
    .filter { it.isReady }
    .map { device ->
      DeviceCandidate(
        device = device,
        match = device.matchTo(origin),
        processes = matchingProcesses(device, origin)
      )
    }
    .sortedBy { it.match.ordinal }

private val DeviceMatch.explanation: String
  get() = when (this) {
    DeviceMatch.SAME_BUILD -> "Same build as the heap dump."
    DeviceMatch.SAME_MODEL -> "Same model and API level as the heap dump, but a different build."
    DeviceMatch.OTHER -> "Not the device the heap dump came from."
  }

/**
 * How the pixels would be got off this device, which is worth knowing in advance for the older one: the
 * app is stopped while a debugger reads its bitmaps, and an app that freezes for a few seconds while
 * someone is using it is worth having been warned about.
 */
private val AndroidDevice.fetchExplanation: String
  get() = if (canDumpBitmaps) {
    "Its bitmaps come from dumping the process again, which costs it nothing."
  } else {
    "API $sdkInt can't put bitmaps in a heap dump, so they come from attaching a debugger to the " +
      "process, which suspends the app while they are read."
  }

private fun BitmapCounts.summary(): String = when {
  count == 0 -> "This heap dump has no bitmaps in it."
  withoutImageCount == 0 -> "This heap dump has the pixels of all ${bitmapCountText(count)} in it."
  else -> "This heap dump has ${bitmapCountText(count)} in it, and the pixels of $withImageCount. " +
    "From API 26 a bitmap keeps its pixels in native memory, which a heap dump only carries when it " +
    "was taken with `am dumpheap -b png` — and that needs Android 15. The process that wrote this dump " +
    "still has them, whichever version it runs."
}

private fun BitmapCounts.fetchedSummary(): String {
  val fetched = "Fetched the pixels of $withImageCount of ${bitmapCountText(count)}."
  return if (mismatchedCount == 0) {
    fetched
  } else {
    "$fetched $mismatchedCount were sent an image of the wrong size, which is a bitmap that has been " +
      "recycled since the dump and whose address now belongs to another one."
  }
}

internal const val FETCH_BITMAPS_TITLE = "Bitmaps from the live process"
internal const val NO_MATCHING_PROCESS =
  "No process of that app is running on this device, so its bitmaps are gone."
