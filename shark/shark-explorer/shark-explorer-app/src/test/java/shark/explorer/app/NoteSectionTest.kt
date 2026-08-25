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
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.GcRoot.JniGlobal
import shark.ValueHolder.ReferenceHolder
import shark.dump
import shark.explorer.Adb
import shark.explorer.AdbOutput
import shark.explorer.DeepLink
import shark.explorer.DeviceHeapDumps
import shark.explorer.HeapDominatorTreemap
import shark.explorer.NoteDirectory
import shark.explorer.Place
import shark.explorer.hexObjectId

/**
 * The note kept about where a tab is, and what makes it worth keeping: the names in it lead back into the
 * window it was written in, and it is there again the next time a tab is there.
 *
 * The markdown itself is `NoteTest` in `shark-explorer-core`, and where a note is kept is `NoteFileTest`,
 * which is where anything about what a note means or is filed under belongs. What is only true here is what
 * saving and cancelling do, that a saved note is drawn with its links going where they say, that a note is
 * about the place its tab is on rather than about the tab, and that what was saved is on disk.
 */
@OptIn(ExperimentalTestApi::class)
class NoteSectionTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  /** Every UI test here records what Shark logged. See [RecordedLog]. */
  @get:Rule val logged = RecordedLog()

  /** The object id of the holder in [testHeapDump], recorded as the dump is written. */
  private var holderObjectId = 0L

  /** Which is what keeps notes from costing a strip of window on every place nobody has written about. */
  @Test fun `a place nobody has written about is a button under the title and nothing more`() {
    explorerUiTest {
      openHeapDump()

      // The button that starts one, and no section under the row it is in: no editor, and nothing that
      // offers to change a note that isn't there.
      onNode(hasText(NOTE_BUTTON) and isButton()).assertIsDisplayed()
      onNodeWithContentDescription(NOTE_EDITOR_DESCRIPTION).assertDoesNotExist()
      onNodeWithText(EDIT_NOTE_BUTTON).assertDoesNotExist()
      onNodeWithText(NOTE_MARK).assertDoesNotExist()
    }
  }

  /** The other half of that: one way in on screen at a time, never two. */
  @Test fun `the button that starts a note goes away once there is one`() {
    explorerUiTest {
      openHeapDump()
      startNote()

      write("Written about the heap dump")
      save()

      waitUntilDoesNotExist(hasText(NOTE_BUTTON), RENDER_TIMEOUT_MILLIS)
      onNode(hasText(EDIT_NOTE_BUTTON) and isButton()).assertIsDisplayed()
    }
  }

  @Test fun `what is saved is drawn as the markdown it is`() {
    explorerUiTest {
      openHeapDump()
      startNote()

      write("## What holds it")
      save()

      // The heading without its hashes, and the box that had both of them gone.
      waitUntilAtLeastOneExists(hasText("What holds it"), RENDER_TIMEOUT_MILLIS)
      onNodeWithContentDescription(NOTE_EDITOR_DESCRIPTION).assertDoesNotExist()
    }
  }

  @Test fun `a class name of the heap dump is shortened to its simple name`() {
    explorerUiTest {
      openHeapDump()
      startNote()

      write("com.example.Holder")
      save()

      waitUntilAtLeastOneExists(hasText("Holder"), RENDER_TIMEOUT_MILLIS)
    }
  }

  @Test fun `a class name leads to that class in a tab of its own`() {
    explorerUiTest {
      openHeapDump()
      startNote()
      write("com.example.Holder")
      save()
      waitUntilAtLeastOneExists(hasText("Holder"), RENDER_TIMEOUT_MILLIS)

      onNodeWithText("Holder").performClick()

      // A tab of its own rather than where the note was being read: the note is what is being worked from,
      // so the strip gains one — the heap dump, and the class.
      waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
        onAllNodes(isTab()).fetchSemanticsNodes().size == 2
      }
    }
  }

  @Test fun `an address is replaced by what the heap dump has at it`() {
    explorerUiTest {
      openHeapDump()
      startNote()

      write("Kept by ${hexObjectId(holderObjectId)}")
      save()

      waitUntilAtLeastOneExists(
        hasText("Holder instance (${hexObjectId(holderObjectId)})", substring = true),
        RENDER_TIMEOUT_MILLIS
      )
    }
  }

  @Test fun `a name this heap dump has never heard of is left as it was typed`() {
    explorerUiTest {
      openHeapDump()
      startNote()

      write("com.example.Absent")
      save()

      // Once, in the note as saved, and not shortened to `Absent`.
      waitUntilExactlyOneExists(hasText("com.example.Absent"), RENDER_TIMEOUT_MILLIS)
    }
  }

  @Test fun `a web link is shortened the way github shortens it and opens in a browser`() {
    val opened = mutableListOf<String>()
    val url = "https://github.com/square/leakcanary/issues/2841"
    explorerUiTest {
      openHeapDump(openUrl = { opened += it })
      startNote()
      write(url)
      save()
      waitUntilAtLeastOneExists(hasText("square/leakcanary#2841"), RENDER_TIMEOUT_MILLIS)

      onNodeWithText("square/leakcanary#2841").performClick()

      assertThat(opened).containsExactly(url)
    }
  }

  @Test fun `a shark link is followed the way one arriving from outside the app is`() {
    val followed = mutableListOf<DeepLink>()
    explorerUiTest {
      openHeapDump(followDeepLink = { followed += it })
      startNote()
      // Starred rather than the leaks or the object list, because the bar's button for those says the same
      // word as the link would: "★ 0 starred" doesn't, so this text is the note's and nothing else.
      write("shark://$WINDOW_ID/starred")
      save()
      waitUntilAtLeastOneExists(hasText(Place.STARRED_LABEL), RENDER_TIMEOUT_MILLIS)

      onNodeWithText(Place.STARRED_LABEL).performClick()

      // Handed to whatever routes links rather than opened here: a link names one window of one run, and
      // which window that is, is not a question this one can answer. See [DeepLinkPeers.follow].
      assertThat(followed).containsExactly(DeepLink(WINDOW_ID, Place.Starred))
    }
  }

  /** Which is the whole of what the two buttons under the box promise. */
  @Test fun `cancelling throws the writing away`() {
    val notesRoot = testFolder.newFolder("notes")
    val heapDumpFile = testHeapDump()
    explorerUiTest {
      openHeapDump(heapDumpFile = heapDumpFile, notesRoot = notesRoot)
      startNote()
      write("Thought better of")

      cancel()

      onNodeWithContentDescription(NOTE_EDITOR_DESCRIPTION).assertDoesNotExist()
      onNode(hasText(NOTE_BUTTON) and isButton()).assertIsDisplayed()
      onNodeWithText("Thought better of").assertDoesNotExist()
      assertThat(noteFile(notesRoot, heapDumpFile).read()).isEmpty()
    }
  }

  @Test fun `changing a note starts from what was saved`() {
    explorerUiTest {
      openHeapDump()
      startNote()
      write("Held by com.example.Holder")
      save()
      waitUntilAtLeastOneExists(hasText("Holder", substring = true), RENDER_TIMEOUT_MILLIS)

      onNode(hasText(EDIT_NOTE_BUTTON) and isButton()).performClick()

      noteEditor().assertTextContains("Held by com.example.Holder")
    }
  }

  @Test fun `dragging the bottom edge makes the note taller`() {
    explorerUiTest {
      openHeapDump()
      startNote()
      val before = noteEditorHeight()

      dragTheNoteEdge(by = DRAG_PIXELS)

      // How much of the window a note is worth is the reading of it against the reading of the heap dump,
      // which is a judgement that changes with the note — hence an edge rather than a number in the code.
      assertThat(noteEditorHeight()).isCloseTo(before + DRAG_PIXELS, within(SLOP_PIXELS))
    }
  }

  @Test fun `a note is never dragged so tall that its own edge leaves the window`() {
    // A short window, because that is the shape this is about: a note is dragged tall on a big screen and
    // then the window is made small, which is one laptop lid away.
    explorerUiTest(height = SHORT_WINDOW) {
      openHeapDump()
      startNote()

      dragTheNoteEdge(by = SHORT_WINDOW.value)

      // The edge is the only way back, so a drag that put it past the bottom of the window would leave the
      // note as tall as it was dragged until the window itself is made bigger.
      onNodeWithContentDescription(RESIZE_NOTE_HINT).assertIsDisplayed()
      assertThat(noteEditorHeight()).isLessThan(SHORT_WINDOW.value)
    }
  }

  /** Which is what a note being about a place means: a tab somewhere else is another note. */
  @Test fun `a note is only about the place the tab it was written on is at`() {
    explorerUiTest {
      openHeapDump()
      startNote()
      write("Written about the heap dump")
      save()
      // Marked on the tab, which is how a reader who moves away knows the note went with the object rather
      // than with the window.
      waitUntilAtLeastOneExists(hasText(NOTE_MARK), RENDER_TIMEOUT_MILLIS)
      waitUntilAtLeastOneExists(
        hasText("Written about the heap dump", substring = true),
        RENDER_TIMEOUT_MILLIS
      )

      // The object list, which nobody has written about: its own note, which is the button and nothing else.
      onNode(hasText(Place.OBJECTS_LABEL) and isButton()).performClick()
      waitUntilDoesNotExist(
        hasText("Written about the heap dump", substring = true),
        RENDER_TIMEOUT_MILLIS
      )
      onNode(hasText(NOTE_BUTTON) and isButton()).assertIsDisplayed()

      // And back onto the heap dump's tab, which is where the note was written.
      onAllNodes(isTab())[0].performClick()

      waitUntilAtLeastOneExists(
        hasText("Written about the heap dump", substring = true),
        RENDER_TIMEOUT_MILLIS
      )
    }
  }

  /** The other half of that, and the reason a note is filed under the place: two tabs on one are one note. */
  @Test fun `two tabs on one place are one note`() {
    explorerUiTest {
      openHeapDump()
      // The whole heap dump, opened a second time: the buttons along the top always open a new tab, so this is
      // two tabs on one place without there being two of anything else.
      onNode(hasText(HeapDominatorTreemap.ROOT_LABEL) and isButton()).performClick()
      waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
        onAllNodes(isTab()).fetchSemanticsNodes().size == 2
      }
      startNote()
      write("Written on the second tab")
      save()
      waitUntilAtLeastOneExists(hasText("Written on the second tab"), RENDER_TIMEOUT_MILLIS)

      onAllNodes(isTab())[0].performClick()

      // The note the other tab wrote, without a save having to be read back off the disk for it: the notepad
      // is the place's and both tabs are writing on it.
      waitUntilAtLeastOneExists(hasText("Written on the second tab"), RENDER_TIMEOUT_MILLIS)
      onNodeWithText(NOTE_BUTTON).assertDoesNotExist()
    }
  }

  @Test fun `what was saved is on disk`() {
    val notesRoot = testFolder.newFolder("notes")
    val heapDumpFile = testHeapDump()
    explorerUiTest {
      openHeapDump(heapDumpFile = heapDumpFile, notesRoot = notesRoot)
      startNote()
      write("Held by com.example.Holder")

      save()

      val noteFile = noteFile(notesRoot, heapDumpFile)
      waitUntil(timeoutMillis = SAVE_TIMEOUT_MILLIS) {
        noteFile.read() == "Held by com.example.Holder"
      }
    }
  }

  /**
   * Which is what keeps the same place open in two windows from being two notepads overwriting each other.
   * No window needed: it is the notepad rather than the section.
   */
  @Test fun `one place is one notepad however the heap dump path is spelled`() {
    val notes = ExplorerNotes(testFolder.newFolder("notes"))
    val heapDumpFile = testFolder.newFile("heap.hprof")
    val sameOtherWayRound = File(heapDumpFile.parentFile, "./${heapDumpFile.name}")

    val notepad = notes.of(heapDumpFile).of(Place.Object(HOLDER_ID))

    assertThat(notepad).isSameAs(notes.of(sameOtherWayRound).of(Place.Object(HOLDER_ID)))
  }

  /** Opens a heap dump, which is where every test above starts. */
  private fun ComposeUiTest.openHeapDump(
    heapDumpFile: File = testHeapDump(),
    notesRoot: File = testFolder.newFolder("notes"),
    openUrl: (String) -> Unit = {},
    followDeepLink: (DeepLink) -> Unit = {}
  ) {
    setContent {
      MaterialTheme {
        ExplorerApp(
          heapDumpFile = heapDumpFile,
          deepLinkId = WINDOW_ID,
          // A directory of this test's, never `~/.shark-explorer`: a test that saved into the real one
          // would write into the notes of whoever is running it.
          notes = ExplorerNotes(notesRoot),
          openUrl = openUrl,
          followDeepLink = followDeepLink,
          // Nothing here opens a second heap dump, and which window one would land in is
          // `ExplorerWindowTest`'s.
          onHeapDumpChosen = { _, _ -> },
          // An `adb` connected to nothing, rather than the one on this machine.
          deviceHeapDumps = DeviceHeapDumps(NO_DEVICE_ADB)
        )
      }
    }
    waitForTheTree(OPEN_TIMEOUT_MILLIS)
  }

  /**
   * Starts writing about the tab on screen.
   *
   * Waits for the button to be enabled rather than clicking it as it is: it stays disabled until the note
   * file has been read, which is what keeps an empty box from being saved over a note still on its way off
   * the disk.
   */
  private fun ComposeUiTest.startNote() {
    val button = hasText(NOTE_BUTTON) and isButton()
    waitUntilAtLeastOneExists(button and isEnabled(), RENDER_TIMEOUT_MILLIS)
    onNode(button).performClick()
    noteEditor().assertIsDisplayed()
  }

  private fun ComposeUiTest.write(markdown: String) {
    noteEditor().performTextInput(markdown)
  }

  private fun ComposeUiTest.save() {
    onNode(hasText(SAVE_NOTE) and isButton()).performClick()
  }

  private fun ComposeUiTest.cancel() {
    onNode(hasText(CANCEL_NOTE) and isButton()).performClick()
  }

  /** The box the markdown is typed into, which is the only thing in this window that takes typing. */
  private fun ComposeUiTest.noteEditor(): SemanticsNodeInteraction =
    onNodeWithContentDescription(NOTE_EDITOR_DESCRIPTION)

  /**
   * How tall the note is, measured on the box being typed in.
   *
   * The section itself is no node — it is a `Surface` around whichever of the two states it is in — and the
   * box is the state whose height is the whole of what the edge sets, since a written note takes what it
   * needs up to that.
   */
  private fun ComposeUiTest.noteEditorHeight(): Float = noteEditor().getBoundsInRoot().height.value

  /** Drags the note's bottom edge down by [by] pixels, which is what makes it taller. */
  private fun ComposeUiTest.dragTheNoteEdge(by: Float) {
    onNodeWithContentDescription(RESIZE_NOTE_HINT).performMouseInput {
      moveTo(center)
      press()
      moveBy(Offset(x = 0f, y = by))
      release()
    }
    waitForIdle()
  }

  /** Where the note about the tab a window opens on is kept. */
  private fun noteFile(
    notesRoot: File,
    heapDumpFile: File
  ) = NoteDirectory(notesRoot, heapDumpFile).noteFile(Place.wholeHeapDump())

  private fun isButton(): SemanticsMatcher =
    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

  private fun isTab(): SemanticsMatcher =
    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

  /** A heap dump with one class and one instance of it, which is all a note has to name. */
  private fun testHeapDump(): File {
    val file = testFolder.newFile("heap.hprof")
    file.dump {
      val holder = "com.example.Holder" instance {
        field["payload"] =
          ReferenceHolder(objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_LENGTH)))
      }
      holderObjectId = holder.value
      gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
    }
    return file
  }

  companion object {
    /** Far enough to be a drag rather than a click, and short of what the window has room for. */
    private const val DRAG_PIXELS = 120f

    /** What a drag loses to the slop that tells it from a click, which is a pixel or two of the first move. */
    private const val SLOP_PIXELS = 8f

    /** Shorter than a note can be dragged to, which is what makes the share of the window the only limit. */
    private val SHORT_WINDOW = 420.dp

    private const val PAYLOAD_LENGTH = 100

    private const val HOLDER_ID = 0x82182c00L

    /** Opening a heap dump and laying its tree out both happen on another thread. */
    private const val OPEN_TIMEOUT_MILLIS = 10_000L

    /** And so does asking it what the names in a saved note are. */
    private const val RENDER_TIMEOUT_MILLIS = 5_000L

    /** Saving is a file written, on another thread. */
    private const val SAVE_TIMEOUT_MILLIS = 10_000L

    /** What a link here names this window by, fixed so that a link can be spelled out in a test. */
    private const val WINDOW_ID = "abcd2345"

    private val NO_DEVICE_ADB = Adb { AdbOutput(exitCode = 0, text = "List of devices attached\n") }
  }
}
