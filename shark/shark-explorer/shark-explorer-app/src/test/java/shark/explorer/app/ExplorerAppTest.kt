package shark.explorer.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
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
import androidx.compose.ui.test.performScrollTo
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
      onNodeWithText(objectIdText(payloadObjectId)).assertIsDisplayed()
      // And nowhere else: pointing at a rectangle is how you find out whether it's worth going to, so the
      // panel beside the map stays on the object clicked, which here is none.
      onNodeWithText(NO_SELECTION).assertIsDisplayed()
    }
  }

  @Test fun `the card saying what the pointer is on moves with it`() {
    explorerUiTest {
      openHeapDump()
      hoverView(TREEMAP_X, TREEMAP_Y)
      waitUntilAtLeastOneExists(hasText(objectIdText(payloadObjectId)), OPEN_TIMEOUT_MILLIS)
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

  @Test fun `the details panel is one side of the map and the chain holding an object the other`() {
    explorerUiTest {
      openHeapDump()

      // What was clicked and how it is held read as one answer around the map, rather than as the window's
      // two outer edges with everything else between them.
      val view = viewBounds()
      assertThat(onNodeWithText(NO_SELECTION).fetchSemanticsNode().boundsInRoot.right)
        .isLessThanOrEqualTo(view.left)
      assertThat(onNodeWithText(ROOT_PATH).fetchSemanticsNode().boundsInRoot.left)
        .isGreaterThanOrEqualTo(view.right)
    }
  }

  @Test fun `the chain floating over the map drops away when the pointer leaves it`() {
    explorerUiTest {
      openHeapDump()
      clickView(TREEMAP_X, TREEMAP_Y)
      waitUntilAtLeastOneExists(hasText(objectIdText(payloadObjectId)), OPEN_TIMEOUT_MILLIS)
      // The band the instance holding the array keeps across the top of the view for itself, which is the
      // one other cell there is to point at once the map has gone to the array.
      hoverRootBand()
      waitUntilAtLeastOneExists(hasText(objectIdText(holderObjectId)), OPEN_TIMEOUT_MILLIS)

      leaveView()

      // Back to the chain of the object clicked, and without reading the heap dump again: what was clicked
      // was never thrown away.
      waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) {
        onAllNodesWithText(objectIdText(holderObjectId)).fetchSemanticsNodes().isEmpty()
      }
      onNodeWithText(objectIdText(payloadObjectId)).assertIsDisplayed()
    }
  }

  @Test fun `the chain the pointer draws starts at the rectangle the map is showing`() {
    explorerUiTest {
      openHeapDump()

      hoverView(TREEMAP_X, TREEMAP_Y)

      // The array is held by the instance holding it, and that instance is one of the whole heap dump's own
      // rectangles: what holds *it* is above the map, so the chain starts there rather than at the GC root
      // reaching it. Which is the pointer's question — what is this, here — and not how the map got here.
      // Waited for by the chain rather than by the panel around it: the panel is up as soon as the pointer
      // lands, and what holds the rectangle takes a walk up the heap dump to work out.
      waitUntilAtLeastOneExists(hasText("$HOLDER_LABEL · ", substring = true), OPEN_TIMEOUT_MILLIS)
      onNodeWithText(POINTED_AT_PATH).assertIsDisplayed()
      onNodeWithText("GC root:", substring = true).assertDoesNotExist()
    }
  }

  @Test fun `the chain the pointer draws is condensed to what still fits beside the map`() {
    explorerUiTest {
      openHeapDump()

      hoverView(TREEMAP_X, TREEMAP_Y)

      waitUntilAtLeastOneExists(hasText("$HOLDER_LABEL · ", substring = true), OPEN_TIMEOUT_MILLIS)
      // A chain from a GC root down to a bitmap of a real app is a dozen objects, and four lines each is
      // taller than any window. So the package, the kind, which field holds the next object and the label
      // saying which steps own it are all left to the chain of the object clicked, one pane below.
      onNodeWithText("com.example.Holder instance").assertDoesNotExist()
      onNodeWithText(DOMINATES_TARGET).assertDoesNotExist()
      onNodeWithText("Holder.payload").assertDoesNotExist()
      // What each step holds stays, on the class name's own line: it's what the map is being read for.
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
    assertThat(logged).noneMatch { it.startsWith("Reading the paths holding") }
  }

  @Test fun `the details panel says which instance was clicked`() {
    explorerUiTest {
      openHeapDump()

      clickView(TREEMAP_X, TREEMAP_Y)

      // An object id is how you point something outside the app — a script, a bug report — at this one
      // instance rather than at its class, so the panel prints the one it's describing.
      waitUntilAtLeastOneExists(hasText(objectIdText(payloadObjectId)), OPEN_TIMEOUT_MILLIS)
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

  @Test fun `the details panel says what dominates what was clicked`() {
    explorerUiTest {
      openHeapDump()

      clickView(TREEMAP_X, TREEMAP_Y)

      // The holder is the only thing pointing at the array clicked, so it dominates it and points straight
      // at it: one line, and nothing in between for the paths below to spell out.
      waitUntilAtLeastOneExists(hasText(DOMINATOR), OPEN_TIMEOUT_MILLIS)
      onNodeWithText("$HOLDER_LABEL ·", substring = true).assertIsDisplayed()
      onNodeWithText(NO_PATHS).performScrollTo().assertIsDisplayed()
    }
  }

  @Test fun `the paths screen spells out every way what was clicked is held`() {
    explorerUiTest {
      // The array is held by a wrapper the cache holds and by the view the tile holds, and nothing holds
      // both: no object dominates it, which is the shape this screen was built for.
      openHeapDump(testFolder.cachedPayloadHeapDump())
      clickView(TREEMAP_X, TREEMAP_Y)
      waitUntilAtLeastOneExists(hasText(INDEPENDENT_PATHS), OPEN_TIMEOUT_MILLIS)

      showPathsButton().performScrollTo().performClick()

      // Two chains with nothing in common in between, each from the GC root it starts at down to the
      // array, drawn where the treemap was.
      waitUntilAtLeastOneExists(hasText("Path 1 of 2", substring = true), OPEN_TIMEOUT_MILLIS)
      // Each chain starts at the kind of GC root that reaches it, which is where a leak trace starts too.
      assertThat(onAllNodesWithText("GC root:", substring = true).fetchSemanticsNodes()).hasSize(2)
      onNodeWithText("Cache.entry").assertIsDisplayed()
      onNodeWithText("Wrapper.payload").assertIsDisplayed()
      onNodeWithText("Tile.view").assertIsDisplayed()
      onNodeWithText("View.drawable").assertIsDisplayed()
    }
  }

  @Test fun `a path names each object by its class, package and all`() {
    explorerUiTest {
      openHeapDump(testFolder.cachedPayloadHeapDump())
      clickView(TREEMAP_X, TREEMAP_Y)
      waitUntilAtLeastOneExists(hasText(INDEPENDENT_PATHS), OPEN_TIMEOUT_MILLIS)

      showPathsButton().performScrollTo().performClick()

      // A leak trace says what each object is and how much it holds, and so does this: the package is
      // there to be read past rather than left out, which is why it's drawn greyed.
      waitUntilAtLeastOneExists(hasText("com.example.Wrapper instance"), OPEN_TIMEOUT_MILLIS)
      onNodeWithText("com.example.View instance").assertIsDisplayed()
      // The array is where both paths end, so what each chain says about it is said twice: what it is,
      // and the size the treemap draws it at. Its element count a third time, in the panel beside them.
      mapOf(
        "java.lang.Object[] array" to 2,
        "Retaining ${formatByteSize(PAYLOAD_LENGTH * 4L)} in 1 object" to 2,
        "$PAYLOAD_LENGTH elements" to 3
      ).forEach { (line, count) ->
        assertThat(onAllNodesWithText(line).fetchSemanticsNodes()).describedAs(line).hasSize(count)
      }
    }
  }

  @Test fun `clicking a step of a path shows that object on the map`() {
    explorerUiTest {
      openHeapDump(testFolder.cachedPayloadHeapDump())
      showPaths()

      onNodeWithText("com.example.Wrapper instance").performClick()

      // Described in the panel, on the map where the tree draws it — which here is the top of it: the
      // wrapper is held from two places, so nothing owns it, and what it holds is held from two places
      // as well, so it owns nothing either. One of the whole heap dump's own rectangles, selected there
      // rather than opened into a view with nothing in it.
      waitUntilAtLeastOneExists(hasText("payload = Object[]"), OPEN_TIMEOUT_MILLIS)
      onNodeWithContentDescription(VIEW_DESCRIPTION).assertIsDisplayed()
    }
  }

  @Test fun `going back returns to the screen the move was made from`() {
    explorerUiTest {
      openHeapDump(testFolder.cachedPayloadHeapDump())
      showPaths()
      onNodeWithText("com.example.Wrapper instance").performClick()
      waitUntilAtLeastOneExists(hasText("payload = Object[]"), OPEN_TIMEOUT_MILLIS)

      // Zooming out isn't the way back from a jump sideways, let alone from one screen to another.
      onNodeWithText(BACK_ARROW).performClick()

      waitUntilAtLeastOneExists(hasText("Path 1 of 2", substring = true), OPEN_TIMEOUT_MILLIS)
      onNodeWithText(FORWARD_ARROW).performClick()
      waitUntilAtLeastOneExists(hasText("payload = Object[]"), OPEN_TIMEOUT_MILLIS)
    }
  }

  @Test fun `a starred object stays readable after moving on to another`() {
    explorerUiTest {
      openHeapDump()
      clickView(TREEMAP_X, TREEMAP_Y)
      waitUntilAtLeastOneExists(hasText("Retained objects"), OPEN_TIMEOUT_MILLIS)

      onNodeWithText(UNSTARRED_GLYPH).performClick()
      // Moving on to the object that dominates it, which is what leaves the panel describing something else.
      onNodeWithText("$HOLDER_LABEL ·", substring = true).performClick()
      waitUntilAtLeastOneExists(hasText("payload = Object[]"), OPEN_TIMEOUT_MILLIS)

      screenButton("$STARRED_GLYPH 1 starred").performClick()

      // Comparing two rectangles means looking at them one after the other, and a treemap has no room to
      // keep the first on screen. So the list keeps everything the panel had read about it.
      onNodeWithText("Starred objects", substring = true).assertIsDisplayed()
      onNodeWithText(objectIdText(payloadObjectId)).assertIsDisplayed()
      onNodeWithText("dominated by $HOLDER_LABEL", substring = true).assertIsDisplayed()
    }
  }

  @Test fun `clicking a field in the details panel inspects what it points at`() {
    explorerUiTest {
      openHeapDump()
      clickView(TREEMAP_X, TREEMAP_Y)
      // Up to the instance holding the array clicked, which is what dominates it.
      waitUntilAtLeastOneExists(hasText(DOMINATOR), OPEN_TIMEOUT_MILLIS)
      onNodeWithText("$HOLDER_LABEL ·", substring = true).performClick()
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
      onNodeWithText(objectIdText(payloadObjectId)).assertIsDisplayed()
    }
  }

  @Test fun `clicking an object of the chain beside the map goes back out to it`() {
    explorerUiTest {
      openHeapDump()
      clickView(TREEMAP_X, TREEMAP_Y)
      waitUntilZoomedIn()

      // Which is the only way back out: the objects the chain draws are the ones the map is nested in, so
      // clicking one of them is a zoom back out to it.
      onNodeWithText("com.example.Holder instance").performClick()

      waitUntilAtLeastOneExists(hasText("payload = Object[]"), OPEN_TIMEOUT_MILLIS)
      onNodeWithText(objectIdText(holderObjectId)).assertIsDisplayed()
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
      onNodeWithText(SIBLING_CLASS_NAME).assertIsDisplayed()
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
      onNodeWithText(SIBLING_CLASS_NAME).assertIsDisplayed()
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

      // No GC root reaches it, so there is no object to blame for it still being here: what stands in for a
      // dominator is the half of the tree it's drawn in, which is the garbage.
      waitUntilAtLeastOneExists(hasText(UNREACHABLE.reachabilityText), OPEN_TIMEOUT_MILLIS)
      onNodeWithText(DOMINATOR).performScrollTo().assertIsDisplayed()
      onNodeWithText("${HeapDominatorTreemap.UNREACHABLE_LABEL} ·", substring = true)
        .performScrollTo()
        .assertIsDisplayed()
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

  @Test fun `a cache holding an image is on none of the paths that hold it`() {
    explorerUiTest {
      // Here the tile showing the image holds it too, so the cache is not the answer to what keeps it
      // around: the tile dominates the pixels, and both ways it holds them are the tile's own.
      openHeapDump(testFolder.coilCachedImageHeapDump(alsoShownByATile = true))

      showPaths()

      waitUntilAtLeastOneExists(hasText("Path 1 of 2", substring = true), OPEN_TIMEOUT_MILLIS)
      onNodeWithText("Tile.view").assertIsDisplayed()
      onNodeWithText("View.drawable").assertIsDisplayed()
      onNodeWithText("BitmapImage.bitmap").assertIsDisplayed()
      // The cache points at the image as squarely as the request result does, and holds nothing.
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
      // merely contains this one is another class, and its instances are not these.
      waitUntilAtLeastOneExists(hasText("com.example.Holder instance"), OPEN_TIMEOUT_MILLIS)
      onNode(hasText(EXACT_MATCH) and isToggleable()).assertIsOn()
      kindToggle(HeapObjectKind.INSTANCE).assertIsOn()
      kindToggle(HeapObjectKind.CLASS).assertIsOff()
      assertThat(onAllNodesWithText("com.example.Holder class").fetchSemanticsNodes()).isEmpty()
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
      waitUntilAtLeastOneExists(hasText(objectIdText(payloadObjectId)), OPEN_TIMEOUT_MILLIS)
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
      onNodeWithText("com.example.Holder instance").performClick()

      waitUntilAtLeastOneExists(hasText("payload = Object[]"), OPEN_TIMEOUT_MILLIS)
      // The id is how anything outside this window is pointed at the same object: another heap analyzer,
      // a script, a colleague.
      onNodeWithText(hexObjectId(holderObjectId), substring = true).assertIsDisplayed()
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

  /** A button on the row of screens an open heap dump can be read through. */
  private fun ComposeUiTest.screenButton(label: String): SemanticsNodeInteraction =
    onNode(hasText(label) and hasClickAction())

  /** The panel's way to the paths, which says how many of them there are. */
  private fun ComposeUiTest.showPathsButton(): SemanticsNodeInteraction =
    onNode(hasText(" paths", substring = true) and hasClickAction())

  /** Clicks the biggest rectangle of the treemap, then goes to the paths that hold it. */
  private fun ComposeUiTest.showPaths() {
    clickView(TREEMAP_X, TREEMAP_Y)
    waitUntilAtLeastOneExists(hasText(INDEPENDENT_PATHS), OPEN_TIMEOUT_MILLIS)
    showPathsButton().performScrollTo().performClick()
    waitUntilAtLeastOneExists(hasText("Path 1 of 2", substring = true), OPEN_TIMEOUT_MILLIS)
  }

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
    onNodeWithText(objectIdText(payloadObjectId)).fetchSemanticsNode().boundsInRoot

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

    /** Big enough that the row of the object list naming it is the bitmap rather than its buffer. */
    private const val BITMAP_SIDE = 64
    private const val BITMAP_ROW = "android.graphics.Bitmap instance"
    private const val BITMAP_DUMP_MODEL = "Pixel 9"
    private const val BITMAP_DUMP_SDK_INT = 36

    /** What the dialog says the heap dump came from, off the `android.os.Build` written into it. */
    private const val DUMP_ORIGIN = "Google $BITMAP_DUMP_MODEL · API $BITMAP_DUMP_SDK_INT"
  }
}
