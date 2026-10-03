package shark.dive.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.GcRoot.JniGlobal
import shark.ValueHolder.ReferenceHolder
import shark.dive.Adb
import shark.dive.AdbOutput
import shark.dive.DeviceHeapDumps
import shark.dive.HeapDominatorTreemap
import shark.dive.Place
import shark.dump

/**
 * What the heap dump says about itself, drawn in the window: LeakCanary's own metadata map, the one printed
 * above every leak trace it writes, read here off the dump this window has open.
 *
 * Its keys and its values are LeakCanary's, which is the whole of what the screen is for, so that is what
 * these tests assert: a row of it has to be the line somebody has in a `leaks.txt` beside them, down to the
 * spelling of the key and the number of digits in the value.
 */
@OptIn(ExperimentalTestApi::class)
class MetadataScreenTest {

  @get:Rule
  val testFolder = TemporaryFolder()

  /** Every UI test here records what Shark logged. See [RecordedLog]. */
  @get:Rule val logged = RecordedLog()

  @Test fun `the button leads to what LeakCanary says about this heap dump`() {
    diveUiTest {
      openMetadata(androidHeapDump())

      // The device and the heap, as the extractor spells them: `Build.VERSION.SDK_INT`, not `API level`.
      metadataRow("Build.VERSION.SDK_INT", SDK_INT.toString()).assertIsDisplayed()
      metadataRow("Build.MANUFACTURER", MANUFACTURER).assertIsDisplayed()
      metadataRow("Bitmap count", "1").assertIsDisplayed()
      // `Unknown` rather than a missing row, which is what the extractor answers for a dump no LeakCanary
      // wrote — and most dumps read here are that: `am dumpheap`, or this app's own **Take heap dump…**.
      metadataRow("LeakCanary version", "Unknown").assertIsDisplayed()
    }
  }

  @Test fun `a number is the one LeakCanary printed rather than one rounded for the screen`() {
    diveUiTest {
      openMetadata(androidHeapDump())

      // Digits and nothing else: no `1.2 MB`, no thousands separator. A figure read here is worth reading
      // because it is the same figure as the one in the report somebody was sent, and rounding it for the
      // eye is exactly what stops it being that. See [MetadataScreen].
      assertThat(valueOf("Heap total bytes")).matches("\\d+")
      assertThat(valueOf("Instance count")).matches("\\d+")
    }
  }

  @Test fun `a heap dump that is not Android's says so, rather than opening on nothing`() {
    diveUiTest {
      openMetadata(jvmHeapDump())

      // Every line of this map is read off the Android framework, so a JVM dump has none of it. Which is a
      // thing about the file rather than a failure of this window, and the screen names the class it
      // looked for so that the two can be told apart.
      onNodeWithText(NO_METADATA).assertIsDisplayed()
    }
  }

  @Test fun `the pass over the heap dump this costs is not made until somebody asks for it`() {
    diveUiTest {
      val heapDumpFile = androidHeapDump()
      openWindow(heapDumpFile)

      // Reading it sums the size of every object in the dump, which is seconds on a large one. A window
      // opens on the map, so nothing of this is read for the windows nobody opens this screen in.
      assertThat(readsOfMetadata(heapDumpFile)).isZero()

      screenButton(Place.METADATA_LABEL).performClick()
      waitUntilAtLeastOneExists(hasText("Build.MANUFACTURER"), OPEN_TIMEOUT_MILLIS)
      assertThat(readsOfMetadata(heapDumpFile)).isOne()

      // And once per window rather than once per visit: what a heap dump says about itself doesn't change
      // while it is open, so coming back to the tab redraws what was read the first time.
      tab(HeapDominatorTreemap.ROOT_LABEL).performClick()
      tab(Place.METADATA_LABEL).performClick()
      waitUntilAtLeastOneExists(hasText("Build.MANUFACTURER"), OPEN_TIMEOUT_MILLIS)
      assertThat(readsOfMetadata(heapDumpFile)).isOne()
    }
  }

  /** Opens a window on [heapDumpFile] and goes to the metadata screen, which is where these tests start. */
  private fun ComposeUiTest.openMetadata(heapDumpFile: File) {
    openWindow(heapDumpFile)
    screenButton(Place.METADATA_LABEL).performClick()
    waitUntilAtLeastOneExists(
      hasText("Build.MANUFACTURER").or(hasText(NO_METADATA)),
      OPEN_TIMEOUT_MILLIS
    )
  }

  private fun ComposeUiTest.openWindow(heapDumpFile: File) {
    setContent {
      MaterialTheme {
        DiveApp(
          heapDumpFile = heapDumpFile,
          // Nothing here opens a second heap dump, and which window one would land in is `DiveWindowTest`'s.
          onHeapDumpChosen = { _, _ -> },
          // An `adb` connected to nothing, rather than the one on this machine: a test that shells out has
          // whatever devices happen to be plugged in to answer for.
          deviceHeapDumps = DeviceHeapDumps(NO_DEVICE_ADB)
        )
      }
    }
    waitForTheTree(OPEN_TIMEOUT_MILLIS)
  }

  /** A row of the screen, which is one node: see [MetadataScreen]. */
  private fun ComposeUiTest.metadataRow(
    name: String,
    value: String
  ): SemanticsNodeInteraction = onNode(hasText(name) and hasText(value))

  /** The right hand column of the row [name] heads, read off the node the two of them are. */
  private fun ComposeUiTest.valueOf(name: String): String =
    onNodeWithText(name).fetchSemanticsNode().config[SemanticsProperties.Text].last().text

  /** How many times this window has read what [heapDumpFile] says about itself. See `HeapDumpSession`. */
  private fun readsOfMetadata(heapDumpFile: File) =
    logged.count { it == "Reading what ${heapDumpFile.name} says about itself" }

  /**
   * A button on the row of screens an open heap dump can be read through, as against the tab of the same
   * name that clicking it opens. See [DiveAppTest] for why the role is what tells them apart.
   */
  private fun ComposeUiTest.screenButton(label: String) = onNode(hasText(label) and isButton())

  private fun ComposeUiTest.tab(title: String) = onNode(hasText(title) and isTab())

  private fun isButton(): SemanticsMatcher =
    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

  private fun isTab(): SemanticsMatcher =
    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

  /**
   * A heap dump with the `android.os.Build` a device writes and one bitmap in it, which is enough of an
   * Android heap dump for the extractor to answer about every section of this screen.
   */
  private fun androidHeapDump(): File =
    testFolder.newFile("android.hprof").apply { writeBitmapHeapDump(hasPixels = true) }

  /** And one from a JVM: an object holding an array, and nothing of Android anywhere in it. */
  private fun jvmHeapDump(): File {
    val file = testFolder.newFile("jvm.hprof")
    file.dump {
      val holder = "com.example.Holder" instance {
        field["payload"] =
          ReferenceHolder(objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_LENGTH)))
      }
      gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
    }
    return file
  }

  companion object {
    /** Opening a heap dump, laying the tree out and reading the metadata all happen on another thread. */
    private const val OPEN_TIMEOUT_MILLIS = 10_000L

    /** An `adb` that answers as if nothing were plugged in, so no test here reaches a real device. */
    private val NO_DEVICE_ADB = Adb { AdbOutput(exitCode = 0, text = "List of devices attached\n") }
  }
}
