package shark.explorer.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilExactlyOneExists
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.GcRoot.JniGlobal
import shark.ValueHolder.ReferenceHolder
import shark.dump
import shark.explorer.Adb
import shark.explorer.AdbOutput
import shark.explorer.DeviceHeapDumps
import shark.explorer.ExplorerScreen
import shark.explorer.HeapDominatorTreemap
import shark.explorer.HeapObjectKind
import shark.explorer.ReachabilityStrength
import shark.explorer.ReachabilityStrength.PHANTOM
import shark.explorer.ReachabilityStrength.STRONG
import shark.explorer.ReachabilityStrength.UNREACHABLE
import shark.explorer.ReachabilityStrength.WEAK
import shark.explorer.formatByteSize
import shark.explorer.hexObjectId

@OptIn(ExperimentalTestApi::class)
class ExplorerAppTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  /** The object id of the array in [testHeapDump], recorded as the dump is written. */
  private var payloadObjectId = 0L

  /** And of the instance holding it, which is what the map roots itself at once the array is clicked. */
  private var holderObjectId = 0L

  /** Everything Shark logged during this test, which every test here records. See [RecordedLog]. */
  @get:Rule val logged = RecordedLog()

  @Test fun `nothing is open until a heap dump is chosen`() {
    explorerUiTest {
      setExplorerContent()

      onNodeWithText(NO_HEAP_DUMP).assertIsDisplayed()
      onNodeWithText(OPEN_HEAP_DUMP).assertIsDisplayed()
    }
  }

  @Test fun `a heap dump passed on the command line is opened`() {
    explorerUiTest {
      openHeapDump()

      onNodeWithContentDescription(VIEW_DESCRIPTION).assertIsDisplayed()
      onNodeWithText(NO_SELECTION).assertIsDisplayed()
      onNodeWithText(NO_ROOT_PATH_YET).assertIsDisplayed()
    }
  }

  @Test fun `the chosen heap dump is opened`() {
    explorerUiTest {
      val heapDumpFile = testHeapDump()
      setExplorerContent(chooseHeapDumpFile = { heapDumpFile })

      onNodeWithText(OPEN_HEAP_DUMP).performClick()

      waitForTheTree(OPEN_TIMEOUT_MILLIS)
    }
  }

  @Test fun `a file that is not a heap dump is reported rather than crashing`() {
    explorerUiTest {
      val notAHeapDump = testFolder.newFile("not-a-heap-dump.txt").apply { writeText("nope") }
      setExplorerContent(notAHeapDump)

      waitUntilAtLeastOneExists(hasText("could not be opened", substring = true), OPEN_TIMEOUT_MILLIS)
    }
  }

  /**
   * A window reporting a failure and a log saying nothing about it is a report nobody can answer, so what
   * the window says has to be what the log says too. Where the log goes: [shark.explorer.SessionLog].
   */
  @Test fun `a file that is not a heap dump is logged with what went wrong`() {
    explorerUiTest {
      val notAHeapDump = testFolder.newFile("not-a-heap-dump.txt").apply { writeText("nope") }
      setExplorerContent(notAHeapDump)

      waitUntilAtLeastOneExists(hasText("could not be opened", substring = true), OPEN_TIMEOUT_MILLIS)
    }

    assertThat(logged).anyMatch { "Could not open" in it && "not-a-heap-dump.txt" in it }
  }

  /**
   * What makes a session readable after it: which dump was opened, which step of opening it was running,
   * and every read of it once it's open. See [HeapDumpSession.read].
   */
  @Test fun `opening a heap dump and reading it are logged`() {
    explorerUiTest { openHeapDump() }

    assertThat(logged).anyMatch { it.startsWith("Opening heap dump") }
    assertThat(logged).anyMatch { it.startsWith("Indexing") }
    assertThat(logged).anyMatch { it.startsWith("Opened") }
    assertThat(logged).anyMatch { it.startsWith("Read the sizes of") }
    assertThat(logged).anyMatch { it.startsWith("Read the treemap rooted at") }
  }

  @Test fun `the whole heap dump is accounted for at the top`() {
    explorerUiTest {
      openHeapDump(testFolder.weaklyReachablePayloadHeapDump())

      // The status line is the whole dump, bytes and objects both, and the legend splits it by strength —
      // with a row for the garbage, so that the rows add up to it rather than to some of it.
      onNodeWithText("$WEAKLY_REACHABLE_DUMP_NAME ·", substring = true)
        .assertTextContains("objects", substring = true)
      strengthToggle(WEAK).assertTextContains(
        formatByteSize(WEAK_PAYLOAD_BYTE_SIZE),
        substring = true
      )
      strengthToggle(UNREACHABLE).assertIsDisplayed()
    }
  }

  @Test fun `clicking a rectangle fills the details panel`() {
    explorerUiTest {
      openHeapDump()

      clickView(TREEMAP_X, TREEMAP_Y)

      waitUntilAtLeastOneExists(hasText("Retained objects"), OPEN_TIMEOUT_MILLIS)
      onNodeWithText(NO_SELECTION).assertDoesNotExist()
      onNodeWithText(STRONG.reachabilityText).assertIsDisplayed()
    }
  }

  @Test fun `moving the pointer over a rectangle says what it is at the pointer`() {
    explorerUiTest {
      openHeapDump()

      hoverView(TREEMAP_X, TREEMAP_Y)

      // Reading the map is moving the mouse across it, so what a rectangle is arrives without a click —
      // in the card beside the pointer, which is where the reader is already looking.
      waitUntilAtLeastOneExists(hasText("$PAYLOAD_LENGTH elements"), OPEN_TIMEOUT_MILLIS)
      onNodeWithText(hexObjectId(payloadObjectId)).assertIsDisplayed()
      // And nowhere else: pointing at a rectangle is how you find out whether it's worth going to, so the
      // panel beside the map stays on the object clicked, which here is none.
      onNodeWithText(NO_SELECTION).assertIsDisplayed()
    }
  }

  @Test fun `the card saying what the pointer is on moves with it`() {
    explorerUiTest {
      openHeapDump()
      hoverView(TREEMAP_X, TREEMAP_Y)
      waitUntilAtLeastOneExists(hasText(hexObjectId(payloadObjectId)), OPEN_TIMEOUT_MILLIS)
      val before = pointerCardBounds()

      // Further right along the same rectangle, which is the array covering almost the whole view.
      hoverView(TREEMAP_X + POINTER_STEP, TREEMAP_Y)
      waitForIdle()

      // Beside the pointer wherever it goes, and never under it: the card is a surface, and a surface the
      // pointer ends up inside takes the hover off the map, which would close the card and start over.
      val after = pointerCardBounds()
      assertThat(after.left).isGreaterThan(before.left)
      assertThat(after.top).isEqualTo(before.top)
      assertThat(after.contains(pointerAt(TREEMAP_X + POINTER_STEP, TREEMAP_Y))).isFalse()
    }
  }

  @Test fun `the chain holding an object is one side of the map and what it holds the other`() {
    explorerUiTest {
      openHeapDump()

      // Where the object came from, where it is, and what it is keeping alive, read left to right — rather
      // than the window's two outer edges with everything else between them.
      val view = viewBounds()
      assertThat(onNodeWithText(NO_ROOT_PATH_YET).fetchSemanticsNode().boundsInRoot.right)
        .isLessThanOrEqualTo(view.left)
      assertThat(onNodeWithText(NO_SELECTION).fetchSemanticsNode().boundsInRoot.left)
        .isGreaterThanOrEqualTo(view.right)
    }
  }

  @Test fun `what the pointer adds to the chain drops away when it leaves the map`() {
    explorerUiTest {
      openHeapDump()
      clickView(TREEMAP_X, TREEMAP_Y)
      waitUntilAtLeastOneExists(hasText(hexObjectId(payloadObjectId)), OPEN_TIMEOUT_MILLIS)
      // The band the instance holding the array keeps across the top of the view for itself, which is the
      // one other cell there is to point at once the map has gone to the array.
      hoverRootBand()
      waitUntilAtLeastOneExists(hasText("$HOLDER_LABEL · ", substring = true), OPEN_TIMEOUT_MILLIS)

      leaveView()

      // Back to the chain of the object clicked, and without reading the heap dump again: what was clicked
      // was never thrown away.
      waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) {
        onAllNodesWithText("$HOLDER_LABEL · ", substring = true).fetchSemanticsNodes().isEmpty()
      }
      assertThat(onAllNodesWithText(hexObjectId(payloadObjectId)).fetchSemanticsNodes()).isNotEmpty()
    }
  }

  @Test fun `the chain the pointer adds starts at the rectangle the map is showing`() {
    explorerUiTest {
      openHeapDump()

      hoverView(TREEMAP_X, TREEMAP_Y)

      // Nothing has been clicked, so there is no chain for the pointer's to run on from: the array is held
      // by the instance holding it, and that instance is one of the whole heap dump's own rectangles, so
      // what holds *it* is above the map and the chain starts there rather than at the GC root reaching it.
      // Which is the pointer's question — what is this, here — and not how the map got here.
      waitUntilAtLeastOneExists(hasText("$HOLDER_LABEL · ", substring = true), OPEN_TIMEOUT_MILLIS)
      onNodeWithText("GC root:", substring = true).assertDoesNotExist()
    }
  }

  @Test fun `the chain runs on into the rectangle the pointer is on`() {
    explorerUiTest {
      openHeapDump()
      // The instance holding the array, so that the array drawn inside it is a rectangle to point at that
      // the chain on screen doesn't reach yet.
      clickContainerEdge(yFraction = 0.5f)
      waitUntilAtLeastOneExists(hasText("payload = Object[]"), OPEN_TIMEOUT_MILLIS)

      hoverView(TREEMAP_X, TREEMAP_Y)

      // One chain growing rather than a second one starting: the object being described is on the chain to
      // the rectangle under the pointer, so what the pointer adds is the steps below it and nothing else.
      waitUntilAtLeastOneExists(hasText("Object[] · ", substring = true), OPEN_TIMEOUT_MILLIS)
      assertThat(onAllNodesWithText("com.example.Holder").fetchSemanticsNodes()).isNotEmpty()
      // One chain, so one row for the whole heap dump it hangs below.
      assertThat(onAllNodes(isWholeHeapDumpRow()).fetchSemanticsNodes()).hasSize(1)
    }
  }

  @Test fun `the whole heap dump at the top of the chain is the way back out to it`() {
    explorerUiTest {
      openHeapDump()
      clickView(TREEMAP_X, TREEMAP_Y)
      waitUntilZoomedIn()

      wholeHeapDumpRow().performClick()

      // Back where the window opened, with nothing left of the chain: every chain hangs below the whole heap
      // dump rather than running through it, so the whole heap dump's own chain is that one row.
      waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) {
        onAllNodesWithText("com.example.Holder").fetchSemanticsNodes().isEmpty()
      }
      wholeHeapDumpRow().assertIsDisplayed()
    }
  }

  @Test fun `a chain too tall for its pane is scrolled to the object at the end of it`() {
    explorerUiTest {
      // A dozen objects at four lines each is taller than the pane, and the end of the chain is the object
      // the window is describing: a pane scrolled to the top would be showing the least interesting of it.
      openHeapDump(testFolder.longChainHeapDump())

      clickView(TREEMAP_X, TREEMAP_Y)

      // The links are numbered from the payload out, so the last of them is the one just above it.
      waitUntilAtLeastOneExists(hasText("Link0 instance"), OPEN_TIMEOUT_MILLIS)
      onNodeWithText("Link0 instance").assertIsDisplayed()
      onNodeWithText("Link${CHAIN_LINK_COUNT - 1} instance").assertIsNotDisplayed()
    }
  }

  @Test fun `the chain the pointer adds is condensed to what still fits beside the map`() {
    explorerUiTest {
      openHeapDump()

      hoverView(TREEMAP_X, TREEMAP_Y)

      waitUntilAtLeastOneExists(hasText("$HOLDER_LABEL · ", substring = true), OPEN_TIMEOUT_MILLIS)
      // A chain from a GC root down to a bitmap of a real app is a dozen objects, and four lines each is
      // taller than any window. So the package, the address, which field holds the next object and the label
      // saying which steps own it are all left to the object that was clicked.
      onNodeWithText("com.example.Holder").assertDoesNotExist()
      onNodeWithText(hexObjectId(holderObjectId)).assertDoesNotExist()
      onNodeWithText(DOMINATES_BELOW).assertDoesNotExist()
      // What each step holds stays, beside its class name: it's what the map is being read for.
      onNodeWithText("$HOLDER_LABEL · ", substring = true).assertIsDisplayed()
    }
  }

  @Test fun `the ways an object is held are not searched for the one merely pointed at`() {
    explorerUiTest {
      openHeapDump()

      hoverView(TREEMAP_X, TREEMAP_Y)

      waitUntilAtLeastOneExists(hasText("$PAYLOAD_LENGTH elements"), OPEN_TIMEOUT_MILLIS)
    }

    // A search that indexes the whole heap dump and then walks it several times is nothing to run as the
    // pointer crosses the map, so it waits for a click. Read off the log, since what a search nobody
    // asked for costs is time rather than anything the window shows.
    assertThat(logged).noneMatch { it.startsWith("Reading the other ways") }
  }

  @Test fun `the bar above the map says which instance was clicked`() {
    explorerUiTest {
      openHeapDump()

      clickView(TREEMAP_X, TREEMAP_Y)

      // An object id is how you point something outside the app — a script, a bug report — at this one
      // instance rather than at its class. Above the map rather than in a pane beside it: which object the
      // window is about is as true of a list of objects, where there is no pane.
      waitUntilAtLeastOneExists(hasText(hexObjectId(payloadObjectId)), OPEN_TIMEOUT_MILLIS)
      val printed = onAllNodesWithText(hexObjectId(payloadObjectId)).fetchSemanticsNodes()
      assertThat(printed.minOf { it.boundsInRoot.bottom }).isLessThanOrEqualTo(viewBounds().top)
    }
  }

  @Test fun `the details panel lists the fields of what was clicked`() {
    explorerUiTest {
      openHeapDump()

      clickView(TREEMAP_X, TREEMAP_Y)

      // The rectangle clicked is the payload array nested in the instance holding it, so its fields
      // are its elements, all null in this heap dump.
      waitUntilAtLeastOneExists(hasText("[0] = null"), OPEN_TIMEOUT_MILLIS)
      // What the array is, said twice: once by the panel and once by the chain beside it, which ends at
      // the same object.
      assertThat(onAllNodesWithText("$PAYLOAD_LENGTH elements").fetchSemanticsNodes()).hasSize(2)
    }
  }

  @Test fun `the chain marks what dominates the object clicked, and says it in words`() {
    explorerUiTest {
      openHeapDump()

      clickView(TREEMAP_X, TREEMAP_Y)

      // The holder is the only thing pointing at the array clicked, so it dominates it and points straight
      // at it: marked as such, with no stretch of the chain in between that could have run any other way.
      waitUntilAtLeastOneExists(hasText(DOMINATES_BELOW), OPEN_TIMEOUT_MILLIS)
      onNodeWithText("com.example.Holder").assertIsDisplayed()
      assertThat(onAllNodesWithText(WAYS_FROM_HERE, substring = true).fetchSemanticsNodes()).isEmpty()
    }
  }

  @Test fun `the chain says how else the object clicked is held, and switches between those ways`() {
    explorerUiTest {
      // The array is held by a wrapper the cache holds and by the view the tile holds, and nothing holds
      // both: no object dominates it, so the whole chain is a stretch that didn't have to run as it does.
      openHeapDump(testFolder.cachedPayloadHeapDump())

      clickView(TREEMAP_X, TREEMAP_Y)

      waitUntilAtLeastOneExists(hasText("1 of 2 $WAYS_FROM_HERE"), OPEN_TIMEOUT_MILLIS)
      // Both ways are three objects long, so which of them the chain took is the heap dump's own order.
      val throughTheCache = onAllNodesWithText("com.example.Cache").fetchSemanticsNodes().isNotEmpty()

      onNodeWithText(NEXT_WAY).performClick()

      // The other way, in the same chain rather than on a screen of its own: the reader is switching one
      // stretch of what holds this object, and everything above and below it is where it was.
      waitUntilAtLeastOneExists(hasText("2 of 2 $WAYS_FROM_HERE"), OPEN_TIMEOUT_MILLIS)
      onNodeWithText(if (throughTheCache) "com.example.Tile" else "com.example.Cache").assertIsDisplayed()
      wholeHeapDumpRow().assertIsDisplayed()
    }
  }

  @Test fun `a chain names each object by its class, its package and its address`() {
    explorerUiTest {
      openHeapDump()

      clickView(TREEMAP_X, TREEMAP_Y)

      // A leak trace says what each object is and how much it holds, and so does this: the class to read,
      // the package under it to read past, and the address to point something outside the window at it.
      waitUntilAtLeastOneExists(hasText("com.example.Holder"), OPEN_TIMEOUT_MILLIS)
      onNodeWithText("$HOLDER_LABEL instance").assertIsDisplayed()
      onNodeWithText(hexObjectId(holderObjectId)).assertIsDisplayed()
      // Which field of the holder the array is reached through, the way a leak trace names it.
      onNodeWithText("$HOLDER_LABEL.payload").assertIsDisplayed()
      // And what each of the two retains, which is the size the treemap draws its rectangle at.
      assertThat(onAllNodesWithText("Retaining ", substring = true).fetchSemanticsNodes()).hasSize(2)
    }
  }

  @Test fun `clicking a step of the chain shows that object on the map`() {
    explorerUiTest {
      openHeapDump(testFolder.cachedPayloadHeapDump())
      clickView(TREEMAP_X, TREEMAP_Y)
      waitUntilAtLeastOneExists(hasText("1 of 2 $WAYS_FROM_HERE"), OPEN_TIMEOUT_MILLIS)

      // The middle step of whichever way round the chain runs, both of which are three objects long.
      val middle = listOf("com.example.Wrapper", "com.example.View")
        .first { onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() }
      onNodeWithText(middle).performClick()

      // Described in the panel, on the map where the tree draws it — which here is the top of it: that step
      // is held from two places, so nothing owns it, and what it holds is held from two places as well, so
      // it owns nothing either. One of the whole heap dump's own rectangles, selected there rather than
      // opened into a view with nothing in it.
      waitUntilAtLeastOneExists(hasText("= Object[]", substring = true), OPEN_TIMEOUT_MILLIS)
      onNodeWithContentDescription(VIEW_DESCRIPTION).assertIsDisplayed()
    }
  }

  @Test fun `going back returns to the object the move was made from`() {
    explorerUiTest {
      openHeapDump()
      clickView(TREEMAP_X, TREEMAP_Y)
      waitUntilAtLeastOneExists(hasText("[0] = null"), OPEN_TIMEOUT_MILLIS)
      // Up the chain to the instance holding the array, which is a move like any other.
      onNodeWithText("com.example.Holder").performClick()
      waitUntilAtLeastOneExists(hasText("payload = Object[]"), OPEN_TIMEOUT_MILLIS)

      // Zooming out isn't the way back from a jump sideways.
      onNodeWithText(BACK_ARROW).performClick()

      waitUntilAtLeastOneExists(hasText("[0] = null"), OPEN_TIMEOUT_MILLIS)
      onNodeWithText(FORWARD_ARROW).performClick()
      waitUntilAtLeastOneExists(hasText("payload = Object[]"), OPEN_TIMEOUT_MILLIS)
    }
  }

  @Test fun `a starred object stays readable after moving on to another`() {
    explorerUiTest {
      openHeapDump()
      clickView(TREEMAP_X, TREEMAP_Y)
      waitUntilAtLeastOneExists(hasText("Retained objects"), OPEN_TIMEOUT_MILLIS)

      onNodeWithText(UNSTARRED_GLYPH, substring = true).performClick()
      // Moving on to the object that dominates it, which is what leaves the panel describing something else.
      onNodeWithText("com.example.Holder").performClick()
      waitUntilAtLeastOneExists(hasText("payload = Object[]"), OPEN_TIMEOUT_MILLIS)

      screenButton("$STARRED_GLYPH 1 starred").performClick()

      // Comparing two rectangles means looking at them one after the other, and a treemap has no room to
      // keep the first on screen. So the list keeps everything the panel had read about it.
      onNodeWithText("Starred objects", substring = true).assertIsDisplayed()
      onNodeWithText(hexObjectId(payloadObjectId)).assertIsDisplayed()
      onNodeWithText("dominated by $HOLDER_LABEL", substring = true).assertIsDisplayed()
    }
  }

  @Test fun `clicking a field in the details panel inspects what it points at`() {
    explorerUiTest {
      openHeapDump()
      clickView(TREEMAP_X, TREEMAP_Y)
      // Up the chain to the instance holding the array clicked, which is what dominates it.
      waitUntilAtLeastOneExists(hasText("com.example.Holder"), OPEN_TIMEOUT_MILLIS)
      onNodeWithText("com.example.Holder").performClick()
      waitUntilAtLeastOneExists(hasText("payload = Object[]"), OPEN_TIMEOUT_MILLIS)

      // And back down through the field, without touching the treemap.
      onNodeWithText("payload = Object[]").performClick()

      waitUntilAtLeastOneExists(hasText("$PAYLOAD_LENGTH elements"), OPEN_TIMEOUT_MILLIS)
    }
  }

  @Test fun `clicking a rectangle goes to it`() {
    explorerUiTest {
      openHeapDump()

      clickView(TREEMAP_X, TREEMAP_Y)

      // One click rather than two: reading a treemap is walking into it, and a click that only outlined a
      // rectangle spent the obvious gesture on the thing the panels do anyway.
      waitUntilZoomedIn()
      // Drawing an object that dominates nothing would be a view with one rectangle in it, so the map lands
      // on the instance holding the array clicked, with the array drawn and described inside it.
      assertThat(onAllNodesWithText(hexObjectId(payloadObjectId)).fetchSemanticsNodes()).isNotEmpty()
    }
  }

  @Test fun `clicking an object of the chain beside the map goes back out to it`() {
    explorerUiTest {
      openHeapDump()
      clickView(TREEMAP_X, TREEMAP_Y)
      waitUntilZoomedIn()

      // Which is the only way back out: the objects the chain draws are the ones the map is nested in, so
      // clicking one of them is a zoom back out to it.
      onNodeWithText("com.example.Holder").performClick()

      waitUntilAtLeastOneExists(hasText("payload = Object[]"), OPEN_TIMEOUT_MILLIS)
      assertThat(onAllNodesWithText(hexObjectId(holderObjectId)).fetchSemanticsNodes()).isNotEmpty()
    }
  }

  @Test fun `clicking the rectangle standing for the siblings that did not fit says what it stands for`() {
    explorerUiTest {
      // Every sibling weighs the same, so the rectangle standing for the ones left out weighs as much
      // as all of them together: it's the largest, and a squarified treemap puts that one in the top
      // left corner.
      openHeapDump(testFolder.manySiblingsHeapDump())

      clickView(LEFTOVER_X, LEFTOVER_Y)

      waitUntilAtLeastOneExists(hasText("smaller objects", substring = true), OPEN_TIMEOUT_MILLIS)
      onNodeWithText("Held by Object[]", substring = true).assertIsDisplayed()
    }
  }

  @Test fun `clicking a class group says how many objects of that class it stands for`() {
    explorerUiTest {
      // Every instance is a GC root of its own, so all 400 land directly under the root: far more than
      // a view can draw one by one, which is what gathers them by class.
      openHeapDump(testFolder.crowdedRootHeapDump())

      clickContainerEdge(yFraction = 0.5f)

      waitUntilAtLeastOneExists(hasText("of one class", substring = true), OPEN_TIMEOUT_MILLIS)
      assertThat(onAllNodesWithText(SIBLING_CLASS_NAME).fetchSemanticsNodes()).isNotEmpty()
      // Says it in words as well as with the label, the slate fill and the dashed outline.
      onNodeWithText(CLASS_GROUP_EXPLANATION).assertIsDisplayed()
      onNodeWithText("Retained together").assertIsDisplayed()
    }
  }

  @Test fun `clicking a class group roots the map at it`() {
    explorerUiTest {
      openHeapDump(testFolder.crowdedRootHeapDump())

      clickContainerEdge(yFraction = 0.5f)

      // A class group is no object of the heap dump, but it is a node of the tree, so the map opens it the
      // same way it opens an object: the instances gathered into it are what's drawn inside.
      waitUntilZoomedIn()
      assertThat(onAllNodesWithText(SIBLING_CLASS_NAME).fetchSemanticsNodes()).isNotEmpty()
    }
  }

  @Test fun `what only a weak reference points at is drawn as weakly reachable`() {
    explorerUiTest {
      // The weakly retained array is by far the biggest thing in this heap dump, so it covers most of
      // the treemap and the weak reference holding it is a thin border around it.
      openHeapDump(testFolder.weaklyReachablePayloadHeapDump())

      clickView(TREEMAP_X, TREEMAP_Y)

      waitUntilAtLeastOneExists(hasText(WEAK.reachabilityText), OPEN_TIMEOUT_MILLIS)
    }
  }

  @Test fun `uncollected garbage is a sibling of the GC roots`() {
    explorerUiTest {
      // The garbage is most of this heap dump, so its half of the tree is the big rectangle and the
      // reachable half the small one.
      openHeapDump(testFolder.uncollectedGarbageHeapDump())

      clickContainerEdge(yFraction = 0.5f)

      waitUntilAtLeastOneExists(
        hasText(HeapDominatorTreemap.UNREACHABLE_LABEL),
        OPEN_TIMEOUT_MILLIS
      )
      onNodeWithText(UNREACHABLE_EXPLANATION).assertIsDisplayed()
      strengthToggle(UNREACHABLE).assertTextContains(
        formatByteSize(GARBAGE_PAYLOAD_BYTE_SIZE),
        substring = true
      )
    }
  }

  @Test fun `nothing keeps uncollected garbage in memory`() {
    explorerUiTest {
      openHeapDump(testFolder.uncollectedGarbageHeapDump())

      clickView(TREEMAP_X, TREEMAP_Y)

      // No GC root reaches it, so there is no object to blame for it still being here: the chain starts at
      // that said in as many words, where every other chain names the kind of root it starts at.
      waitUntilAtLeastOneExists(hasText(UNREACHABLE.reachabilityText), OPEN_TIMEOUT_MILLIS)
      waitUntilAtLeastOneExists(hasText(UNCOLLECTED_GARBAGE_CHAIN), OPEN_TIMEOUT_MILLIS)
    }
  }

  @Test fun `every strength can be greyed out, and none of it changes what is drawn`() {
    explorerUiTest {
      openHeapDump()
      val wholeHeapDump = strengthLegend()

      // A checkbox is the colour scale and nothing else, so there is no strength it makes no sense to
      // press: greying the strong heap is how you find the little there is of everything else.
      ReachabilityStrength.values().forEach { strength ->
        strengthToggle(strength).assertIsOn().assertIsEnabled()
        strengthToggle(strength).performClick()
        strengthToggle(strength).assertIsOff()
      }

      assertThat(strengthLegend()).isEqualTo(wholeHeapDump)
    }
  }

  @Test fun `a cache holding an image is on none of the ways it is held`() {
    explorerUiTest {
      // Here the tile showing the image holds it too, so the cache is not the answer to what keeps it
      // around: the tile dominates the pixels, and both ways it holds them are the tile's own.
      openHeapDump(testFolder.coilCachedImageHeapDump(alsoShownByATile = true))

      clickView(TREEMAP_X, TREEMAP_Y)

      // The shorter way first, which is the pixels as what the tile's view draws.
      waitUntilAtLeastOneExists(hasText("1 of 2 $WAYS_FROM_HERE"), OPEN_TIMEOUT_MILLIS)
      onNodeWithText("com.example.View").assertIsDisplayed()

      onNodeWithText(NEXT_WAY).performClick()

      // And the longer one: the same tile, holding the same pixels through the result of the request that
      // loaded them. The cache points at the image as squarely as that result does, and is on neither way.
      waitUntilAtLeastOneExists(hasText("2 of 2 $WAYS_FROM_HERE"), OPEN_TIMEOUT_MILLIS)
      onNodeWithText("coil3.request.SuccessResult").assertIsDisplayed()
      onNodeWithText("coil3.BitmapImage").assertIsDisplayed()
      assertThat(onAllNodesWithText("InternalValue", substring = true).fetchSemanticsNodes()).isEmpty()
    }
  }

  @Test fun `a strength nothing is reachable at says so`() {
    explorerUiTest {
      openHeapDump()

      // Nothing in this heap dump is reachable only through a java.lang.ref.Reference, which reads as a
      // bug until something explains it.
      strengthToggle(PHANTOM).assertTextContains(formatByteSize(0L), substring = true)
      onNodeWithText(NOTHING_WEAKER).assertIsDisplayed()
      // The paragraph saying why that is normal waits for the pointer: it is above the map for as long as
      // the heap dump is open, and read once it is in the way of the thing it is about.
      onNodeWithText(NOTHING_WEAKER_HINT).assertDoesNotExist()
    }
  }

  @Test fun `the same tree can be drawn as rings`() {
    explorerUiTest {
      openHeapDump()
      shapeOption(ViewShape.TREEMAP).assertIsSelected()

      shapeOption(ViewShape.RADIAL).performClick()

      // Laying the rings out is another read of the heap dump, so the tree comes back a beat later.
      shapeOption(ViewShape.RADIAL).assertIsSelected()
      waitForTheTree(OPEN_TIMEOUT_MILLIS)
      // Just off the middle of the view, which is inside one of the rings: the rings are as wide as the
      // view's shorter side divided by how many of them the layout allows for, so a tree three levels deep
      // fills only the first three of them and the space past that belongs to no cell.
      clickView(RING_X, RING_Y)

      waitUntilAtLeastOneExists(hasText("Retained objects"), OPEN_TIMEOUT_MILLIS)
    }
  }

  @Test fun `the colour scheme can be switched`() {
    explorerUiTest {
      openHeapDump()
      schemeOption(CellColorScheme.DAISY).assertIsSelected()

      schemeOption(CellColorScheme.SLATE).performClick()

      schemeOption(CellColorScheme.SLATE).assertIsSelected()
      schemeOption(CellColorScheme.DAISY).assertIsNotSelected()
      onNodeWithContentDescription(VIEW_DESCRIPTION).assertIsDisplayed()
    }
  }

  @Test fun `every object of the heap dump can be listed`() {
    explorerUiTest {
      openHeapDump()

      screenButton(ExplorerScreen.OBJECTS_LABEL).performClick()

      // The view a treemap can't be: one line per object, whatever its size, with the retained size the
      // treemap draws its rectangle from.
      waitUntilAtLeastOneExists(hasText("java.lang.Object[] array"), OPEN_TIMEOUT_MILLIS)
      onNodeWithText("com.example.Holder instance").assertIsDisplayed()
      onNodeWithText("$PAYLOAD_LENGTH elements").assertIsDisplayed()
      // How much of the heap dump is being looked at, which is what says a search found little of it.
      onNodeWithText("objects match", substring = true).assertIsDisplayed()
    }
  }

  @Test fun `a few characters filter the list down to the class names holding them`() {
    explorerUiTest {
      openHeapDump()
      screenButton(ExplorerScreen.OBJECTS_LABEL).performClick()
      waitUntilAtLeastOneExists(hasText("java.lang.Object[] array"), OPEN_TIMEOUT_MILLIS)

      // Part of a name rather than all of it, which is how anyone types a class they half remember.
      searchBox().performTextInput("Hold")

      waitUntilExactlyOneExists(hasText("com.example.Holder instance"), OPEN_TIMEOUT_MILLIS)
      waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) {
        onAllNodesWithText("java.lang.Object[] array").fetchSemanticsNodes().isEmpty()
      }
    }
  }

  @Test fun `a class leads to the instances of it`() {
    explorerUiTest {
      openHeapDump()
      screenButton(ExplorerScreen.OBJECTS_LABEL).performClick()
      waitUntilAtLeastOneExists(hasText("java.lang.Object[] array"), OPEN_TIMEOUT_MILLIS)
      // The class object itself, which the list has a line of its own for.
      onNodeWithText("com.example.Holder class").performClick()
      waitUntilAtLeastOneExists(hasText(LIST_INSTANCES), OPEN_TIMEOUT_MILLIS)

      onNodeWithText(LIST_INSTANCES).performClick()

      // Back on the list, filtered to the instances of that one class. Exactly it: a class whose name
      // merely contains this one is another class, and its instances are not these. Waited for by the class
      // itself going, since listing the objects again is a read of the heap dump and the list on screen is
      // the one from before it until it comes back.
      waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) {
        onAllNodesWithText("com.example.Holder class").fetchSemanticsNodes().isEmpty()
      }
      onNodeWithText("com.example.Holder instance").assertIsDisplayed()
      onNode(hasText(EXACT_MATCH) and isToggleable()).assertIsOn()
      kindToggle(HeapObjectKind.INSTANCE).assertIsOn()
      kindToggle(HeapObjectKind.CLASS).assertIsOff()
    }
  }

  @Test fun `clicking a listed object shows it on the map and describes it`() {
    explorerUiTest {
      openHeapDump()
      screenButton(ExplorerScreen.OBJECTS_LABEL).performClick()
      waitUntilAtLeastOneExists(hasText("java.lang.Object[] array"), OPEN_TIMEOUT_MILLIS)

      onNodeWithText("java.lang.Object[] array").performClick()

      // The same place clicking its rectangle would have taken you: the map zoomed to it, and the panel
      // describing it.
      waitUntilAtLeastOneExists(hasText(hexObjectId(payloadObjectId)), OPEN_TIMEOUT_MILLIS)
      onNodeWithContentDescription(VIEW_DESCRIPTION).assertIsDisplayed()
      waitUntilZoomedIn()
    }
  }

  @Test fun `the panels describe whatever the map went to`() {
    explorerUiTest {
      openHeapDump()
      clickView(TREEMAP_X, TREEMAP_Y)
      waitUntilAtLeastOneExists(hasText("$PAYLOAD_LENGTH elements"), OPEN_TIMEOUT_MILLIS)

      // Every move takes the panels with it: a window showing one object and describing another is a window
      // that has to be read twice. The chain is a move like any other.
      onNodeWithText("com.example.Holder").performClick()

      waitUntilAtLeastOneExists(hasText("payload = Object[]"), OPEN_TIMEOUT_MILLIS)
      // The id is how anything outside this window is pointed at the same object: another heap analyzer,
      // a script, a colleague. Said twice, by the bar above the map and by the chain's own last step.
      assertThat(onAllNodesWithText(hexObjectId(holderObjectId)).fetchSemanticsNodes()).hasSize(2)
    }
  }

  @Test fun `the colour and shape controls belong to the view they control`() {
    explorerUiTest {
      openHeapDump()
      strengthToggle(STRONG).assertIsDisplayed()
      shapeOption(ViewShape.RADIAL).assertIsDisplayed()

      screenButton(ExplorerScreen.OBJECTS_LABEL).performClick()

      // A list of objects is coloured by nothing and shaped like a list, so controls for how the tree is
      // drawn have nothing to do there.
      waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) {
        onAllNodesWithText(ViewShape.RADIAL.displayName).fetchSemanticsNodes().isEmpty()
      }
      assertThat(onAllNodesWithText(STRONG.displayName, substring = true).fetchSemanticsNodes())
        .isEmpty()
    }
  }

  /**
   * One window's worth of app, showing [heapDumpFile] and taking a chosen heap dump the way the window
   * that has none does. Which window a chosen one lands in is `ExplorerWindowTest`'s.
   */
  private fun ComposeUiTest.setExplorerContent(
    heapDumpFile: File? = null,
    chooseHeapDumpFile: () -> File? = { null },
    // An `adb` that is connected to nothing, rather than the one on this machine: a test that shells out
    // has whatever devices happen to be plugged in to answer for.
    deviceHeapDumps: DeviceHeapDumps = DeviceHeapDumps(NO_DEVICE_ADB)
  ) = setContent {
    MaterialTheme {
      var shown: File? by remember { mutableStateOf(heapDumpFile) }
      ExplorerApp(
        heapDumpFile = shown,
        // No pixels to keep track of: nothing here takes a dump off a device, which is the only way
        // any come with one.
        onHeapDumpChosen = { file, _ -> shown = file },
        chooseHeapDumpFile = chooseHeapDumpFile,
        deviceHeapDumps = deviceHeapDumps
      )
    }
  }

  private fun ComposeUiTest.openHeapDump(heapDumpFile: File = testHeapDump()) {
    setExplorerContent(heapDumpFile)
    waitForTheTree(OPEN_TIMEOUT_MILLIS)
  }

  /**
   * Waits until the map has been laid out rooted somewhere other than the top of the tree, which is what
   * going to an object does.
   *
   * Read off the log, because nothing on screen says it: the view is one canvas, and the chain beside it
   * names the object gone to rather than the node the map settled on above it.
   */
  private fun ComposeUiTest.waitUntilZoomedIn() {
    waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) {
      logged.any { it.startsWith(TREEMAP_LAID_OUT) && WHOLE_HEAP_DUMP_NODE !in it }
    }
  }

  /** The row every chain hangs below, which leads to the whole heap dump as the screen bar's button does. */
  private fun ComposeUiTest.wholeHeapDumpRow(): SemanticsNodeInteraction = onNode(isWholeHeapDumpRow())

  /** A button on the row of screens an open heap dump can be read through. */
  private fun ComposeUiTest.screenButton(label: String): SemanticsNodeInteraction =
    onNode(hasText(label) and isButton())

  /**
   * What names the whole heap dump in the chain pane, which is a clickable line and not a button: the
   * screen bar has a button of the same name, so the text alone matches two nodes.
   */
  private fun isWholeHeapDumpRow(): SemanticsMatcher =
    hasText(HeapDominatorTreemap.ROOT_LABEL) and hasClickAction() and !isButton()

  private fun isButton(): SemanticsMatcher =
    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

  /** The search box of the object list, the one thing in the window that takes typing. */
  private fun ComposeUiTest.searchBox(): SemanticsNodeInteraction = onNode(hasSetTextAction())

  /** The checkbox that filters a list of objects down to one kind of them. */
  private fun ComposeUiTest.kindToggle(kind: HeapObjectKind): SemanticsNodeInteraction =
    onNode(hasText(kind.displayName) and isToggleable())

  /** The checkbox for [strength] above the view, which carries its name and how much it holds. */
  private fun ComposeUiTest.strengthToggle(strength: ReachabilityStrength): SemanticsNodeInteraction =
    onNode(hasText(strength.displayName, substring = true) and isToggleable())

  /** The radio button for [scheme] above the view. */
  private fun ComposeUiTest.schemeOption(scheme: CellColorScheme): SemanticsNodeInteraction =
    onNode(hasText(scheme.displayName) and isSelectable())

  /** The radio button for [shape] above the view. */
  private fun ComposeUiTest.shapeOption(shape: ViewShape): SemanticsNodeInteraction =
    onNode(hasText(shape.displayName) and isSelectable())

  /** How the legend above the view splits the heap dump up, which is what a colour scale must not change. */
  private fun ComposeUiTest.strengthLegend(): List<String> = ReachabilityStrength.values().map {
    strengthToggle(it).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("")
  }

  /**
   * Clicks the outline of the innermost container down the left edge of the treemap, [yFraction] of
   * the way down it.
   *
   * A rectangle's children cover every pixel of it, so its outline is the only part of a container
   * there is to click — see `TreemapLayout.cellAt`. Which container that is follows from the heap dump:
   * a squarified layout puts the largest rectangle of every level against the left edge, so clicking
   * there reaches the innermost of the nested containers that all but fill the view.
   */
  private fun ComposeUiTest.clickContainerEdge(yFraction: Float) {
    val view = viewBounds()
    clickAt(
      Offset(
        x = view.left + EDGE_PRESS_INSET,
        y = view.top + view.height * yFraction
      )
    )
  }

  /**
   * Clicks a point of the view, given as a fraction of it: the view is a canvas with no node per cell, so
   * a test drives it by coordinates. Of the view rather than of the window, because what sits above the
   * view is a row of buttons and a legend that wraps to as many lines as it takes.
   */
  private fun ComposeUiTest.clickView(
    xFraction: Float,
    yFraction: Float
  ) {
    val view = viewBounds()
    clickAt(Offset(x = view.left + view.width * xFraction, y = view.top + view.height * yFraction))
  }

  private fun ComposeUiTest.clickAt(offset: Offset) {
    onRoot().performMouseInput { click(offset) }
  }

  /** Moves the pointer onto a point of the view, given as a fraction of it, the way [clickView] clicks one. */
  private fun ComposeUiTest.hoverView(
    xFraction: Float,
    yFraction: Float
  ) {
    onRoot().performMouseInput { hover(pointerAt(xFraction, yFraction)) }
  }

  /** A point of the view given as a fraction of it, in the window's own coordinates. */
  private fun ComposeUiTest.pointerAt(
    xFraction: Float,
    yFraction: Float
  ): Offset {
    val view = viewBounds()
    return Offset(x = view.left + view.width * xFraction, y = view.top + view.height * yFraction)
  }

  /**
   * Where the card naming what the pointer is on ended up, measured by the object id it prints.
   *
   * The card itself is a surface with no semantics of its own, so this is a line inside it: enough to say
   * which way it moved and that the pointer isn't in it, since the card is at least as big as its text.
   */
  private fun ComposeUiTest.pointerCardBounds() =
    onNodeWithText(hexObjectId(payloadObjectId)).fetchSemanticsNode().boundsInRoot

  /**
   * Moves the pointer onto the band the root keeps across the top of the view for its own label, which its
   * children leave uncovered. A fraction of the view would be a fraction of however tall the window is.
   */
  private fun ComposeUiTest.hoverRootBand() {
    val view = viewBounds()
    onRoot().performMouseInput {
      hover(Offset(x = view.left + view.width / 2, y = view.top + LABEL_BAND_INSET))
    }
  }

  /** Moves the pointer off the view, onto the panes beside it, which is what leaves nothing hovered. */
  private fun ComposeUiTest.leaveView() {
    val view = viewBounds()
    onRoot().performMouseInput {
      moveTo(Offset(x = view.right + PANE_INSET, y = view.top + PANE_INSET))
    }
  }

  /** Where the tree is drawn, which is the one part of the window a click has to land in. */
  private fun ComposeUiTest.viewBounds() =
    onNodeWithContentDescription(VIEW_DESCRIPTION).fetchSemanticsNode().boundsInRoot

  /**
   * A heap dump where a single instance is the only path to a large object array, so that one
   * rectangle and the one nested in it cover almost the whole treemap and can be clicked blind.
   */
  private fun testHeapDump(): File {
    val file = testFolder.newFile("heap.hprof")
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
    /** Somewhere in the middle of the view, which is inside whatever it draws biggest. */
    private const val TREEMAP_X = 0.4f
    private const val TREEMAP_Y = 0.6f

    /** How far along the view the pointer moves to show the card following it, staying on one rectangle. */
    private const val POINTER_STEP = 0.2f

    /** Just off the middle of the view, which is in the first ring the rings are drawn out from. */
    private const val RING_X = 0.45f
    private const val RING_Y = 0.55f

    /** How far inside the left edge of the view a container's outline is pressed. Within EDGE_GRAB. */
    private const val EDGE_PRESS_INSET = 2f

    /** How far down the view the root's own label band is, which is where its children start. */
    private const val LABEL_BAND_INSET = 2f

    /** How far past the edge of the view the panes beside it start. */
    private const val PANE_INSET = 10f

    /**
     * Well inside the largest rectangle of the second level rather than in its label band, which is what
     * the rectangle standing for the siblings that didn't fit is. A fraction of the window, so it only has
     * to be somewhere in the top left block.
     */
    private const val LEFTOVER_X = 0.05f
    private const val LEFTOVER_Y = 0.45f

    /** Opening a heap dump and rebuilding a tree both happen on another thread. */
    private const val OPEN_TIMEOUT_MILLIS = 10_000L

    /** How the log says a treemap was laid out, and what it calls the node at the top of the tree. */
    private const val TREEMAP_LAID_OUT = "Read the treemap rooted at"
    private const val WHOLE_HEAP_DUMP_NODE = "the whole heap dump"

    /** What `adb devices` prints when nothing is plugged in, which is every command this test needs. */
    private val NO_DEVICE_ADB = Adb { AdbOutput(exitCode = 0, text = "List of devices attached\n") }

    private const val HOLDER_LABEL = "Holder"

    /** What a chain says it starts at when no GC root reaches the object. See `gcRootLabelOf`. */
    private const val UNCOLLECTED_GARBAGE_CHAIN = "Uncollected garbage"

    /** Big enough that the row of the object list naming it is the bitmap rather than its buffer. */
    private const val BITMAP_SIDE = 64
    private const val BITMAP_ROW = "android.graphics.Bitmap instance"
    private const val BITMAP_DUMP_MODEL = "Pixel 9"
    private const val BITMAP_DUMP_SDK_INT = 36

    /** What the dialog says the heap dump came from, off the `android.os.Build` written into it. */
    private const val DUMP_ORIGIN = "Google $BITMAP_DUMP_MODEL · API $BITMAP_DUMP_SDK_INT"
  }
}
