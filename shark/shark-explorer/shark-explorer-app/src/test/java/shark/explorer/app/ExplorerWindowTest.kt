package shark.explorer.app

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import shark.explorer.DeepLink
import shark.explorer.Place

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

  @Test fun `every window answers to an id of its own`() {
    val windows = explorerWindows(opening(FIRST_DUMP, FIRST_DUMP))

    // The whole reason a link names a window rather than a heap dump: the same dump open twice is two
    // places to be, and a link has to lead to the one it was copied from.
    assertThat(windows.map { it.deepLinkId }).doesNotHaveDuplicates()
  }

  @Test fun `a link goes to the window it names and to no other`() {
    val windows = explorerWindows(opening(FIRST_DUMP, SECOND_DUMP))
    val (first, second) = windows

    windows.open(DeepLink(second.deepLinkId, Place.Starred))

    assertThat(second.linkedPlaces).containsExactly(Place.Starred)
    assertThat(first.linkedPlaces).isEmpty()
    assertThat(windows).hasSize(2)
  }

  @Test fun `a place a link asked for is dropped once a tab has opened it`() {
    val windows = explorerWindows(opening(FIRST_DUMP))
    val window = windows.single()
    windows.open(DeepLink(window.deepLinkId, Place.Leaks()))

    window.linkedPlaceOpened(Place.Leaks())

    // Otherwise every recomposition would open the tab again, which is what a link asked for once looking
    // like a link followed forever would be.
    assertThat(window.linkedPlaces).isEmpty()
  }

  @Test fun `two links are two tabs, in the order they arrived`() {
    val windows = explorerWindows(opening(FIRST_DUMP))
    val window = windows.single()

    windows.open(DeepLink(window.deepLinkId, Place.Starred))
    windows.open(DeepLink(window.deepLinkId, Place.Leaks()))

    assertThat(window.linkedPlaces).containsExactly(Place.Starred, Place.Leaks())
  }

  @Test fun `a link to a window that has gone opens one saying so`() {
    val windows = explorerWindows(opening(FIRST_DUMP))

    windows.open(DeepLink(CLOSED_WINDOW_ID, Place.Starred))

    // Rather than nothing at all, which is the one answer that can't be told from the app having failed
    // to start — and a link is usually followed from somewhere that can't see either way.
    assertThat(windows).hasSize(2)
    assertThat(windows.last().deepLinkProblem).contains(CLOSED_WINDOW_ID)
    assertThat(windows.last().heapDumpFile).isNull()
    assertThat(logged).anyMatch { CLOSED_WINDOW_ID in it }
  }

  @Test fun `a window opened by a link that found nothing lands beside the others`() {
    val windows = explorerWindows(opening(FIRST_DUMP, SECOND_DUMP))

    windows.open(DeepLink(CLOSED_WINDOW_ID, Place.Starred))

    assertThat(windows.map { it.cascade }).doesNotHaveDuplicates()
  }

  @Test fun `a window that gets a heap dump stops saying a link found nothing`() {
    val windows = explorerWindows(noHeapDumps())
    windows.open(DeepLink(CLOSED_WINDOW_ID, Place.Starred))
    val empty = windows.last()

    windows.openHeapDump(empty, FIRST_DUMP)

    // The message was about this window having nothing in it, and now it has something in it.
    assertThat(empty.deepLinkProblem).isNull()
  }

  @Test fun `a run knows which windows are its own`() {
    val windows = explorerWindows(opening(FIRST_DUMP))

    // What another run of this app asks before handing a link over. See [DeepLinkPeers].
    assertThat(windows.holds(windows.single().deepLinkId)).isTrue()
    assertThat(windows.holds(CLOSED_WINDOW_ID)).isFalse()
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

    /** Shaped like one this run could have handed out, and belonging to no window of it. */
    private const val CLOSED_WINDOW_ID = "qrst6789"
  }
}
