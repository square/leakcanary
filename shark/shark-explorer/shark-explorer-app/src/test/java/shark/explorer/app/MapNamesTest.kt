package shark.explorer.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
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
import shark.explorer.formatObjectCount
import shark.explorer.hexObjectId

/**
 * What the names the map draws answer to.
 *
 * A rectangle's children cover every pixel of it, so the name written on it is the one part of it left to
 * see — and therefore the one part left to point at. Pointing at a name means the rectangle it names and
 * clicking one goes there, which is what makes a container reachable without hunting for the pixels of its
 * edge.
 *
 * The names that stand for a pile of objects rather than for one, `400 × Sibling` and `300 smaller objects`,
 * are the other half of it: a rectangle has room for the count and a simple class name and no more, so which
 * pile it is has to be said at the pointer. [ExplorerAppTest] covers the rest of the window.
 */
@OptIn(ExperimentalTestApi::class)
class MapNamesTest {

  @get:Rule
  val testFolder = TemporaryFolder()

  /** Every UI test here records what Shark logged. See [RecordedLog]. */
  @get:Rule val logged = RecordedLog()

  /** The object id of the array in [nestedHeapDump], recorded as the dump is written. */
  private var payloadObjectId = 0L

  /** And of the instance holding it, which is the rectangle the array is drawn inside. */
  private var holderObjectId = 0L

  @Test fun `pointing at a name says what it names rather than what is drawn inside it`() {
    explorerUiTest {
      openHeapDump(nestedHeapDump())

      hoverName()

      // The instance covers the view and the array covers the instance, so every pixel of the instance but
      // the name written on it belongs to the array.
      waitUntilAtLeastOneExists(hasText(hexObjectId(holderObjectId)), OPEN_TIMEOUT_MILLIS)
      assertThat(onAllNodesWithText(hexObjectId(payloadObjectId)).fetchSemanticsNodes()).isEmpty()
    }
  }

  @Test fun `clicking a name goes to what it names`() {
    explorerUiTest {
      openHeapDump(nestedHeapDump())

      clickName()

      // Rather than to the array drawn inside it, which is where a click anywhere else on it lands: the
      // window is about the instance, so the panel beside the map lists the instance's own fields.
      waitUntilAtLeastOneExists(hasText("payload = Object[]"), OPEN_TIMEOUT_MILLIS)
      assertThat(onAllNodesWithText(hexObjectId(holderObjectId)).fetchSemanticsNodes()).isNotEmpty()
    }
  }

  @Test fun `pointing at a class group says which class it gathers`() {
    explorerUiTest {
      // Every instance is a GC root of its own, so all of them land directly under the root and are
      // gathered into one rectangle, which is the biggest thing on the map and so the one that is named.
      openHeapDump(testFolder.crowdedRootHeapDump())

      hoverName()

      // The map has room for `400 × Sibling` and no more, and which Sibling that is, is the question a pile
      // of them raises. So the qualified name is what the pointer gets.
      waitUntilAtLeastOneExists(hasText(SIBLING_CLASS_NAME), OPEN_TIMEOUT_MILLIS)
      onNodeWithText("${formatObjectCount(SIBLING_COUNT)} of one class").assertIsDisplayed()
      // And that it is a pile at all, because a rectangle full of objects looks exactly like one object.
      onNodeWithText(PILE_OF_OBJECTS).assertIsDisplayed()
    }
  }

  @Test fun `pointing at the rectangle standing for the siblings that did not fit says what they are`() {
    explorerUiTest {
      openHeapDump(testFolder.manySiblingsHeapDump())

      hoverView(LEFTOVER_X, LEFTOVER_Y)

      // They have nothing in common but the rectangle they were left out of, so that is the whole of what
      // there is to say about them: how many, how much, and which rectangle to go to for any of them.
      waitUntilAtLeastOneExists(hasText("smaller objects", substring = true), OPEN_TIMEOUT_MILLIS)
      onNodeWithText("Held by Object[]").assertIsDisplayed()
      onNodeWithText(LEFTOVER_OBJECTS).assertIsDisplayed()
    }
  }

  /** Moves the pointer onto the name of the largest of the root's children. See [nameAt]. */
  private fun ComposeUiTest.hoverName() {
    onRoot().performMouseInput { hover(nameAt()) }
  }

  /** And presses it, which is how a rectangle with something drawn inside it is walked into. */
  private fun ComposeUiTest.clickName() {
    onRoot().performMouseInput { click(nameAt()) }
  }

  /**
   * Where the map writes the name of the largest of the root's children, which a squarified treemap puts
   * in the top left corner.
   *
   * A few pixels in rather than at the very corner: the first [EDGE_GRAB] of a rectangle's edge belongs to
   * it whatever is drawn there, so a point in the corner would reach the same rectangle whether its name is
   * a target of its own or not. Density is 1 in a UI test, so these are pixels, and the plate a name sits
   * on is a line of [LABEL_STYLE] text with a couple of them around it.
   */
  private fun ComposeUiTest.nameAt(): Offset {
    val view = viewBounds()
    return Offset(x = view.left + NAME_X, y = view.top + NAME_Y)
  }

  /** Moves the pointer onto a point of the view given as a fraction of it, the view being one canvas. */
  private fun ComposeUiTest.hoverView(
    xFraction: Float,
    yFraction: Float
  ) {
    val view = viewBounds()
    onRoot().performMouseInput {
      hover(Offset(x = view.left + view.width * xFraction, y = view.top + view.height * yFraction))
    }
  }

  /** Where the tree is drawn, which is the one part of the window a press has to land in. */
  private fun ComposeUiTest.viewBounds() =
    onNodeWithContentDescription(VIEW_DESCRIPTION).fetchSemanticsNode().boundsInRoot

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
   * A heap dump where one instance is the only path to a large object array, so that the instance is the
   * root's one child and the array covers all of it but the name the map writes on it.
   */
  private fun nestedHeapDump(): File {
    val file = testFolder.newFile("nested.hprof")
    file.dump {
      val holder = "com.example.Holder" instance {
        val payload =
          ReferenceHolder(objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_LENGTH)))
        payloadObjectId = payload.value
        field["payload"] = payload
      }
      holderObjectId = holder.value
      gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
    }
    return file
  }

  companion object {
    /** Where in a rectangle its name is: past the edge that belongs to it, inside the shortest label. */
    private const val NAME_X = 12f
    private const val NAME_Y = 9f

    /**
     * Well inside the largest rectangle of the second level, which is the one standing for the siblings
     * that didn't fit: every sibling weighs the same, so the ones left out weigh as much as all of them.
     */
    private const val LEFTOVER_X = 0.05f
    private const val LEFTOVER_Y = 0.45f

    /** Opening a heap dump and laying the tree out both happen on another thread. */
    private const val OPEN_TIMEOUT_MILLIS = 10_000L
  }
}
