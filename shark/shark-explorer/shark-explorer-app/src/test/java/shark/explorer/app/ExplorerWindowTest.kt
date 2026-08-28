package shark.explorer.app

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.explorer.Adb
import shark.explorer.AdbOutput
import shark.explorer.DeepLink
import shark.explorer.DeviceHeapDumps
import shark.explorer.HeapDumpPaths
import shark.explorer.Place
import shark.explorer.agent.AgentRefusal

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

  /** For the one thing a link needs a real file for: opening a heap dump no window has. */
  @get:Rule val temporaryFolder = TemporaryFolder()

  /** Where this test's runs remember the heap dumps they opened, which is never this machine's own. */
  private val heapDumpPaths by lazy { HeapDumpPaths(temporaryFolder.newFolder("heap-dump-paths")) }

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

    // Which is what an agent calls a window, and the same heap dump open twice is what it is for: two windows
    // on one file are two places to be told about. Never in a link, which names the heap dump.
    assertThat(windows.map { it.windowId }).doesNotHaveDuplicates()
  }

  @Test fun `a link goes to the window of the heap dump it names and to no other`() {
    val windows = explorerWindows(opening(FIRST_DUMP, SECOND_DUMP))
    val (first, second) = windows

    windows.open(DeepLink(SECOND_DUMP, Place.Starred))

    assertThat(second.linkedPlaces).containsExactly(Place.Starred)
    assertThat(first.linkedPlaces).isEmpty()
    assertThat(windows).hasSize(2)
  }

  /** A link somebody typed or shortened, which names the dump the way a person would. */
  @Test fun `a link with a file name and no path goes to the window of that file`() {
    val windows = explorerWindows(opening(FIRST_DUMP))

    windows.open(DeepLink.parse("shark://${FIRST_DUMP.name}/starred"))

    assertThat(windows.single().linkedPlaces).containsExactly(Place.Starred)
  }

  /**
   * Two readings of one heap dump, which is one heap dump: a link says nothing that tells them apart, and
   * they are showing the same file, so there is nothing worth asking about.
   */
  @Test fun `a link to a heap dump open in two windows goes to the first of them`() {
    val windows = explorerWindows(opening(FIRST_DUMP, FIRST_DUMP))
    val (first, second) = windows

    windows.open(DeepLink(FIRST_DUMP, Place.Starred))

    assertThat(first.linkedPlaces).containsExactly(Place.Starred)
    assertThat(second.linkedPlaces).isEmpty()
    assertThat(windows.mapNotNull { it.linkedHeapDump }).isEmpty()
  }

  @Test fun `a place a link asked for is dropped once a tab has opened it`() {
    val windows = explorerWindows(opening(FIRST_DUMP))
    val window = windows.single()
    windows.open(DeepLink(FIRST_DUMP, Place.Leaks()))

    window.linkedPlaceOpened(Place.Leaks())

    // Otherwise every recomposition would open the tab again, which is what a link asked for once looking
    // like a link followed forever would be.
    assertThat(window.linkedPlaces).isEmpty()
  }

  @Test fun `two links are two tabs, in the order they arrived`() {
    val windows = explorerWindows(opening(FIRST_DUMP))
    val window = windows.single()

    windows.open(DeepLink(FIRST_DUMP, Place.Starred))
    windows.open(DeepLink(FIRST_DUMP, Place.Leaks()))

    assertThat(window.linkedPlaces).containsExactly(Place.Starred, Place.Leaks())
  }

  /**
   * The payoff of a link naming the heap dump: the run it was copied from can be gone, and the link still
   * puts the reader in front of what it names, because opening the file wrote down where it was.
   */
  @Test fun `a link to a heap dump this machine has opened before opens it`() {
    val heapDumpFile = temporaryFolder.newFile("third.hprof")
    heapDumpPaths.record(heapDumpFile)
    val windows = explorerWindows(opening(FIRST_DUMP))

    windows.open(DeepLink(heapDumpFile, Place.Starred))

    val opened = windows.last()
    assertThat(windows).hasSize(2)
    assertThat(opened.heapDumpFile).isEqualTo(heapDumpFile.absoluteFile)
    assertThat(opened.linkedPlaces).containsExactly(Place.Starred)
    assertThat(windows.mapNotNull { it.linkedHeapDump }).isEmpty()
  }

  /**
   * A window whose heap dump failed to open is not a window that has it, so a link is looked up instead of
   * being handed to the window that says so.
   *
   * Which is what a run started with a relative path leaves behind — the OS launches an app with `/` for a
   * working directory, so a path off a command line typed in a checkout resolves to nothing — and the file is
   * usually right there where this machine last saw it.
   */
  @Test fun `a link about a heap dump a window failed to open opens the file instead`() {
    val heapDumpFile = temporaryFolder.newFile(FIRST_DUMP.name)
    heapDumpPaths.record(heapDumpFile)
    val windows = explorerWindows(opening(FIRST_DUMP))
    val failed = windows.single()
    failed.openProblem = "There is no file at ${FIRST_DUMP.absolutePath}"
    // So a peer that has it open takes the link ahead of this run, for the same reason. See [DeepLinkPeers].
    assertThat(windows.windowsFor(DeepLink(FIRST_DUMP, Place.Starred))).isEmpty()

    windows.open(DeepLink(FIRST_DUMP, Place.Starred))

    val opened = windows.last()
    assertThat(windows).hasSize(2)
    assertThat(opened.heapDumpFile).isEqualTo(heapDumpFile.absoluteFile)
    assertThat(opened.linkedPlaces).containsExactly(Place.Starred)
    assertThat(failed.linkedPlaces).isEmpty()
  }

  /** The one case a file name cannot answer, and the reason a link can still carry a path. */
  @Test fun `a link that says where the heap dump is opens it without looking anything up`() {
    val heapDumpFile = temporaryFolder.newFile("fourth.hprof")
    val windows = explorerWindows(opening(FIRST_DUMP))

    windows.open(lookedUp(heapDumpFile, Place.Starred))

    assertThat(windows.last().heapDumpFile).isEqualTo(heapDumpFile.absoluteFile)
    assertThat(windows.last().linkedPlaces).containsExactly(Place.Starred)
  }

  @Test fun `a link to a heap dump that has been deleted asks where it is`() {
    val windows = explorerWindows(opening(FIRST_DUMP))

    windows.open(lookedUp(SECOND_DUMP, Place.Starred))

    // Rather than nothing at all, which is the one answer that can't be told from the app having failed
    // to start — and a link is usually followed from somewhere that can't see either way.
    assertThat(windows).hasSize(2)
    val asked = windows.last()
    assertThat(asked.deepLinkProblem)
      .contains(SECOND_DUMP.name)
      .contains(SECOND_DUMP.absolutePath)
    assertThat(asked.heapDumpFile).isNull()
    // With nothing to pick from, so the question is where the file is rather than which of them it is.
    assertThat(asked.linkedHeapDump?.choices).isEmpty()
    assertThat(asked.linkedHeapDump?.place).isEqualTo(Place.Starred)
    assertThat(logged).anyMatch { SECOND_DUMP.name in it }
  }

  /**
   * A link about a heap dump this machine has no record of ever opening, which is one from somebody else's
   * machine: there was nothing here to look its path up in. See [HeapDumpPaths].
   */
  @Test fun `a link to a heap dump nothing knows where to find says what is missing`() {
    val windows = explorerWindows(opening(FIRST_DUMP))

    windows.open(DeepLink.parse("shark://${SECOND_DUMP.name}/starred"))

    val asked = windows.last()
    assertThat(asked.deepLinkProblem)
      .contains(SECOND_DUMP.name)
      .contains("no record of opening one by that name")
      // And what to type instead, since a link from another machine can carry the path.
      .contains("&dump=/path/to/${SECOND_DUMP.name}")
    // The same sentence in the dialog that asks for the file, so the question and the reason for it are one.
    assertThat(asked.linkedHeapDump?.question).isEqualTo(asked.deepLinkProblem)
  }

  /**
   * Which nothing can answer for the reader: two dumps of one name are an app dumped on two devices, or a
   * dump copied somewhere, and picking one would be picking somebody's investigation for them.
   */
  @Test fun `a link about a name two open windows share asks which of them`() {
    val pixel = temporaryFolder.newFolder("pixel").resolve("app.hprof")
    val emulator = temporaryFolder.newFolder("emulator").resolve("app.hprof")
    val windows = explorerWindows(opening(pixel, emulator))

    windows.open(DeepLink.parse("shark://app.hprof/starred"))

    // In the window of the first of them rather than in a window of its own: the question is a dialog over
    // what its reader was looking at either way, and a third window would be one nobody asked for.
    val asked = windows.first().linkedHeapDump
    assertThat(windows).hasSize(2)
    assertThat(asked?.choices).containsExactly(pixel.absoluteFile, emulator.absoluteFile)
    assertThat(asked?.question).contains("2 heap dumps called app.hprof are open")
    assertThat(windows.none { it.linkedPlaces.isNotEmpty() }).isTrue()
  }

  @Test fun `a link about a name two heap dumps on record share asks which of them`() {
    val pixel = temporaryFolder.newFolder("pixel").resolve("app.hprof").apply { writeText("") }
    val emulator = temporaryFolder.newFolder("emulator").resolve("app.hprof").apply { writeText("") }
    heapDumpPaths.record(pixel)
    heapDumpPaths.record(emulator)
    val windows = explorerWindows(opening(FIRST_DUMP))

    windows.open(DeepLink.parse("shark://app.hprof/starred"))

    // A window of its own this time, since no window here is showing either of them: it is where whichever
    // one is picked will open, and it says why it is empty in the meantime.
    val asked = windows.last()
    assertThat(asked.linkedHeapDump?.choices)
      .containsExactlyInAnyOrder(pixel.absoluteFile, emulator.absoluteFile)
    assertThat(asked.deepLinkProblem).contains("have been opened here, and none is open now")
  }

  @Test fun `the heap dump picked for a link is where the link goes`() {
    val heapDumpFile = temporaryFolder.newFile("picked.hprof")
    val windows = explorerWindows(noHeapDumps())
    windows.open(DeepLink.parse("shark://picked.hprof/leaks"))
    val asked = windows.single()

    windows.chooseLinkedHeapDump(asked, heapDumpFile)

    // The window that asked, since it had nothing in it, and the place the link was going all along.
    assertThat(asked.heapDumpFile).isEqualTo(heapDumpFile.absoluteFile)
    assertThat(asked.linkedPlaces).containsExactly(Place.Leaks())
    assertThat(asked.linkedHeapDump).isNull()
  }

  @Test fun `a question dismissed leaves the reason it was asked on screen`() {
    val windows = explorerWindows(noHeapDumps())
    windows.open(DeepLink.parse("shark://picked.hprof/leaks"))
    val asked = windows.single()

    windows.chooseLinkedHeapDump(asked, chosen = null)

    // A link not followed, which is the reader's answer: the dialog goes and what it was about stays.
    assertThat(asked.linkedHeapDump).isNull()
    assertThat(asked.heapDumpFile).isNull()
    assertThat(asked.deepLinkProblem).contains("picked.hprof")
    assertThat(logged).anyMatch { "Nothing was picked" in it }
  }

  /** Two links with nowhere to go are two questions, and the second must not take the first one's window. */
  @Test fun `a second link that needs an answer gets a window of its own`() {
    val windows = explorerWindows(noHeapDumps())

    windows.open(DeepLink.parse("shark://one.hprof/leaks"))
    windows.open(DeepLink.parse("shark://two.hprof/starred"))

    assertThat(windows.mapNotNull { it.linkedHeapDump?.heapDumpName })
      .containsExactly("one.hprof", "two.hprof")
  }

  @Test fun `a window opened by a link that found nothing lands beside the others`() {
    val windows = explorerWindows(opening(FIRST_DUMP, SECOND_DUMP))

    windows.open(DeepLink.parse("shark://third.hprof/starred"))

    assertThat(windows.map { it.cascade }).doesNotHaveDuplicates()
  }

  @Test fun `a window that gets a heap dump stops saying a link found nothing`() {
    val windows = explorerWindows(noHeapDumps())
    windows.open(DeepLink.parse("shark://third.hprof/starred"))
    val empty = windows.last()

    windows.openHeapDump(empty, FIRST_DUMP)

    // The message was about this window having nothing in it, and now it has something in it.
    assertThat(empty.deepLinkProblem).isNull()
  }

  @Test fun `a row of an agent's session goes to the window that has that heap dump`() {
    val windows = explorerWindows(opening(FIRST_DUMP, SECOND_DUMP))
    val (first, second) = windows

    // The absolute path, which is what a session recorded, against a window holding the relative one it was
    // given on the command line: the same heap dump, and one window of it.
    windows.goToHeapDump(SECOND_DUMP.absoluteFile, Place.Leaks())

    // Not a second window on the same dump: a session names the heap dump it read rather than a window,
    // since the run that answered that agent has usually ended and its window ids with it.
    assertThat(second.linkedPlaces).containsExactly(Place.Leaks())
    assertThat(first.linkedPlaces).isEmpty()
    assertThat(windows).hasSize(2)
  }

  @Test fun `a row about a heap dump no window has open opens it`() {
    val windows = explorerWindows(opening(FIRST_DUMP))

    windows.goToHeapDump(SECOND_DUMP.absoluteFile, Place.Starred)

    // Because the alternative is the app showing somebody what an agent looked at and then declining to
    // show them the thing.
    val opened = windows.last()
    assertThat(opened.heapDumpFile).isEqualTo(SECOND_DUMP.absoluteFile)
    assertThat(opened.linkedPlaces).containsExactly(Place.Starred)
  }

  /**
   * What another run of this app asks before handing a link over — and it is about windows that exist and
   * never about a file this run could open, or every run on the machine would claim every link. See
   * [DeepLinkPeers].
   */
  @Test fun `a run claims a link only for a heap dump it has open`() {
    val windows = explorerWindows(opening(FIRST_DUMP))
    heapDumpPaths.record(temporaryFolder.newFile(SECOND_DUMP.name))

    assertThat(windows.windowsFor(DeepLink(FIRST_DUMP, Place.Starred))).containsExactly(windows.single())
    // On record here and not on screen here, which is a link for whoever has it open — and this run's to
    // answer for only once nobody else has claimed it.
    assertThat(windows.windowsFor(DeepLink(SECOND_DUMP, Place.Starred))).isEmpty()
  }

  @Test fun `an agent asking for a heap dump a window already has gets that window`() {
    val windows = explorerWindows(opening(FIRST_DUMP))
    val window = windows.single()
    // What a window says when its dump turns out not to be one, which is the one outcome a test can reach
    // without a real heap dump: either way it ends the wait, and what this is about is which window waited.
    window.openProblem = "Not a heap dump."

    assertThatThrownBy { runBlocking { agentHeapDumps(windows).open(FIRST_DUMP.absoluteFile) } }
      .isInstanceOf(AgentRefusal::class.java)

    // A second window on it would be a second index of the same gigabyte, and a window nobody asked for —
    // unlike the button above the map, where a person opening one dump twice is comparing two readings of it.
    assertThat(windows).hasSize(1)
    assertThat(logged).anyMatch { "already has" in it && window.windowId in it }
  }

  @Test fun `an agent asking for a heap dump nobody has open gets a window of its own`() {
    val windows = explorerWindows(opening(FIRST_DUMP))

    // Nothing is composing these windows, so the dump never opens and the call never returns: what is being
    // read here is the window it made on the way, which is the half of it that isn't the heap dump.
    runBlocking {
      withTimeoutOrNull(WAIT_MILLIS) { agentHeapDumps(windows).open(SECOND_DUMP.absoluteFile) }
    }

    assertThat(windows.map { it.heapDumpFile }).containsExactly(FIRST_DUMP, SECOND_DUMP.absoluteFile)
    assertThat(logged).anyMatch { "An agent opened" in it && SECOND_DUMP.name in it }
  }

  /** The agent surface over these windows, with an `adb` that isn't there: nothing here reaches a device. */
  private fun agentHeapDumps(windows: ExplorerWindows) = WindowAgentHeapDumps(
    windows = windows,
    deviceHeapDumps = DeviceHeapDumps(object : Adb {
      override fun run(arguments: List<String>) = AdbOutput(exitCode = 1, text = "")
    })
  )

  /**
   * A link that says where the heap dump is, which is one written by hand about a dump this machine has never
   * opened. Every link this app writes says the file name and no more — see [HeapDumpPaths].
   */
  private fun lookedUp(
    heapDumpFile: File,
    place: Place
  ) = DeepLink(
    heapDumpName = heapDumpFile.name,
    place = place,
    heapDumpPath = heapDumpFile.absoluteFile.normalize()
  )

  private fun noHeapDumps(titlePrefix: String? = null) =
    ExplorerArguments(heapDumpFiles = emptyList(), titlePrefix = titlePrefix)

  private fun opening(
    vararg heapDumpFiles: File,
    titlePrefix: String? = null
  ) = ExplorerArguments(heapDumpFiles = heapDumpFiles.toList(), titlePrefix = titlePrefix)

  /**
   * The run's windows, with a record of this machine's heap dumps that belongs to this test.
   *
   * Never the real one: what a link about a dump no window has open does is look in it, so a test reading the
   * directory under whoever is running it would pass or fail on which heap dumps they last opened.
   */
  private fun explorerWindows(arguments: ExplorerArguments) =
    explorerWindows(arguments, heapDumpPaths)

  companion object {
    /** Never opened, so these don't have to exist. */
    private val FIRST_DUMP = File("first.hprof")
    private val SECOND_DUMP = File("second.hprof")
    private const val TITLE = "Hover previews"

    /** Long enough for a window to be added and short enough not to be a pause anybody notices. */
    private const val WAIT_MILLIS = 200L
  }
}
