package shark.explorer.app

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test

/**
 * How many windows the app has and which heap dump each one shows. No heap dump is read here: a window
 * is a file and a title until [ExplorerApp] opens it.
 */
class ExplorerWindowTest {

  /**
   * What Shark logged during this test, recorded for the same reason every test here records it: a log line
   * is built lazily, so one built from the wrong state says nothing until a test runs with a logger
   * installed. See [RecordedLog].
   */
  @get:Rule val logged = RecordedLog()

  @Test fun `an app started with no heap dump has one window to open one from`() {
    val windows = explorerWindows(noHeapDumps())

    assertThat(windows).hasSize(1)
    assertThat(windows.single().heapDumpFile).isNull()
  }

  @Test fun `every heap dump on the command line gets a window`() {
    val windows = explorerWindows(opening(FIRST_DUMP, SECOND_DUMP))

    assertThat(windows.map { it.heapDumpFile }).containsExactly(FIRST_DUMP, SECOND_DUMP)
  }

  @Test fun `the first heap dump opens in the window that has none`() {
    val windows = explorerWindows(noHeapDumps())

    windows.openHeapDump(windows.single(), FIRST_DUMP)

    assertThat(windows).hasSize(1)
    assertThat(windows.single().heapDumpFile).isEqualTo(FIRST_DUMP)
    // A run's log covers every window of that run, so which one a heap dump went to has to be in it.
    assertThat(logged).anyMatch { FIRST_DUMP.name in it && "window that had no heap dump" in it }
  }

  @Test fun `another heap dump opens in a window of its own`() {
    val windows = explorerWindows(opening(FIRST_DUMP))
    val first = windows.single()

    windows.openHeapDump(first, SECOND_DUMP)

    // The window it was opened from keeps what it was showing: two heap dumps are two windows, which is
    // what looking at both of them at once takes.
    assertThat(first.heapDumpFile).isEqualTo(FIRST_DUMP)
    assertThat(windows.map { it.heapDumpFile }).containsExactly(FIRST_DUMP, SECOND_DUMP)
    assertThat(logged).anyMatch { SECOND_DUMP.name in it && "a window of its own" in it }
  }

  @Test fun `the same heap dump twice is two windows`() {
    val windows = explorerWindows(opening(FIRST_DUMP))

    windows.openHeapDump(windows.single(), FIRST_DUMP)

    // Two independent reads of one dump rather than a jump to the window already showing it: the point
    // of a second window is that it can be somewhere else in the same heap dump.
    assertThat(windows).hasSize(2)
  }

  @Test fun `no two windows open at the same place`() {
    val windows = explorerWindows(opening(FIRST_DUMP, SECOND_DUMP))

    windows.openHeapDump(windows.first(), FIRST_DUMP)

    // A window landing exactly over the one it was opened from is what a heap dump being replaced looks
    // like, which is the one thing a window per heap dump is meant not to look like.
    assertThat(windows.map { it.cascade }).doesNotHaveDuplicates()
  }

  @Test fun `a window opens where a closed one was`() {
    val windows = explorerWindows(opening(FIRST_DUMP, SECOND_DUMP))
    windows -= windows.first()

    windows.openHeapDump(windows.single(), FIRST_DUMP)

    // The cascade is where there is room rather than how many windows have ever been opened, so working
    // through a directory of heap dumps one at a time doesn't walk them off the screen.
    assertThat(windows.map { it.cascade }).containsExactly(1, 0)
  }

  @Test fun `a window is named after the heap dump it shows`() {
    val windows = explorerWindows(opening(FIRST_DUMP))

    // Which is what tells one window from another in the window list of the OS, where every window of
    // an app is otherwise the app.
    assertThat(windows.single().title).isEqualTo(FIRST_DUMP.name)
    assertThat(ExplorerWindow(null).title).isEqualTo(APP_NAME)
  }

  @Test fun `a run given a title says it in front of every window name`() {
    val windows = explorerWindows(opening(FIRST_DUMP, titlePrefix = TITLE))

    // Which is what tells one explorer from another, where a heap dump opened twice for two reasons is
    // otherwise the same window twice.
    assertThat(windows.single().title).isEqualTo("$TITLE · ${FIRST_DUMP.name}")
    assertThat(explorerWindows(noHeapDumps(titlePrefix = TITLE)).single().title)
      .isEqualTo("$TITLE · $APP_NAME")
  }

  @Test fun `a heap dump opened into a window of its own keeps the run's title`() {
    val windows = explorerWindows(opening(FIRST_DUMP, titlePrefix = TITLE))

    windows.openHeapDump(windows.single(), SECOND_DUMP)

    // Every window of one explorer belongs to whatever that explorer was started for, however many heap
    // dumps end up open in it.
    assertThat(windows.map { it.title })
      .containsExactly("$TITLE · ${FIRST_DUMP.name}", "$TITLE · ${SECOND_DUMP.name}")
  }

  private fun noHeapDumps(titlePrefix: String? = null) =
    ExplorerArguments(heapDumpFiles = emptyList(), titlePrefix = titlePrefix)

  private fun opening(
    vararg heapDumpFiles: File,
    titlePrefix: String? = null
  ) = ExplorerArguments(heapDumpFiles = heapDumpFiles.toList(), titlePrefix = titlePrefix)

  companion object {
    /** Never opened, so these don't have to exist. */
    private val FIRST_DUMP = File("first.hprof")
    private val SECOND_DUMP = File("second.hprof")
    private const val TITLE = "Hover previews"
  }
}
