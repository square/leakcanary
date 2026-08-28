package shark.explorer.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.explorer.Place

/**
 * What a link that couldn't be followed on its own asks, and what the answer does. See [LinkedHeapDump].
 *
 * The question itself is [ExplorerWindows]' — which link gets asked what is `ExplorerWindowTest` — so what
 * is here is the dialog: that both questions can be answered, and that the answer is the path the link then
 * opens.
 */
@OptIn(ExperimentalTestApi::class)
class LinkedHeapDumpTest {

  private val chosen = mutableListOf<File?>()

  @Test fun `a link about two heap dumps of one name offers the places they are in`() {
    runComposeUiTest {
      setContentAsking(
        LinkedHeapDump(
          heapDumpName = HEAP_DUMP_NAME,
          question = "2 heap dumps called $HEAP_DUMP_NAME are open.",
          choices = listOf(PIXEL_DUMP, EMULATOR_DUMP),
          place = Place.Starred
        )
      )

      waitUntilAtLeastOneExists(hasText(whichHeapDumpTitle(HEAP_DUMP_NAME)), TIMEOUT_MILLIS)
      // The directories, since the file name is the same on every row and is in the title above them.
      onNodeWithText(EMULATOR_DUMP.parent).performClick()

      assertThat(chosen).containsExactly(EMULATOR_DUMP)
    }
  }

  @Test fun `a link about a heap dump nothing can find asks for the file`() {
    runComposeUiTest {
      setContentAsking(
        LinkedHeapDump(
          heapDumpName = HEAP_DUMP_NAME,
          question = "No heap dump called $HEAP_DUMP_NAME is open here.",
          choices = emptyList(),
          place = Place.Starred
        ),
        chooseHeapDumpFile = { PIXEL_DUMP }
      )

      waitUntilAtLeastOneExists(hasText(whereIsHeapDumpTitle(HEAP_DUMP_NAME)), TIMEOUT_MILLIS)
      // Nothing to pick between, so the file picker is the whole of the answer.
      onNodeWithText(CHOOSE_HEAP_DUMP_FILE).performClick()

      assertThat(chosen).containsExactly(PIXEL_DUMP)
    }
  }

  @Test fun `why it is asking is said in the dialog and behind it`() {
    val question = "No heap dump called $HEAP_DUMP_NAME is open here."
    runComposeUiTest {
      setContentAsking(
        LinkedHeapDump(
          heapDumpName = HEAP_DUMP_NAME,
          question = question,
          choices = emptyList(),
          place = Place.Starred
        )
      )

      waitUntilAtLeastOneExists(hasText(question), TIMEOUT_MILLIS)

      // Once in the dialog and once in the middle of the window under it, which is what a question
      // dismissed leaves on screen: a window with nothing in it and no reason for it is worse.
      assertThat(onAllNodesWithText(question).fetchSemanticsNodes()).hasSize(2)
    }
  }

  @Test fun `a question dismissed picks nothing`() {
    runComposeUiTest {
      setContentAsking(
        LinkedHeapDump(
          heapDumpName = HEAP_DUMP_NAME,
          question = "2 heap dumps called $HEAP_DUMP_NAME are open.",
          choices = listOf(PIXEL_DUMP, EMULATOR_DUMP),
          place = Place.Starred
        )
      )

      waitUntilAtLeastOneExists(hasText(CANCEL_LINK), TIMEOUT_MILLIS)
      onNodeWithText(CANCEL_LINK).performClick()

      // Answered, and the answer is "not this link": null is what closes the question. See
      // [ExplorerWindows.chooseLinkedHeapDump].
      assertThat(chosen).containsExactly(null)
    }
  }

  /**
   * A window with no heap dump in it, being asked about one: which is where a question with nothing to pick
   * from is put, since the heap dump picked opens in that window. See [ExplorerWindows.open].
   */
  private fun ComposeUiTest.setContentAsking(
    asked: LinkedHeapDump,
    chooseHeapDumpFile: () -> File? = { null }
  ) = setContent {
    MaterialTheme {
      ExplorerApp(
        heapDumpFile = null,
        onHeapDumpChosen = { _, _ -> },
        deepLinkProblem = asked.question,
        linkedHeapDump = asked,
        onLinkedHeapDumpChosen = { chosen += it },
        chooseHeapDumpFile = chooseHeapDumpFile
      )
    }
  }

  private companion object {
    /** One name, two heap dumps: an app dumped on two devices, which is what a link cannot tell apart. */
    const val HEAP_DUMP_NAME = "com.example.hprof"

    val PIXEL_DUMP = File("/dumps/pixel/$HEAP_DUMP_NAME")
    val EMULATOR_DUMP = File("/dumps/emulator/$HEAP_DUMP_NAME")

    /** Long enough for the dialog to be composed, and it draws nothing that has to be read off disk. */
    const val TIMEOUT_MILLIS = 5_000L
  }
}
