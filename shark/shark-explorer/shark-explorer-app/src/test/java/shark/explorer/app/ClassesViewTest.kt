package shark.explorer.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.GcRoot.JniGlobal
import shark.ValueHolder.ReferenceHolder
import shark.dump

/**
 * The window with the heap dump read from the classes up, which is a view of piles rather than of objects:
 * every cell of it stands for the objects of one class at one point of a column, so what the panels say
 * about one is not what they say anywhere else.
 *
 * The geometry is the other thing only a window shows. This view's rows grow **up** from the whole heap
 * dump, so the row across the bottom is the one it opens on — see `StackLayoutTest` for the arithmetic and
 * [classesRow] for how a test points at a row of it.
 *
 * The tree itself is unit tested by `ReverseDominatorTreeTest` in `shark-explorer-core`.
 */
@OptIn(ExperimentalTestApi::class)
class ClassesViewTest {

  @get:Rule
  val testFolder = TemporaryFolder()

  /** Every UI test here records what Shark logged. See [RecordedLog]. */
  @get:Rule val logged = RecordedLog()

  @Test fun `the row across the bottom is the whole heap dump`() {
    explorerUiTest {
      openHeapDump(oneClassPerPayloadHeapDump())
      showTheClassesView()

      clickAt(classesRow(WHOLE_HEAP_DUMP_ROW))

      // Which is what makes the view readable without scrolling it: the row every column stands on is the
      // one always in front of the reader, and the classes are the row above it.
      waitUntilAtLeastOneExists(hasText(WHOLE_HEAP_DUMP_ROW_EXPLANATION), OPEN_TIMEOUT_MILLIS)
    }
  }

  @Test fun `pointing at a row says which class it gathers`() {
    explorerUiTest {
      openHeapDump(oneClassPerPayloadHeapDump())
      showTheClassesView()

      hoverAt(classesRow(CLASS_ROW, WIDEST_CELL_X))

      // The row has room for `250 × Object[]` and no more, so which `Object[]` that is arrives at the
      // pointer, as it does for a pile of objects on the map.
      waitUntilAtLeastOneExists(hasText(OBJECT_ARRAY_CLASS_NAME), OPEN_TIMEOUT_MILLIS)
      // And that it is a pile at all, said the way this view means it: a click gives what dominates these
      // the width rather than reaching the objects, which are on this very row already.
      onNodeWithText(ROW_OF_OBJECTS).assertIsDisplayed()
    }
  }

  @Test fun `clicking a row says what holds it, going down`() {
    explorerUiTest {
      openHeapDump(oneClassPerPayloadHeapDump())
      showTheClassesView()

      clickAt(classesRow(CLASS_ROW, WIDEST_CELL_X))

      // Which is the question the view is for, and the one thing the map beside it can't answer: these
      // bytes are held by this, which is held by that, down to the whole heap dump.
      waitUntilAtLeastOneExists(hasText(CLASS_ROW_EXPLANATION), OPEN_TIMEOUT_MILLIS)
      onNodeWithText(COLUMN_BELOW).assertIsDisplayed()
      // How wide it is rather than what it retains, which for a row of arrays is not the same number.
      onNodeWithText(ACCOUNTS_FOR, substring = true).assertIsDisplayed()
      assertThat(onAllNodesWithText(OBJECT_ARRAY_CLASS_NAME).fetchSemanticsNodes()).isNotEmpty()
    }
  }

  @Test fun `pointing at the classes a row had no room for says they are classes`() {
    explorerUiTest {
      // A class per payload, more of them than a row draws one by one, so the row above the arrays has a
      // cell standing for the ones it left out.
      openHeapDump(oneClassPerPayloadHeapDump())
      showTheClassesView()

      hoverAt(classesRow(DOMINATOR_ROW, LEFTOVER_CELL_X))

      waitUntilAtLeastOneExists(hasText(SMALLER_ROWS, substring = true), OPEN_TIMEOUT_MILLIS)
      // Rows rather than objects, and a click that leads down rather than up: everything a pile of
      // siblings says on the map is the other way round here.
      onNodeWithText(LEFTOVER_ROWS).assertIsDisplayed()
    }
  }

  /** Switches the view to the classes and waits for that tree to be laid out. */
  private fun ComposeUiTest.showTheClassesView() {
    shapeOption(ViewShape.CLASSES).performClick()
    // Reading the classes off the heap dump is a pass over every object of it, so the view comes back a
    // beat later — and on a first switch, several of them.
    waitForTheTree(OPEN_TIMEOUT_MILLIS)
  }

  /** The radio button for [shape] above the view. */
  private fun ComposeUiTest.shapeOption(shape: ViewShape): SemanticsNodeInteraction =
    onNode(hasText(shape.displayName) and isSelectable())

  private fun ComposeUiTest.hoverAt(offset: Offset) {
    onRoot().performMouseInput { hover(offset) }
  }

  private fun ComposeUiTest.clickAt(offset: Offset) {
    onRoot().performMouseInput { click(offset) }
  }

  private fun ComposeUiTest.openHeapDump(heapDumpFile: File) {
    setContent {
      MaterialTheme {
        var shown: File? by remember { mutableStateOf(heapDumpFile) }
        ExplorerApp(shown, onHeapDumpChosen = { file, _ -> shown = file })
      }
    }
    waitForTheTree(OPEN_TIMEOUT_MILLIS)
  }

  /**
   * A heap dump of [HOLDER_COUNT] payload arrays of one array class, each held by an instance of a class of
   * its own: so the arrays are one row across almost the whole view, and the row above it is one cell per
   * holder class — more of them than a row has room to draw.
   */
  private fun oneClassPerPayloadHeapDump(): File {
    val file = testFolder.newFile("one-class-per-payload.hprof")
    file.dump {
      // Declared once, so that the payloads share a class and therefore a row. The holders don't: the
      // `instance { }` shorthand writes a class per instance, which is what gives this dump its crowd.
      val objectArrayClassId = arrayClass("java.lang.Object")
      repeat(HOLDER_COUNT) { index ->
        val holder = "com.example.Holder$index" instance {
          field["payload"] =
            ReferenceHolder(objectArray(objectArrayClassId, LongArray(HOLDER_PAYLOAD_LENGTH)))
        }
        gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = index.toLong()))
      }
    }
    return file
  }

  companion object {
    /** Past `StackLayout.maxChildrenPerNode`, which is 200, so a level of it has to leave some out. */
    private const val HOLDER_COUNT = 250

    /** Big enough that the arrays are nearly the whole dump, so their row is nearly the whole width. */
    private const val HOLDER_PAYLOAD_LENGTH = 1024

    /** The row this view opens on, counting from the bottom, which is what every column stands on. */
    private const val WHOLE_HEAP_DUMP_ROW = 0

    /** And the row above it, which is one cell per class of the objects of the heap dump. */
    private const val CLASS_ROW = 1

    /** And the row above that, which splits a class row by what dominates the objects on it. */
    private const val DOMINATOR_ROW = 2

    /** Well inside the widest cell of a row, which a stack lays out first and therefore leftmost. */
    private const val WIDEST_CELL_X = 0.05f

    /**
     * And where the cell standing for the classes that didn't fit is: at the far end of the row, since it
     * is what is left after the ones drawn one by one.
     *
     * The holders weigh the same as each other, so the fifty left out are a fifth of the row's width and
     * the row is nearly the whole view: the last tenth of it is inside that cell with room to spare.
     */
    private const val LEFTOVER_CELL_X = 0.9f

    /** What the payloads' class is called in full, which is the line only the pointer has room for. */
    private const val OBJECT_ARRAY_CLASS_NAME = "java.lang.Object[]"

    /** How a pile of rows names itself, whatever a level had no room for. See [Selection.Group.title]. */
    private const val SMALLER_ROWS = "smaller rows"

    /** Opening a heap dump and laying a tree out both happen on another thread. */
    private const val OPEN_TIMEOUT_MILLIS = 10_000L
  }
}
