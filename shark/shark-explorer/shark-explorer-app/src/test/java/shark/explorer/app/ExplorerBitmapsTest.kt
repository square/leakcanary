package shark.explorer.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
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
import shark.GcRoot.JniGlobal
import shark.HprofWriterHelper
import shark.ValueHolder
import shark.ValueHolder.BooleanHolder
import shark.ValueHolder.IntHolder
import shark.ValueHolder.LongHolder
import shark.ValueHolder.ReferenceHolder
import shark.dump
import shark.explorer.Adb
import shark.explorer.AdbOutput
import shark.explorer.DeviceBitmaps
import shark.explorer.ExplorerScreen
import shark.explorer.HeapDominatorTreemap

/**
 * Covers what the window does about a bitmap: shows the picture when the heap dump has the pixels, and
 * offers to go and get them off the device when it doesn't.
 *
 * Drawing them on the treemap is [TreemapViewTest]'s, since a cell is not a node anything here can find.
 */
@OptIn(ExperimentalTestApi::class)
class ExplorerBitmapsTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Test fun `a bitmap the heap dump has the pixels of is shown`() {
    runComposeUiTest {
      openHeapDump(bitmapHeapDump(hasPixels = true))
      screenButton(ExplorerScreen.OBJECTS_CRUMB).performClick()
      waitUntilAtLeastOneExists(hasText(BITMAP_ROW), OPEN_TIMEOUT_MILLIS)

      onNodeWithText(BITMAP_ROW).performClick()

      // The panel is where a bitmap is shown as the picture it is. On the map it's a rectangle the size
      // of its share of the heap, which is not a size anything can be recognised at.
      waitUntilAtLeastOneExists(hasContentDescription(BITMAP_DESCRIPTION), OPEN_TIMEOUT_MILLIS)
    }
  }

  @Test fun `a bitmap whose pixels the heap dump doesn't have offers to fetch them off the device`() {
    runComposeUiTest {
      // Which is every bitmap of every dump from API 26 on, unless it was taken with `am dumpheap -b`.
      openHeapDump(bitmapHeapDump(hasPixels = false))
      val fetchLabel = "$FETCH_BITMAPS ${bitmapCountText(1)}"
      waitUntilAtLeastOneExists(hasText(fetchLabel), OPEN_TIMEOUT_MILLIS)

      screenButton(fetchLabel).performClick()

      // The dialog names the device the dump says it came from, and what `adb` says about the devices
      // that are actually there — which here is nothing, because this `adb` is not one.
      waitUntilAtLeastOneExists(hasText(FETCH_BITMAPS_TITLE), OPEN_TIMEOUT_MILLIS)
      onNodeWithText(DUMP_ORIGIN, substring = true).assertIsDisplayed()
      waitUntilAtLeastOneExists(hasText(NO_DEVICES), OPEN_TIMEOUT_MILLIS)
    }
  }

  @Test fun `a heap dump with no bitmap in it says nothing about bitmaps`() {
    runComposeUiTest {
      openHeapDump(noBitmapHeapDump())

      assertThat(onAllNodesWithText(FETCH_BITMAPS, substring = true).fetchSemanticsNodes()).isEmpty()
    }
  }

  private fun ComposeUiTest.openHeapDump(heapDumpFile: File) {
    setContent {
      MaterialTheme {
        ExplorerApp(
          heapDumpFile = heapDumpFile,
          // Nothing here opens a second heap dump, and which window one would land in is
          // `ExplorerWindowTest`'s.
          onHeapDumpChosen = {},
          // An `adb` that is connected to nothing, rather than the one on this machine: a test that
          // shells out has whatever devices happen to be plugged in to answer for.
          deviceBitmaps = DeviceBitmaps(NO_DEVICE_ADB)
        )
      }
    }
    waitUntilAtLeastOneExists(
      hasText(HeapDominatorTreemap.ROOT_LABEL, substring = true),
      OPEN_TIMEOUT_MILLIS
    )
  }

  /** A button on the row of screens an open heap dump can be read through. */
  private fun ComposeUiTest.screenButton(label: String): SemanticsNodeInteraction =
    onNode(hasText(label) and hasClickAction())

  /**
   * A heap dump with one bitmap in it, and the device it came from recorded the way a real one records it.
   *
   * [hasPixels] writes the `mBuffer` a bitmap keeps its pixels in before API 26. Without it the dump is
   * what every device since has written: the size of the bitmap, the address of its pixels, and no pixels.
   */
  private fun bitmapHeapDump(hasPixels: Boolean): File {
    val file = testFolder.newFile("bitmap-$hasPixels.hprof")
    file.dump {
      val bitmapClassId = clazz(
        className = "android.graphics.Bitmap",
        fields = listOf(
          "mWidth" to IntHolder::class,
          "mHeight" to IntHolder::class,
          "mRecycled" to BooleanHolder::class,
          "mNativePtr" to LongHolder::class,
          "mBuffer" to ReferenceHolder::class
        )
      )
      val pixels = if (hasPixels) {
        // Four bytes a pixel, which is what makes them ARGB_8888.
        ReferenceHolder(primitiveByteArray(ByteArray(BITMAP_SIDE * BITMAP_SIDE * 4)))
      } else {
        ReferenceHolder(ValueHolder.NULL_REFERENCE)
      }
      val bitmap = instance(
        classId = bitmapClassId,
        fields = listOf(
          IntHolder(BITMAP_SIDE),
          IntHolder(BITMAP_SIDE),
          BooleanHolder(false),
          LongHolder(NATIVE_POINTER),
          pixels
        )
      )
      gcRoot(JniGlobal(id = bitmap.value, jniGlobalRefId = 0))
      androidBuild()
    }
    return file
  }

  private fun noBitmapHeapDump(): File {
    val file = testFolder.newFile("no-bitmap.hprof")
    file.dump {
      val holder = "com.example.Holder" instance {
        field["payload"] = ReferenceHolder(objectArray(arrayClass("java.lang.Object"), LongArray(64)))
      }
      gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
      androidBuild()
    }
    return file
  }

  /** What `android.os.Build` looks like in a dump, which is how the explorer knows which device to go to. */
  private fun HprofWriterHelper.androidBuild() {
    "android.os.Build" clazz {
      staticField["FINGERPRINT"] = string(
        "google/tokay/tokay:16/BP31.250610.004/13698546:user/release-keys"
      )
      staticField["MANUFACTURER"] = string("Google")
      staticField["MODEL"] = string(MODEL)
    }
    "android.os.Build\$VERSION" clazz {
      staticField["SDK_INT"] = IntHolder(SDK_INT)
    }
  }

  companion object {
    /** Opening a heap dump and rebuilding a tree both happen on another thread. */
    private const val OPEN_TIMEOUT_MILLIS = 10_000L

    /** Big enough that the biggest object of the dump is the bitmap's buffer rather than anything else. */
    private const val BITMAP_SIDE = 64
    private const val BITMAP_ROW = "android.graphics.Bitmap instance"
    private const val NATIVE_POINTER = 0x7f4321L

    private const val MODEL = "Pixel 9"
    private const val SDK_INT = 36

    /** What the dialog says the heap dump came from, off the `android.os.Build` written into it. */
    private const val DUMP_ORIGIN = "Google $MODEL · API $SDK_INT"

    /** What `adb devices` prints when nothing is plugged in, which is every command this test needs. */
    private val NO_DEVICE_ADB = Adb { AdbOutput(exitCode = 0, text = "List of devices attached\n") }
  }
}
