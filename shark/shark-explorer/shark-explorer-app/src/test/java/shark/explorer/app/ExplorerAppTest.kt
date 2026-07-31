package shark.explorer.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnySibling
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
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilExactlyOneExists
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.GcRoot.JniGlobal
import shark.SharkLog
import shark.ValueHolder.ReferenceHolder
import shark.dump
import shark.explorer.ExplorerScreen
import shark.explorer.HeapDominatorTreemap
import shark.explorer.HeapObjectKind
import shark.explorer.ReachabilityStrength
import shark.explorer.ReachabilityStrength.PHANTOM
import shark.explorer.ReachabilityStrength.STRONG
import shark.explorer.ReachabilityStrength.UNREACHABLE
import shark.explorer.ReachabilityStrength.WEAK
import shark.explorer.formatByteSize

@OptIn(ExperimentalTestApi::class)
class ExplorerAppTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  /** The object id of the array in [testHeapDump], recorded as the dump is written. */
  private var payloadObjectId = 0L

  /**
   * Everything Shark logged during this test, a line per log with the throwable it came with appended.
   *
   * Recorded for every test rather than only for the ones asserting on it, so that a log line built from
   * the wrong state — an index into a path that has been shortened, say — fails the test that reaches it.
   * The window's thread and the heap dump's both log, hence the concurrent list.
   */
  private val logged = CopyOnWriteArrayList<String>()

  private var previousLogger: SharkLog.Logger? = null

  @Before fun recordWhatIsLogged() {
    previousLogger = SharkLog.logger
    SharkLog.logger = object : SharkLog.Logger {
      override fun d(message: String) {
        logged += message
      }

      override fun d(
        throwable: Throwable,
        message: String
      ) {
        logged += "$message: $throwable"
      }
    }
  }

  @After fun stopRecordingWhatIsLogged() {
    SharkLog.logger = previousLogger
  }

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

  /**
   * A window reporting a failure and a log saying nothing about it is a report nobody can answer, so what
   * the window says has to be what the log says too. Where the log goes: [shark.explorer.SessionLog].
   */
  @Test fun `a file that is not a heap dump is logged with what went wrong`() {
    runComposeUiTest {
      val notAHeapDump = testFolder.newFile("not-a-heap-dump.txt").apply { writeText("nope") }
      setContent { MaterialTheme { ExplorerApp(initialHeapDumpFile = notAHeapDump) } }

      waitUntilAtLeastOneExists(hasText("could not be opened", substring = true), OPEN_TIMEOUT_MILLIS)
    }

    assertThat(logged).anyMatch { "Could not open" in it && "not-a-heap-dump.txt" in it }
  }

  /**
   * What makes a session readable after it: which dump was opened, which step of opening it was running,
   * and every read of it once it's open. See [HeapDumpSession.read].
   */
  @Test fun `opening a heap dump and reading it are logged`() {
    runComposeUiTest { openHeapDump() }

    assertThat(logged).anyMatch { it.startsWith("Opening heap dump") }
    assertThat(logged).anyMatch { it.startsWith("Indexing") }
    assertThat(logged).anyMatch { it.startsWith("Opened") }
    assertThat(logged).anyMatch { it.startsWith("Read the sizes of") }
    assertThat(logged).anyMatch { it.startsWith("Read the treemap rooted at") }
  }

  @Test fun `the whole heap dump is accounted for at the top`() {
    runComposeUiTest {
      openHeapDump(weaklyReachablePayloadHeapDump())

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

  @Test fun `pressing a rectangle fills the details panel`() {
    runComposeUiTest {
      openHeapDump()

      pressView(TREEMAP_X, TREEMAP_Y)

      waitUntilAtLeastOneExists(hasText("Retained objects"), OPEN_TIMEOUT_MILLIS)
      onNodeWithText(NO_SELECTION).assertDoesNotExist()
      onNodeWithText(STRONG.reachabilityText).assertIsDisplayed()
    }
  }

  @Test fun `the details panel says which instance was pressed`() {
    runComposeUiTest {
      openHeapDump()

      pressView(TREEMAP_X, TREEMAP_Y)

      // An object id is how you point something outside the app — a script, a bug report — at this one
      // instance rather than at its class, so the panel prints the one it's describing.
      waitUntilAtLeastOneExists(hasText(objectIdText(payloadObjectId)), OPEN_TIMEOUT_MILLIS)
    }
  }

  @Test fun `the details panel lists the fields of what was pressed`() {
    runComposeUiTest {
      openHeapDump()

      pressView(TREEMAP_X, TREEMAP_Y)

      // The rectangle clicked is the payload array nested in the instance holding it, so its fields
      // are its elements, all null in this heap dump.
      waitUntilAtLeastOneExists(hasText("[0] = null"), OPEN_TIMEOUT_MILLIS)
      onNodeWithText("$PAYLOAD_LENGTH elements").assertIsDisplayed()
    }
  }

  @Test fun `the details panel says what dominates what was pressed`() {
    runComposeUiTest {
      openHeapDump()

      pressView(TREEMAP_X, TREEMAP_Y)

      // The holder is the only thing pointing at the array clicked, so it dominates it and points straight
      // at it: one line, and nothing in between for the paths below to spell out.
      waitUntilAtLeastOneExists(hasText(DOMINATOR), OPEN_TIMEOUT_MILLIS)
      onNodeWithText("$HOLDER_LABEL ·", substring = true).assertIsDisplayed()
      onNodeWithText(NO_PATHS).performScrollTo().assertIsDisplayed()
    }
  }

  @Test fun `the paths screen spells out every way what was pressed is held`() {
    runComposeUiTest {
      // The array is held by a wrapper the cache holds and by the view the tile holds, and nothing holds
      // both: no object dominates it, which is the shape this screen was built for.
      openHeapDump(cachedPayloadHeapDump())
      pressView(TREEMAP_X, TREEMAP_Y)
      waitUntilAtLeastOneExists(hasText(INDEPENDENT_PATHS), OPEN_TIMEOUT_MILLIS)

      showPathsButton().performScrollTo().performClick()

      // Two chains with nothing in common in between, each from the GC root it starts at down to the
      // array, drawn where the treemap was and named in the breadcrumbs.
      waitUntilAtLeastOneExists(hasText("Path 1 of 2", substring = true), OPEN_TIMEOUT_MILLIS)
      currentCrumb(ExplorerScreen.PATHS_CRUMB).assertIsDisplayed()
      // Each chain starts at the kind of GC root that reaches it, which is where a leak trace starts too.
      assertThat(onAllNodesWithText("GC root:", substring = true).fetchSemanticsNodes()).hasSize(2)
      onNodeWithText("Cache.entry").assertIsDisplayed()
      onNodeWithText("Wrapper.payload").assertIsDisplayed()
      onNodeWithText("Tile.view").assertIsDisplayed()
      onNodeWithText("View.drawable").assertIsDisplayed()
    }
  }

  @Test fun `a path names each object by its class, package and all`() {
    runComposeUiTest {
      openHeapDump(cachedPayloadHeapDump())
      pressView(TREEMAP_X, TREEMAP_Y)
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
    runComposeUiTest {
      openHeapDump(cachedPayloadHeapDump())
      showPaths()

      onNodeWithText("com.example.Wrapper instance").performClick()

      // Described in the panel, and the treemap zoomed to where it's drawn, which is a step down from the
      // root: the wrapper is held from two places, so nothing owns it either.
      waitUntilAtLeastOneExists(hasText("payload = Object[]"), OPEN_TIMEOUT_MILLIS)
      onNodeWithContentDescription(VIEW_DESCRIPTION).assertIsDisplayed()
      waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) { breadcrumbCount() > 1 }
    }
  }

  @Test fun `going back returns to the screen the move was made from`() {
    runComposeUiTest {
      openHeapDump(cachedPayloadHeapDump())
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
    runComposeUiTest {
      openHeapDump()
      pressView(TREEMAP_X, TREEMAP_Y)
      waitUntilAtLeastOneExists(hasText("Retained objects"), OPEN_TIMEOUT_MILLIS)

      onNodeWithText(UNSTARRED_GLYPH).performClick()
      // Moving on to the object that dominates it, which is what leaves the panel describing something else.
      onNodeWithText("$HOLDER_LABEL ·", substring = true).performClick()
      waitUntilAtLeastOneExists(hasText("payload = Object[]"), OPEN_TIMEOUT_MILLIS)

      screenButton("$STARRED_GLYPH 1 starred").performClick()

      // Comparing two rectangles means looking at them one after the other, and a treemap has no room to
      // keep the first on screen. So the list keeps everything the panel had read about it.
      currentCrumb(ExplorerScreen.STARRED_CRUMB).assertIsDisplayed()
      onNodeWithText("Starred objects", substring = true).assertIsDisplayed()
      onNodeWithText(objectIdText(payloadObjectId)).assertIsDisplayed()
      onNodeWithText("dominated by $HOLDER_LABEL", substring = true).assertIsDisplayed()
    }
  }

  @Test fun `clicking a field in the details panel inspects what it points at`() {
    runComposeUiTest {
      openHeapDump()
      pressView(TREEMAP_X, TREEMAP_Y)
      // Up to the instance holding the array clicked, which is what dominates it.
      waitUntilAtLeastOneExists(hasText(DOMINATOR), OPEN_TIMEOUT_MILLIS)
      onNodeWithText("$HOLDER_LABEL ·", substring = true).performClick()
      waitUntilAtLeastOneExists(hasText("payload = Object[]"), OPEN_TIMEOUT_MILLIS)

      // And back down through the field, without touching the treemap.
      onNodeWithText("payload = Object[]").performClick()

      waitUntilAtLeastOneExists(hasText("$PAYLOAD_LENGTH elements"), OPEN_TIMEOUT_MILLIS)
    }
  }

  @Test fun `double clicking a rectangle adds a breadcrumb for every dominator down to it`() {
    runComposeUiTest {
      openHeapDump()
      assertThat(breadcrumbCount()).isEqualTo(1)

      pressView(TREEMAP_X, TREEMAP_Y, isDoubleClick = true)

      // The point clicked is inside the payload array, which is inside the instance holding it, which
      // is inside the reachable half of the dump: a crumb for each rather than a jump from the root
      // straight to the array.
      waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) { breadcrumbCount() == 4 }
      // A crumb says what the object retains as well as what it is, which is what tells it apart from
      // the details panel naming the same object.
      crumb("$HOLDER_LABEL ·").assertIsDisplayed()
    }
  }

  @Test fun `clicking a breadcrumb zooms back out`() {
    runComposeUiTest {
      openHeapDump()
      pressView(TREEMAP_X, TREEMAP_Y, isDoubleClick = true)
      waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) { breadcrumbCount() > 1 }

      onNodeWithText(HeapDominatorTreemap.ROOT_LABEL, substring = true).performClick()

      waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) { breadcrumbCount() == 1 }
    }
  }

  @Test fun `pressing the rectangle standing for the siblings that did not fit says what it stands for`() {
    runComposeUiTest {
      // Every sibling weighs the same, so the rectangle standing for the ones left out weighs as much
      // as all of them together: it's the largest, and a squarified treemap puts that one in the top
      // left corner.
      openHeapDump(manySiblingsHeapDump())

      pressView(LEFTOVER_X, LEFTOVER_Y)

      waitUntilAtLeastOneExists(hasText("smaller objects", substring = true), OPEN_TIMEOUT_MILLIS)
      onNodeWithText("Held by Object[]", substring = true).assertIsDisplayed()
    }
  }

  @Test fun `pressing a class group says how many objects of that class it stands for`() {
    runComposeUiTest {
      // Every instance is a GC root of its own, so all 400 land directly under the root: far more than
      // a view can draw one by one, which is what gathers them by class.
      openHeapDump(crowdedRootHeapDump())

      pressContainerEdge(yFraction = 0.5f)

      waitUntilAtLeastOneExists(hasText("of one class", substring = true), OPEN_TIMEOUT_MILLIS)
      onNodeWithText(SIBLING_CLASS_NAME).assertIsDisplayed()
      // Says it in words as well as with the label, the slate fill and the dashed outline.
      onNodeWithText(CLASS_GROUP_EXPLANATION).assertIsDisplayed()
      onNodeWithText("Retained together").assertIsDisplayed()
    }
  }

  @Test fun `zooming into a class group leaves a breadcrumb naming the class`() {
    runComposeUiTest {
      openHeapDump(crowdedRootHeapDump())

      pressContainerEdge(yFraction = 0.5f, isDoubleClick = true)

      waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) { breadcrumbCount() == 3 }
      onNodeWithText(
        "$SIBLING_COUNT ${HeapDominatorTreemap.CLASS_GROUP_LABEL_SEPARATOR} Sibling",
        substring = true
      ).assertIsDisplayed()
    }
  }

  @Test fun `what only a weak reference points at is drawn as weakly reachable`() {
    runComposeUiTest {
      // The weakly retained array is by far the biggest thing in this heap dump, so it covers most of
      // the treemap and the weak reference holding it is a thin border around it.
      openHeapDump(weaklyReachablePayloadHeapDump())

      pressView(TREEMAP_X, TREEMAP_Y)

      waitUntilAtLeastOneExists(hasText(WEAK.reachabilityText), OPEN_TIMEOUT_MILLIS)
    }
  }

  @Test fun `uncollected garbage is a sibling of the GC roots`() {
    runComposeUiTest {
      // The garbage is most of this heap dump, so its half of the tree is the big rectangle and the
      // reachable half the small one.
      openHeapDump(uncollectedGarbageHeapDump())

      pressContainerEdge(yFraction = 0.5f)

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
    runComposeUiTest {
      openHeapDump(uncollectedGarbageHeapDump())

      pressView(TREEMAP_X, TREEMAP_Y)

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
    runComposeUiTest {
      openHeapDump()
      val wholeHeapDump = rootCrumb()

      // A checkbox is the colour scale and nothing else, so there is no strength it makes no sense to
      // press: greying the strong heap is how you find the little there is of everything else.
      ReachabilityStrength.values().forEach { strength ->
        strengthToggle(strength).assertIsOn().assertIsEnabled()
        strengthToggle(strength).performClick()
        strengthToggle(strength).assertIsOff()
      }

      assertThat(rootCrumb()).isEqualTo(wholeHeapDump)
    }
  }

  @Test fun `a cache holding an image is on none of the paths that hold it`() {
    runComposeUiTest {
      // Here the tile showing the image holds it too, so the cache is not the answer to what keeps it
      // around: the tile dominates the pixels, and both ways it holds them are the tile's own.
      openHeapDump(coilCachedImageHeapDump(alsoShownByATile = true))

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
    runComposeUiTest {
      openHeapDump()

      // Nothing in this heap dump is reachable only through a java.lang.ref.Reference, which reads as a
      // bug until something explains it.
      strengthToggle(PHANTOM).assertTextContains(formatByteSize(0L), substring = true)
      onNodeWithText(NOTHING_WEAKER).assertIsDisplayed()
    }
  }

  @Test fun `the same tree can be drawn as rings`() {
    runComposeUiTest {
      openHeapDump()
      shapeOption(ViewShape.TREEMAP).assertIsSelected()

      shapeOption(ViewShape.RADIAL).performClick()

      // Laying the rings out is another read of the heap dump, so the tree comes back a beat later.
      shapeOption(ViewShape.RADIAL).assertIsSelected()
      waitUntilAtLeastOneExists(
        hasText(HeapDominatorTreemap.ROOT_LABEL, substring = true),
        OPEN_TIMEOUT_MILLIS
      )
      // Anywhere near the middle of the view is inside one of the rings.
      pressView(TREEMAP_X, TREEMAP_Y)

      waitUntilAtLeastOneExists(hasText("Retained objects"), OPEN_TIMEOUT_MILLIS)
    }
  }

  @Test fun `the colour scheme can be switched`() {
    runComposeUiTest {
      openHeapDump()
      schemeOption(CellColorScheme.DAISY).assertIsSelected()

      schemeOption(CellColorScheme.SLATE).performClick()

      schemeOption(CellColorScheme.SLATE).assertIsSelected()
      schemeOption(CellColorScheme.DAISY).assertIsNotSelected()
      onNodeWithText(HeapDominatorTreemap.ROOT_LABEL, substring = true).assertIsDisplayed()
    }
  }

  @Test fun `every object of the heap dump can be listed`() {
    runComposeUiTest {
      openHeapDump()

      screenButton(ExplorerScreen.OBJECTS_CRUMB).performClick()

      // The view a treemap can't be: one line per object, whatever its size, with the retained size the
      // treemap draws its rectangle from.
      waitUntilAtLeastOneExists(hasText("java.lang.Object[] array"), OPEN_TIMEOUT_MILLIS)
      currentCrumb(ExplorerScreen.OBJECTS_CRUMB).assertIsDisplayed()
      onNodeWithText("com.example.Holder instance").assertIsDisplayed()
      onNodeWithText("$PAYLOAD_LENGTH elements").assertIsDisplayed()
      // How much of the heap dump is being looked at, which is what says a search found little of it.
      onNodeWithText("objects match", substring = true).assertIsDisplayed()
    }
  }

  @Test fun `a few characters filter the list down to the class names holding them`() {
    runComposeUiTest {
      openHeapDump()
      screenButton(ExplorerScreen.OBJECTS_CRUMB).performClick()
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
    runComposeUiTest {
      openHeapDump()
      screenButton(ExplorerScreen.OBJECTS_CRUMB).performClick()
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
    runComposeUiTest {
      openHeapDump()
      screenButton(ExplorerScreen.OBJECTS_CRUMB).performClick()
      waitUntilAtLeastOneExists(hasText("java.lang.Object[] array"), OPEN_TIMEOUT_MILLIS)

      onNodeWithText("java.lang.Object[] array").performClick()

      // The same place clicking its rectangle would have taken you: the map zoomed to it, and the panel
      // describing it.
      waitUntilAtLeastOneExists(hasText(objectIdText(payloadObjectId)), OPEN_TIMEOUT_MILLIS)
      onNodeWithContentDescription(VIEW_DESCRIPTION).assertIsDisplayed()
      // Drawing an object that dominates nothing would be an empty view, so the map lands on what holds
      // it, with it selected inside: the last crumb is the holder and the panel is the array.
      currentCrumb("$HOLDER_LABEL ·", substring = true).assertIsDisplayed()
    }
  }

  @Test fun `the panel describes whatever the breadcrumbs name`() {
    runComposeUiTest {
      openHeapDump()
      pressView(TREEMAP_X, TREEMAP_Y, isDoubleClick = true)
      waitUntilAtLeastOneExists(hasText("$PAYLOAD_LENGTH elements"), OPEN_TIMEOUT_MILLIS)

      // A crumb moves the map, so it has to move the panel with it: a window saying one thing at the top
      // and another on the right is a window that has to be read twice.
      crumb("$HOLDER_LABEL ·").performClick()

      waitUntilAtLeastOneExists(hasText("payload = Object[]"), OPEN_TIMEOUT_MILLIS)
    }
  }

  @Test fun `a breadcrumb says which object it is, not only which class`() {
    runComposeUiTest {
      openHeapDump()

      pressView(TREEMAP_X, TREEMAP_Y, isDoubleClick = true)

      // The id is how anything outside this window is pointed at the same object: another heap analyzer,
      // a script, a colleague.
      waitUntilAtLeastOneExists(
        hasText(hexObjectId(payloadObjectId), substring = true),
        OPEN_TIMEOUT_MILLIS
      )
    }
  }

  @Test fun `the colour and shape controls belong to the view they control`() {
    runComposeUiTest {
      openHeapDump()
      strengthToggle(STRONG).assertIsDisplayed()
      shapeOption(ViewShape.RADIAL).assertIsDisplayed()

      screenButton(ExplorerScreen.OBJECTS_CRUMB).performClick()

      // A list of objects is coloured by nothing and shaped like a list, so controls for how the tree is
      // drawn have nothing to do there.
      waitUntil(timeoutMillis = OPEN_TIMEOUT_MILLIS) {
        onAllNodesWithText(ViewShape.RADIAL.displayName).fetchSemanticsNodes().isEmpty()
      }
      assertThat(onAllNodesWithText(STRONG.displayName, substring = true).fetchSemanticsNodes())
        .isEmpty()
    }
  }

  private fun ComposeUiTest.openHeapDump(heapDumpFile: File = testHeapDump()) {
    setContent { MaterialTheme { ExplorerApp(initialHeapDumpFile = heapDumpFile) } }
    waitUntilAtLeastOneExists(
      hasText(HeapDominatorTreemap.ROOT_LABEL, substring = true),
      OPEN_TIMEOUT_MILLIS
    )
  }

  /**
   * A button on the row of screens an open heap dump can be read through, rather than the breadcrumb of
   * the same name once it has been pressed: a crumb for where the explorer already is leads nowhere.
   */
  private fun ComposeUiTest.screenButton(label: String): SemanticsNodeInteraction =
    onNode(hasText(label) and hasClickAction())

  /**
   * The last breadcrumb, which is where the explorer is and therefore isn't a button. Told apart from a
   * panel line naming the same screen by the chevron beside it.
   */
  private fun ComposeUiTest.currentCrumb(
    label: String,
    substring: Boolean = false
  ): SemanticsNodeInteraction =
    onNode(hasText(label, substring = substring) and hasAnySibling(hasText(BREADCRUMB_SEPARATOR)))

  /** The panel's way to the paths, which says how many of them there are. */
  private fun ComposeUiTest.showPathsButton(): SemanticsNodeInteraction =
    onNode(hasText(" paths", substring = true) and hasClickAction())

  /** Presses the biggest rectangle of the treemap, then goes to the paths that hold it. */
  private fun ComposeUiTest.showPaths() {
    pressView(TREEMAP_X, TREEMAP_Y)
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

  /** Crumbs are separated by a chevron, so there's one more crumb than there are chevrons. */
  private fun ComposeUiTest.breadcrumbCount(): Int =
    onAllNodesWithText(BREADCRUMB_SEPARATOR).fetchSemanticsNodes().size + 1

  /**
   * A breadcrumb, rather than a panel line that names the same object the same way: a crumb is a button.
   */
  private fun ComposeUiTest.crumb(label: String): SemanticsNodeInteraction = onNode(
    hasText(label, substring = true) and
      SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
  )

  /**
   * Presses the outline of the innermost container down the left edge of the treemap, [yFraction] of
   * the way down it.
   *
   * A rectangle's children cover every pixel of it, so its outline is the only part of a container
   * there is to press — see `TreemapLayout.cellAt`. Which container that is follows from the heap dump:
   * a squarified layout puts the largest rectangle of every level against the left edge, so pressing
   * there reaches the innermost of the nested containers that all but fill the view.
   */
  private fun ComposeUiTest.pressContainerEdge(
    yFraction: Float,
    isDoubleClick: Boolean = false
  ) {
    val view = viewBounds()
    press(
      Offset(
        x = view.left + EDGE_PRESS_INSET,
        y = view.top + view.height * yFraction
      ),
      isDoubleClick
    )
  }

  /**
   * Presses a point of the view, given as a fraction of it: the view is a canvas with no node per cell, so
   * a test drives it by coordinates. Of the view rather than of the window, because what sits above the
   * view is a row of buttons and a legend that wraps to as many lines as it takes.
   */
  private fun ComposeUiTest.pressView(
    xFraction: Float,
    yFraction: Float,
    isDoubleClick: Boolean = false
  ) {
    val view = viewBounds()
    press(
      Offset(x = view.left + view.width * xFraction, y = view.top + view.height * yFraction),
      isDoubleClick
    )
  }

  private fun ComposeUiTest.press(
    offset: Offset,
    isDoubleClick: Boolean
  ) {
    onRoot().performMouseInput { if (isDoubleClick) doubleClick(offset) else click(offset) }
  }

  /** Where the tree is drawn, which is the one part of the window a click has to land in. */
  private fun ComposeUiTest.viewBounds() =
    onNodeWithContentDescription(VIEW_DESCRIPTION).fetchSemanticsNode().boundsInRoot

  /** The first breadcrumb, which names the root of the tree and says how much it holds. */
  private fun ComposeUiTest.rootCrumb(): String =
    onNodeWithText(HeapDominatorTreemap.ROOT_LABEL, substring = true)
      .fetchSemanticsNode()
      .config[SemanticsProperties.Text]
      .joinToString("")

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
      gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
    }
    return file
  }

  /**
   * The same, plus a much larger array that only a `WeakReference` points at, so that the weakly
   * reachable part of the tree is what a blind press in the middle of the treemap lands on.
   */
  private fun weaklyReachablePayloadHeapDump(): File {
    val file = testFolder.newFile(WEAKLY_REACHABLE_DUMP_NAME)
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

  /**
   * A heap dump most of which is garbage: a large array nothing points at and no GC root reaches, which
   * a collection would have taken had one run before the dump was written.
   */
  private fun uncollectedGarbageHeapDump(): File {
    val file = testFolder.newFile("uncollected-garbage.hprof")
    file.dump {
      objectArray(arrayClass("java.lang.Object"), LongArray(GARBAGE_PAYLOAD_LENGTH))
      val holder = "com.example.Holder" instance {
        field["payload"] =
          ReferenceHolder(objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_LENGTH)))
      }
      gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
    }
    return file
  }

  /**
   * A heap dump shaped like the one [ReachabilityStrength.CACHE] came from, built of the real class and
   * field names Coil's memory cache is made of, because that is what the explorer matches on. With
   * [alsoShownByATile] a tile showing the image holds it too, so the cache isn't what keeps it around.
   */
  private fun coilCachedImageHeapDump(alsoShownByATile: Boolean): File {
    val file = testFolder.newFile("coil-cached-image-$alsoShownByATile.hprof")
    file.dump {
      val pixels =
        ReferenceHolder(objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_LENGTH)))
      val image = "coil3.BitmapImage" instance { field["bitmap"] = pixels }
      val cacheEntry =
        "coil3.memory.RealStrongMemoryCache\$InternalValue" instance { field["image"] = image }
      val cache = "coil3.memory.RealStrongMemoryCache" instance { field["cache"] = cacheEntry }
      gcRoot(JniGlobal(id = cache.value, jniGlobalRefId = 0))
      if (alsoShownByATile) {
        val tile = "com.example.Tile" instance {
          field["view"] = "com.example.View" instance { field["drawable"] = pixels }
          field["result"] = "coil3.request.SuccessResult" instance { field["image"] = image }
        }
        gcRoot(JniGlobal(id = tile.value, jniGlobalRefId = 1))
      }
    }
    return file
  }

  /**
   * A heap dump shaped like the one the paths section was built for: a cache and the view showing an
   * image both hold it, and the view holds it twice, so the paths that hold it meet only at the root.
   */
  private fun cachedPayloadHeapDump(): File {
    val file = testFolder.newFile("cached-payload.hprof")
    file.dump {
      val payload =
        ReferenceHolder(objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_LENGTH)))
      val wrapper = "com.example.Wrapper" instance { field["payload"] = payload }
      val view = "com.example.View" instance { field["drawable"] = payload }
      val tile = "com.example.Tile" instance {
        field["result"] = wrapper
        field["view"] = view
      }
      val cache = "com.example.Cache" instance { field["entry"] = wrapper }
      gcRoot(JniGlobal(id = tile.value, jniGlobalRefId = 0))
      gcRoot(JniGlobal(id = cache.value, jniGlobalRefId = 1))
    }
    return file
  }

  /**
   * A heap dump with more equally sized rooted instances than one node draws one by one, so that the
   * treemap has a rectangle standing for the ones it left out.
   */
  private fun manySiblingsHeapDump(): File {
    val file = testFolder.newFile("many-siblings.hprof")
    file.dump {
      val siblingClassId = clazz(
        className = "com.example.Sibling",
        fields = listOf("payload" to ReferenceHolder::class)
      )
      val objectArrayClassId = arrayClass("java.lang.Object")
      val siblingIds = LongArray(SIBLING_COUNT) { _ ->
        val payload = objectArray(objectArrayClassId, LongArray(SIBLING_PAYLOAD_LENGTH))
        instance(siblingClassId, fields = listOf(ReferenceHolder(payload))).value
      }
      // Held by an array rather than each being a GC root: the root's children are gathered by class,
      // so the crowd a leftover cell stands for has to sit under a node that isn't the root.
      val siblings = objectArray(objectArrayClassId, siblingIds)
      gcRoot(JniGlobal(id = siblings, jniGlobalRefId = 0))
    }
    return file
  }

  /** A heap dump with more objects of one class directly under the root than a view can draw. */
  private fun crowdedRootHeapDump(): File {
    val file = testFolder.newFile("crowded-root.hprof")
    file.dump {
      val siblingClassId = clazz(
        className = "com.example.Sibling",
        fields = listOf("payload" to ReferenceHolder::class)
      )
      val objectArrayClassId = arrayClass("java.lang.Object")
      repeat(SIBLING_COUNT) { index ->
        val payload = objectArray(objectArrayClassId, LongArray(SIBLING_PAYLOAD_LENGTH))
        val sibling = instance(siblingClassId, fields = listOf(ReferenceHolder(payload)))
        gcRoot(JniGlobal(id = sibling.value, jniGlobalRefId = index.toLong()))
      }
    }
    return file
  }

  companion object {
    /** Somewhere in the middle of the view, which is inside whatever it draws biggest. */
    private const val TREEMAP_X = 0.4f
    private const val TREEMAP_Y = 0.6f

    /** How far inside the left edge of the view a container's outline is pressed. Within EDGE_GRAB. */
    private const val EDGE_PRESS_INSET = 2f

    /**
     * Well inside the largest rectangle of the second level rather than in its label band, which is what
     * the rectangle standing for the siblings that didn't fit is. A fraction of the window, so it only has
     * to be somewhere in the top left block.
     */
    private const val LEFTOVER_X = 0.05f
    private const val LEFTOVER_Y = 0.45f

    /** Opening a heap dump and rebuilding a tree both happen on another thread. */
    private const val OPEN_TIMEOUT_MILLIS = 10_000L

    private const val HOLDER_LABEL = "Holder"
    private const val PAYLOAD_LENGTH = 4096
    private const val WEAKLY_REACHABLE_DUMP_NAME = "weakly-reachable.hprof"
    private const val WEAK_PAYLOAD_LENGTH = 32768
    private const val WEAK_PAYLOAD_BYTE_SIZE = WEAK_PAYLOAD_LENGTH * 4L
    private const val GARBAGE_PAYLOAD_LENGTH = 32768
    private const val GARBAGE_PAYLOAD_BYTE_SIZE = GARBAGE_PAYLOAD_LENGTH * 4L

    /** Twice what a node draws one by one, so half the siblings end up in one rectangle. */
    private const val SIBLING_COUNT = 400
    private const val SIBLING_CLASS_NAME = "com.example.Sibling"
    private const val SIBLING_PAYLOAD_LENGTH = 16
  }
}
