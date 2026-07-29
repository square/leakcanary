package shark.explorer.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.GcRoot.JniGlobal
import shark.ValueHolder.ReferenceHolder
import shark.dump
import shark.explorer.HeapDominatorTreemap
import shark.explorer.ReachabilityStrength
import shark.explorer.ReachabilityStrength.STRONG
import shark.explorer.ReachabilityStrength.WEAK
import shark.explorer.formatByteSize

@OptIn(ExperimentalTestApi::class)
class ExplorerAppTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Test fun `nothing is open until a heap dump is chosen`() {
    runComposeUiTest {
      setContent { MaterialTheme { ExplorerApp(chooseHeapDumpFile = { null }) } }

      onNodeWithText(NO_HEAP_DUMP).assertIsDisplayed()
      onNodeWithText(OPEN_HEAP_DUMP).assertIsDisplayed()
    }
  }

  @Test fun `a heap dump passed on the command line is opened`() {
    runComposeUiTest {
      openHeapDump()

      onNodeWithText(HeapDominatorTreemap.ROOT_LABEL, substring = true).assertIsDisplayed()
      onNodeWithText(NO_SELECTION).assertIsDisplayed()
    }
  }

  @Test fun `the chosen heap dump is opened`() {
    runComposeUiTest {
      val heapDumpFile = testHeapDump()
      setContent { MaterialTheme { ExplorerApp(chooseHeapDumpFile = { heapDumpFile }) } }

      onNodeWithText(OPEN_HEAP_DUMP).performClick()

      waitUntilAtLeastOneExists(
        hasText(HeapDominatorTreemap.ROOT_LABEL, substring = true),
        OPEN_TIMEOUT_MILLIS
      )
    }
  }

  @Test fun `a file that is not a heap dump is reported rather than crashing`() {
    runComposeUiTest {
      val notAHeapDump = testFolder.newFile("not-a-heap-dump.txt").apply { writeText("nope") }
      setContent { MaterialTheme { ExplorerApp(initialHeapDumpFile = notAHeapDump) } }

      waitUntilAtLeastOneExists(hasText("could not be opened", substring = true), OPEN_TIMEOUT_MILLIS)
    }
  }

  @Test fun `the whole heap dump is accounted for at the top`() {
    runComposeUiTest {
      openHeapDump()

      onNodeWithText("total", substring = true).assertIsDisplayed()
      onNodeWithText("unreachable", substring = true).assertIsDisplayed()
      // The weakly retained array counts as weakly reachable, whether or not it's in the treemap.
      strengthToggle(WEAK).assertTextContains(
        formatByteSize(WEAK_PAYLOAD_BYTE_SIZE),
        substring = true
      )
    }
  }

  @Test fun `pressing a rectangle fills the details panel`() {
    runComposeUiTest {
      openHeapDump()

      onRoot().performMouseInput { click(percentOffset(TREEMAP_X, TREEMAP_Y)) }

      waitUntilAtLeastOneExists(hasText("Retained objects"), OPEN_TIMEOUT_MILLIS)
      onNodeWithText(NO_SELECTION).assertDoesNotExist()
      onNodeWithText(STRONG.reachabilityText).assertIsDisplayed()
    }
  }

  @Test fun `double clicking a rectangle adds a breadcrumb`() {
    runComposeUiTest {
      openHeapDump()
      assertThat(breadcrumbCount()).isEqualTo(1)

      onRoot().performMouseInput { doubleClick(percentOffset(TREEMAP_X, TREEMAP_Y)) }

      waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) { breadcrumbCount() == 2 }
    }
  }

  @Test fun `clicking a breadcrumb zooms back out`() {
    runComposeUiTest {
      openHeapDump()
      onRoot().performMouseInput { doubleClick(percentOffset(TREEMAP_X, TREEMAP_Y)) }
      waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) { breadcrumbCount() == 2 }

      onNodeWithText(HeapDominatorTreemap.ROOT_LABEL, substring = true).performClick()

      waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) { breadcrumbCount() == 1 }
    }
  }

  @Test fun `following weak references grows the treemap`() {
    runComposeUiTest {
      openHeapDump()
      val stronglyReachable = rootCrumb()
      strengthToggle(WEAK).assertIsOff()

      strengthToggle(WEAK).performClick()

      strengthToggle(WEAK).assertIsOn()
      // The root of the tree now holds what the weak reference points at as well.
      waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) { rootCrumb() != stronglyReachable }
    }
  }

  @Test fun `what only a weak reference points at is drawn as weakly reachable`() {
    runComposeUiTest {
      openHeapDump()
      val stronglyReachable = rootCrumb()
      strengthToggle(WEAK).performClick()
      waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) { rootCrumb() != stronglyReachable }

      // The weakly retained array is by far the biggest thing in the heap dump, so it covers most of
      // the treemap and the weak reference holding it is a thin border around it.
      onRoot().performMouseInput { click(percentOffset(TREEMAP_X, TREEMAP_Y)) }

      waitUntilAtLeastOneExists(hasText(WEAK.reachabilityText), OPEN_TIMEOUT_MILLIS)
    }
  }

  @Test fun `strong references cannot be unfollowed`() {
    runComposeUiTest {
      openHeapDump()

      strengthToggle(STRONG).assertIsOn().assertIsNotEnabled()
    }
  }

  private fun ComposeUiTest.openHeapDump() {
    val heapDumpFile = testHeapDump()
    setContent { MaterialTheme { ExplorerApp(initialHeapDumpFile = heapDumpFile) } }
    waitUntilAtLeastOneExists(
      hasText(HeapDominatorTreemap.ROOT_LABEL, substring = true),
      OPEN_TIMEOUT_MILLIS
    )
  }

  /** The checkbox for [strength] in the top bar, which carries its name and how much it holds. */
  private fun ComposeUiTest.strengthToggle(strength: ReachabilityStrength): SemanticsNodeInteraction =
    onNode(hasText(strength.displayName, substring = true) and isToggleable())

  /** Crumbs are separated by a chevron, so there's one more crumb than there are chevrons. */
  private fun ComposeUiTest.breadcrumbCount(): Int =
    onAllNodesWithText(BREADCRUMB_SEPARATOR).fetchSemanticsNodes().size + 1

  /** The first breadcrumb, which names the root of the tree and says how much it holds. */
  private fun ComposeUiTest.rootCrumb(): String =
    onNodeWithText(HeapDominatorTreemap.ROOT_LABEL, substring = true)
      .fetchSemanticsNode()
      .config[SemanticsProperties.Text]
      .joinToString("")

  /**
   * A heap dump where a single instance is the only path to a large object array, so that one
   * rectangle and the one nested in it cover almost the whole treemap and can be clicked blind, plus
   * a larger array that only a weak reference points at.
   */
  private fun testHeapDump(): File {
    val file = testFolder.newFile("heap.hprof")
    file.dump {
      val holder = "com.example.Holder" instance {
        field["payload"] =
          ReferenceHolder(objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_LENGTH)))
      }
      val weakReference = "java.lang.ref.WeakReference" instance {
        field["referent"] = ReferenceHolder(
          objectArray(arrayClass("java.lang.Object"), LongArray(WEAK_PAYLOAD_LENGTH))
        )
      }
      gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
      gcRoot(JniGlobal(id = weakReference.value, jniGlobalRefId = 1))
    }
    return file
  }

  companion object {
    /** Somewhere in the treemap: below the top bar and breadcrumbs, left of the details panel. */
    private const val TREEMAP_X = 0.4f
    private const val TREEMAP_Y = 0.6f

    /** Opening a heap dump and rebuilding a tree both happen on another thread. */
    private const val OPEN_TIMEOUT_MILLIS = 10_000L

    private const val PAYLOAD_LENGTH = 4096
    private const val WEAK_PAYLOAD_LENGTH = 32768
    private const val WEAK_PAYLOAD_BYTE_SIZE = WEAK_PAYLOAD_LENGTH * 4L
  }
}
