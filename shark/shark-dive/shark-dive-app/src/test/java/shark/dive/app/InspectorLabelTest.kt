package shark.dive.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.GcRoot.JniGlobal
import shark.dump
import shark.dive.Adb
import shark.dive.AdbOutput
import shark.dive.DeviceHeapDumps
import shark.dive.Place
import shark.dive.hexObjectId

/**
 * What Shark's inspectors write about an object, under it in the panel and on its step of the path, read the
 * way a note is: a class an inspector names is a link to that class. See [NoteReader].
 */
@OptIn(ExperimentalTestApi::class)
class InspectorLabelTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  /** Every UI test here records what Shark logged. See [RecordedLog]. */
  @get:Rule val logged = RecordedLog()

  @Test fun `a class an inspector names is drawn as that class, and leads there`() {
    val file = testFolder.newFile("anonymous.hprof")
    var callbackClassId = 0L
    var anonymousObjectId = 0L
    file.dump {
      callbackClassId = clazz(className = CALLBACK_CLASS_NAME)
      // Named the way javac names an anonymous class, which is what Shark's inspector recognizes it by.
      val anonymous = instance(clazz(className = "com.example.Screen\$1", superclassId = callbackClassId))
      gcRoot(JniGlobal(id = anonymous.value, jniGlobalRefId = 0))
      anonymousObjectId = anonymous.value
    }
    diveUiTest {
      setContent {
        MaterialTheme {
          DiveApp(
            heapDumpFile = file,
            linkedPlaces = listOf(Place.Object(anonymousObjectId)),
            openUrl = {},
            // Nothing here opens a second heap dump, and which window one would land in is
            // `DiveWindowTest`'s.
            onHeapDumpChosen = { _, _ -> },
            // An `adb` connected to nothing, rather than the one on this machine.
            deviceHeapDumps = DeviceHeapDumps(NO_DEVICE_ADB)
          )
        }
      }
      waitForTheTree(OPEN_TIMEOUT_MILLIS)

      // `Anonymous subclass of com.example.Callback` as Shark writes it, shortened the way a note shortens a
      // class name once the heap dump has said it has that class.
      val label = hasText("Anonymous subclass of Callback")
      waitUntilAtLeastOneExists(label, RENDER_TIMEOUT_MILLIS)
      onAllNodes(label).onFirst().performFirstLinkClick()

      waitUntilAtLeastOneExists(tabOn(callbackClassId), RENDER_TIMEOUT_MILLIS)
    }
  }

  private fun tabOn(objectId: Long) = hasText(hexObjectId(objectId), substring = true) and isTab()

  private fun isTab(): SemanticsMatcher =
    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

  companion object {
    /** Opening a heap dump and laying its tree out both happen on another thread. */
    private const val OPEN_TIMEOUT_MILLIS = 10_000L

    /** And so does describing an object of it. */
    private const val RENDER_TIMEOUT_MILLIS = 5_000L

    private const val CALLBACK_CLASS_NAME = "com.example.Callback"

    private val NO_DEVICE_ADB = Adb { AdbOutput(exitCode = 0, text = "List of devices attached\n") }
  }
}
