package shark.dive.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.dive.Adb
import shark.dive.AdbOutput
import shark.dive.BitmapDebugger
import shark.dive.DeviceHeapDumps
import shark.dive.EncodedImageFormat
import shark.dive.NativeBitmapPixels

/**
 * Covers taking a heap dump off a device and landing in Shark Dive with it open.
 *
 * The `adb` here answers like a device would but is not one, so what this pins is the flow: two questions
 * in the order they have to be asked, then a dump that opens by itself. What a real device answers is in
 * `notes/bitmaps.md`.
 */
@OptIn(ExperimentalTestApi::class)
class TakeHeapDumpTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Test fun `a device and a process are picked, and the dump that comes back opens`() {
    val adb = fakeAdb()
    runComposeUiTest {
      setDiveContent(DeviceHeapDumps(adb))

      onNodeWithText(TAKE_HEAP_DUMP).performClick()
      waitUntilAtLeastOneExists(hasText(DEVICE_DESCRIPTION), TIMEOUT_MILLIS)
      onNodeWithText(DEVICE_DESCRIPTION).performClick()
      waitUntilAtLeastOneExists(hasText(PROCESS_ROW), TIMEOUT_MILLIS)
      onNodeWithText(PROCESS_ROW).performClick()

      // No file dialog and no path to type: the dump the device just wrote is the one being explored.
      waitForTheTree(TIMEOUT_MILLIS)
      // And it came with the pixels of its bitmaps in it, so there is nothing left to go and fetch.
      assertThat(onAllNodesWithText(FETCH_BITMAPS, substring = true).fetchSemanticsNodes()).isEmpty()
    }
    // Asked for with `-g`, which collects the garbage first, and `-b png`, which puts the bitmaps in it.
    assertThat(adb.commands.single { it.contains("am dumpheap") })
      .contains("am dumpheap -g -b png 1201 /data/local/tmp/")
  }

  @Test fun `a device that can't put bitmaps in a heap dump says so before one is taken`() {
    runComposeUiTest {
      setDiveContent(DeviceHeapDumps(fakeAdb(deviceSdkInt = 30)))

      onNodeWithText(TAKE_HEAP_DUMP).performClick()
      waitUntilAtLeastOneExists(hasText(OLD_DEVICE_DESCRIPTION), TIMEOUT_MILLIS)
      // Not on the device list, which says nothing about bitmaps: it is on the screen where the fetch
      // is offered, next to the checkbox it is the reason for.
      assertThat(onAllNodesWithText(BITMAPS_MISSING, substring = true).fetchSemanticsNodes()).isEmpty()
      onNodeWithText(OLD_DEVICE_DESCRIPTION).performClick()

      // The dump is still worth taking, and it's the one thing about it worth knowing in advance.
      waitUntilAtLeastOneExists(hasText("API 30 $BITMAPS_MISSING", substring = true), TIMEOUT_MILLIS)
    }
  }

  @Test fun `a user build says that only a debuggable app can be dumped`() {
    runComposeUiTest {
      setDiveContent(DeviceHeapDumps(fakeAdb()))

      onNodeWithText(TAKE_HEAP_DUMP).performClick()
      waitUntilAtLeastOneExists(hasText(DEVICE_DESCRIPTION), TIMEOUT_MILLIS)
      onNodeWithText(DEVICE_DESCRIPTION).performClick()

      waitUntilAtLeastOneExists(hasText("ro.debuggable=0", substring = true), TIMEOUT_MILLIS)
    }
  }

  @Test fun `a userdebug build says that any of its processes can be dumped`() {
    runComposeUiTest {
      // `ro.debuggable=1` is what `ActivityManagerService.enforceDebuggable` lets everything through
      // on, so on this one the system's own apps can be dumped as well — which is the whole reason
      // someone reaches for such an image, and the reason "only a debuggable app" is the wrong answer.
      setDiveContent(DeviceHeapDumps(fakeAdb(deviceIsDebuggable = true)))

      onNodeWithText(TAKE_HEAP_DUMP).performClick()
      waitUntilAtLeastOneExists(hasText(DEVICE_DESCRIPTION), TIMEOUT_MILLIS)
      onNodeWithText(DEVICE_DESCRIPTION).performClick()

      waitUntilAtLeastOneExists(hasText("ro.debuggable=1", substring = true), TIMEOUT_MILLIS)
    }
  }

  @Test fun `the system's own processes are listed under their own heading`() {
    runComposeUiTest {
      setDiveContent(
        DeviceHeapDumps(
          fakeAdb(
            processLines = "PID NAME\n1 init\n914 com.android.systemui\n1201 com.example\n",
            installedPackages = "package:com.example\npackage:com.android.systemui\n"
          )
        )
      )

      onNodeWithText(TAKE_HEAP_DUMP).performClick()
      waitUntilAtLeastOneExists(hasText(DEVICE_DESCRIPTION), TIMEOUT_MILLIS)
      onNodeWithText(DEVICE_DESCRIPTION).performClick()
      waitUntilAtLeastOneExists(hasText(PROCESS_ROW), TIMEOUT_MILLIS)

      // Thirty of the system's own to the one being looked for, so they are below a line rather than
      // mixed in with it.
      assertThat(onAllNodesWithText(SYSTEM_APPS).fetchSemanticsNodes()).hasSize(1)
      assertThat(onAllNodesWithText("com.android.systemui").fetchSemanticsNodes()).hasSize(1)
    }
  }

  @Test fun `a device whose dump carries its bitmaps is not asked about them`() {
    runComposeUiTest {
      setDiveContent(DeviceHeapDumps(fakeAdb()))

      onNodeWithText(TAKE_HEAP_DUMP).performClick()
      waitUntilAtLeastOneExists(hasText(DEVICE_DESCRIPTION), TIMEOUT_MILLIS)
      onNodeWithText(DEVICE_DESCRIPTION).performClick()
      waitUntilAtLeastOneExists(hasText(PROCESS_ROW), TIMEOUT_MILLIS)

      // `am dumpheap -b png` puts them in the dump, so there is nothing to weigh up and nothing to ask.
      assertThat(onAllNodesWithText(FETCH_BITMAPS_WITH_DUMP).fetchSemanticsNodes()).isEmpty()
    }
  }

  @Test fun `a dump of an old device can bring its bitmaps' pixels with it`() {
    runComposeUiTest {
      setDiveContent(oldDeviceHeapDumps(BitmapDebugger { _, _, _ -> FETCHED_PIXELS }))

      onNodeWithText(TAKE_HEAP_DUMP).performClick()
      waitUntilAtLeastOneExists(hasText(OLD_DEVICE_DESCRIPTION), TIMEOUT_MILLIS)
      onNodeWithText(OLD_DEVICE_DESCRIPTION).performClick()
      waitUntilAtLeastOneExists(hasText(FETCH_BITMAPS_WITH_DUMP), TIMEOUT_MILLIS)
      // Ticked before the process is picked, because picking one is what starts the dump.
      onNodeWithText(FETCH_BITMAPS_WITH_DUMP).performClick()
      onNodeWithText(PROCESS_ROW).performClick()

      waitForTheTree(TIMEOUT_MILLIS)
      // The pixels the debugger read are already in, so the dump opens with its pictures and there is
      // nothing left to go back for — which is the whole point of doing it in one go.
      assertThat(onAllNodesWithText(FETCH_BITMAPS, substring = true).fetchSemanticsNodes()).isEmpty()
    }
  }

  @Test fun `a fetch that fails doesn't cost the dump it was asked for`() {
    runComposeUiTest {
      setDiveContent(
        oldDeviceHeapDumps(BitmapDebugger { _, _, _ -> error("The debugger could not attach") })
      )

      onNodeWithText(TAKE_HEAP_DUMP).performClick()
      waitUntilAtLeastOneExists(hasText(OLD_DEVICE_DESCRIPTION), TIMEOUT_MILLIS)
      onNodeWithText(OLD_DEVICE_DESCRIPTION).performClick()
      waitUntilAtLeastOneExists(hasText(FETCH_BITMAPS_WITH_DUMP), TIMEOUT_MILLIS)
      onNodeWithText(FETCH_BITMAPS_WITH_DUMP).performClick()
      onNodeWithText(PROCESS_ROW).performClick()

      // Tens of megabytes were pulled before the fetch was even tried, so the dump opens either way,
      // with the fetch still on offer from the window.
      waitForTheTree(TIMEOUT_MILLIS)
      waitUntilAtLeastOneExists(hasText(FETCH_BITMAPS, substring = true), TIMEOUT_MILLIS)
    }
  }

  @Test fun `a device with no app running on it is not a dead end`() {
    runComposeUiTest {
      setDiveContent(DeviceHeapDumps(fakeAdb(processLines = "PID NAME\n1 init\n")))

      onNodeWithText(TAKE_HEAP_DUMP).performClick()
      waitUntilAtLeastOneExists(hasText(DEVICE_DESCRIPTION), TIMEOUT_MILLIS)
      onNodeWithText(DEVICE_DESCRIPTION).performClick()
      waitUntilAtLeastOneExists(hasText(NO_APP_PROCESSES), TIMEOUT_MILLIS)

      // The way back, since the device that has the app on it may be the other one plugged in.
      onNodeWithText(BACK_TO_DEVICES).performClick()
      waitUntilAtLeastOneExists(hasText(DEVICE_DESCRIPTION), TIMEOUT_MILLIS)
    }
  }

  /**
   * A window with no heap dump in it, which is the state the button is pressed from — and where the dump
   * then opens, since a window showing none takes the first one into itself.
   */
  private fun ComposeUiTest.setDiveContent(deviceHeapDumps: DeviceHeapDumps) = setContent {
    MaterialTheme {
      var shown: File? by remember { mutableStateOf(null) }
      // Whatever came back with the dump travels with it, the way a window keeps both: pixels fetched
      // for one heap dump are pictures of the wrong app in any other. See `DiveWindow`.
      var shownPixels: NativeBitmapPixels? by remember { mutableStateOf(null) }
      DiveApp(
        heapDumpFile = shown,
        bitmapPixels = shownPixels,
        onHeapDumpChosen = { file, fetchedPixels ->
          shown = file
          shownPixels = fetchedPixels
        },
        deviceHeapDumps = deviceHeapDumps
      )
    }
  }

  /**
   * An `adb` that answers like one device with one app on it: it lists itself, says what it is, lists its
   * processes, and hands over a real heap dump when asked to dump one.
   *
   * Matched by command prefix, because the path a dump is written to has a timestamp in it.
   */
  private fun fakeAdb(
    deviceSdkInt: Int = SDK_INT,
    processLines: String = "PID NAME\n1 init\n1201 com.example\n",
    // What a dump of that device would have in it: pixels only where `-b png` is understood.
    dumpHasPixels: Boolean = true,
    // A `user` build, which is what a retail phone and a modern emulator image both are.
    deviceIsDebuggable: Boolean = false,
    installedPackages: String = "package:com.example\n"
  ): RecordingAdb {
    val heapDumpFile = testFolder.newFile("pulled.hprof")
      .apply { writeBitmapHeapDump(hasPixels = dumpHasPixels) }
    val dumpBytes = heapDumpFile.readBytes()
    return RecordingAdb { arguments ->
      val command = arguments.joinToString(" ")
      when {
        command == "devices" -> "List of devices attached\n$SERIAL\tdevice\n"
        command.endsWith("shell getprop") -> """
          [ro.build.fingerprint]: [$FINGERPRINT]
          [ro.product.model]: [$MODEL]
          [ro.build.version.sdk]: [$deviceSdkInt]
          [ro.debuggable]: [${if (deviceIsDebuggable) 1 else 0}]
        """.trimIndent()
        command.contains("shell ps") -> processLines
        // What separates an app's process from a native service, which reads just like one.
        command.contains("pm list packages") -> installedPackages
        // A size that hasn't changed since the last look is what says the process has finished writing.
        command.contains("shell stat") -> dumpBytes.size.toString()
        command.contains(" pull ") -> {
          File(arguments.last()).writeBytes(dumpBytes)
          "1 file pulled"
        }
        else -> ""
      }
    }
  }

  /**
   * A device too old for `am dumpheap -b png`, so its dump has no pixels in it and [bitmapDebugger] is
   * the only way to the ones its process still holds.
   */
  private fun oldDeviceHeapDumps(bitmapDebugger: BitmapDebugger): DeviceHeapDumps = DeviceHeapDumps(
    adb = fakeAdb(deviceSdkInt = OLD_SDK_INT, dumpHasPixels = false),
    bitmapDebugger = bitmapDebugger
  )

  /** An [Adb] that answers from [answer] and remembers what it was asked. */
  private class RecordingAdb(private val answer: (List<String>) -> String) : Adb {

    private val recorded = mutableListOf<String>()

    val commands: List<String> get() = recorded

    override fun run(arguments: List<String>): AdbOutput {
      recorded += arguments.joinToString(" ")
      return AdbOutput(exitCode = 0, text = answer(arguments))
    }
  }

  companion object {
    /** Opening a heap dump and dumping one both happen off the UI thread, and the poll sleeps twice. */
    private const val TIMEOUT_MILLIS = 20_000L

    private const val SERIAL = "emulator-5554"
    private const val DEVICE_DESCRIPTION = "$MODEL · API $SDK_INT · $SERIAL"

    /** A row is the name and the pid in two columns, so the name is what a test clicks. */
    private const val PROCESS_ROW = "com.example"

    /** Older than `am dumpheap -b png`, which is what makes a debugger the only way to the pixels. */
    private const val OLD_SDK_INT = 30
    private const val OLD_DEVICE_DESCRIPTION = "$MODEL · API $OLD_SDK_INT · $SERIAL"

    /**
     * What such a device says about its dumps, after the API level: matched here as a fragment rather
     * than in full, because the sentence after it is about the fetch and not about the device.
     */
    private const val BITMAPS_MISSING = "keeps bitmap pixels out of the dump"

    /** What the debugger reads out of the process: one image, for the one bitmap of the dump. */
    private val FETCHED_PIXELS = NativeBitmapPixels(
      format = EncodedImageFormat.PNG,
      bytesByNativePointer = mapOf(NATIVE_POINTER to pngBytes(BITMAP_SIDE, BITMAP_SIDE))
    )
  }
}
