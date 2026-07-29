package shark.explorer.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasText
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
import shark.explorer.HeapTreemap

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

      onNodeWithText(HeapTreemap.ROOT_LABEL, substring = true).assertIsDisplayed()
      onNodeWithText(NO_SELECTION).assertIsDisplayed()
    }
  }

  @Test fun `the chosen heap dump is opened`() {
    runComposeUiTest {
      val heapDumpFile = testHeapDump()
      setContent { MaterialTheme { ExplorerApp(chooseHeapDumpFile = { heapDumpFile }) } }

      onNodeWithText(OPEN_HEAP_DUMP).performClick()

      waitUntilAtLeastOneExists(hasText(HeapTreemap.ROOT_LABEL, substring = true))
    }
  }

  @Test fun `a file that is not a heap dump is reported rather than crashing`() {
    runComposeUiTest {
      val notAHeapDump = testFolder.newFile("not-a-heap-dump.txt").apply { writeText("nope") }
      setContent { MaterialTheme { ExplorerApp(initialHeapDumpFile = notAHeapDump) } }

      waitUntilAtLeastOneExists(hasText("could not be opened", substring = true))
    }
  }

  @Test fun `pressing a rectangle fills the details panel`() {
    runComposeUiTest {
      openHeapDump()

      onRoot().performMouseInput { click(percentOffset(TREEMAP_X, TREEMAP_Y)) }

      onNodeWithText(NO_SELECTION).assertDoesNotExist()
      onNodeWithText("Retained objects").assertIsDisplayed()
    }
  }

  @Test fun `double clicking a rectangle adds a breadcrumb`() {
    runComposeUiTest {
      openHeapDump()
      assertThat(breadcrumbCount()).isEqualTo(1)

      onRoot().performMouseInput { doubleClick(percentOffset(TREEMAP_X, TREEMAP_Y)) }

      assertThat(breadcrumbCount()).isEqualTo(2)
    }
  }

  @Test fun `clicking a breadcrumb zooms back out`() {
    runComposeUiTest {
      openHeapDump()
      onRoot().performMouseInput { doubleClick(percentOffset(TREEMAP_X, TREEMAP_Y)) }
      assertThat(breadcrumbCount()).isEqualTo(2)

      onNodeWithText(HeapTreemap.ROOT_LABEL, substring = true).performClick()

      assertThat(breadcrumbCount()).isEqualTo(1)
    }
  }

  private fun ComposeUiTest.openHeapDump() {
    val heapDumpFile = testHeapDump()
    setContent { MaterialTheme { ExplorerApp(initialHeapDumpFile = heapDumpFile) } }
    waitUntilAtLeastOneExists(hasText(HeapTreemap.ROOT_LABEL, substring = true))
  }

  /** Crumbs are separated by a chevron, so there's one more crumb than there are chevrons. */
  private fun ComposeUiTest.breadcrumbCount(): Int =
    onAllNodesWithText(BREADCRUMB_SEPARATOR).fetchSemanticsNodes().size + 1

  /**
   * A heap dump where one instance is the only path to a large object array, so that a single
   * rectangle and the one nested in it cover almost the whole treemap and can be clicked blind.
   */
  private fun testHeapDump(): File {
    val file = testFolder.newFile("heap.hprof")
    file.dump {
      val payload = ReferenceHolder(objectArray(arrayClass("java.lang.Object"), LongArray(4096)))
      val holder = "com.example.Holder" instance {
        field["payload"] = payload
      }
      gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
    }
    return file
  }

  companion object {
    /** Somewhere in the treemap: below the top bar and breadcrumbs, left of the details panel. */
    private const val TREEMAP_X = 0.4f
    private const val TREEMAP_Y = 0.6f
  }
}
