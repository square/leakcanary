package shark.explorer.app

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
import shark.explorer.Adb
import shark.explorer.AdbOutput
import shark.explorer.DeviceHeapDumps
import shark.explorer.HeapDominatorTreemap

/**
 * Covers taking a heap dump off a device and landing in the explorer with it open.
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
      setExplorerContent(DeviceHeapDumps(adb))

      onNodeWithText(TAKE_HEAP_DUMP).performClick()
      waitUntilAtLeastOneExists(hasText(DEVICE_DESCRIPTION), TIMEOUT_MILLIS)
      onNodeWithText(DEVICE_DESCRIPTION).performClick()
      waitUntilAtLeastOneExists(hasText(PROCESS_ROW), TIMEOUT_MILLIS)
      onNodeWithText(PROCESS_ROW).performClick()

      // No file dialog and no path to type: the dump the device just wrote is the one being explored.
      waitUntilAtLeastOneExists(hasText(HeapDominatorTreemap.ROOT_LABEL, substring = true), TIMEOUT_MILLIS)
      // And it came with the pixels of its bitmaps in it, so there is nothing left to go and fetch.
      assertThat(onAllNodesWithText(FETCH_BITMAPS, substring = true).fetchSemanticsNodes()).isEmpty()
    }
    // Asked for with `-b png`, which is what puts the bitmaps in it.
    assertThat(adb.commands.single { it.contains("am dumpheap") })
      .contains("am dumpheap -b png 1201 /data/local/tmp/")
  }

  @Test fun `a device that can't put bitmaps in a heap dump says so before one is taken`() {
    runComposeUiTest {
      setExplorerContent(DeviceHeapDumps(fakeAdb(deviceSdkInt = 30)))

      onNodeWithText(TAKE_HEAP_DUMP).performClick()

      // The dump is still worth taking, and it's the one thing about it worth knowing in advance.
      waitUntilAtLeastOneExists(hasText("API 30 can't put", substring = true), TIMEOUT_MILLIS)
    }
  }

  @Test fun `a device with no app running on it is not a dead end`() {
    runComposeUiTest {
      setExplorerContent(DeviceHeapDumps(fakeAdb(processLines = "PID NAME\n1 init\n")))

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
  private fun ComposeUiTest.setExplorerContent(deviceHeapDumps: DeviceHeapDumps) = setContent {
    MaterialTheme {
      var shown: File? by remember { mutableStateOf(null) }
      ExplorerApp(
        heapDumpFile = shown,
        onHeapDumpChosen = { shown = it },
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
    processLines: String = "PID NAME\n1 init\n1201 com.example\n"
  ): RecordingAdb {
    val heapDumpFile = testFolder.newFile("pulled.hprof").apply { writeBitmapHeapDump(hasPixels = true) }
    val dumpBytes = heapDumpFile.readBytes()
    return RecordingAdb { arguments ->
      val command = arguments.joinToString(" ")
      when {
        command == "devices" -> "List of devices attached\n$SERIAL\tdevice\n"
        command.endsWith("shell getprop") -> """
          [ro.build.fingerprint]: [$FINGERPRINT]
          [ro.product.model]: [$MODEL]
          [ro.build.version.sdk]: [$deviceSdkInt]
        """.trimIndent()
        command.contains("shell ps") -> processLines
        // What separates an app's process from a native service, which reads just like one.
        command.contains("pm list packages") -> "package:com.example\n"
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
    private const val PROCESS_ROW = "com.example · pid 1201"
  }
}
