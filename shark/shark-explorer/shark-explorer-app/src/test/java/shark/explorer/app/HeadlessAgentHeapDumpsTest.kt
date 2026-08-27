package shark.explorer.app

import java.io.File
import kotlinx.coroutines.runBlocking
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
import shark.explorer.LeakStatus
import shark.explorer.LeakStatusOverride
import shark.explorer.Place
import shark.explorer.agent.AgentRefusal

/**
 * An agent's heap dumps with no window anywhere, which is what `--mcp-stdio --no-ui` serves.
 *
 * What is worth pinning is that this is the *same* investigation a window records rather than a second one:
 * the verdicts and the notes go in the files a window reads, so the last test here opens the file again the
 * way another run of the app would.
 */
class HeadlessAgentHeapDumpsTest {

  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @get:Rule
  val log = RecordedLog()

  @Test
  fun `a heap dump named on the command line is open`() {
    val file = temporaryFolder.leakyHeapDump().file
    headless(file).use { heapDumps ->
      val dump = runBlocking { heapDumps.open(file) }

      assertThat(heapDumps.openHeapDumps().map { it.windowId }).containsExactly(dump.windowId)
      assertThat(dump.heapDumpPath).isEqualTo(file.absolutePath)
    }
  }

  @Test
  fun `a heap dump named on the command line is named before it can be read`() {
    val file = temporaryFolder.leakyHeapDump().file
    headless(file).use { heapDumps ->
      // Either open or indexing, and the assertion is the union because which one it is at this line is a race
      // with the open this started: the invariant that matters is that a dump this run was pointed at is never
      // in neither list. An agent that asks what is open and is told nothing, with no path, guesses a path.
      val named = heapDumps.openingHeapDumpPaths() + heapDumps.openHeapDumps().map { it.heapDumpPath }
      assertThat(named).containsExactly(file.absolutePath)

      runBlocking { heapDumps.open(file) }

      assertThat(heapDumps.openHeapDumps().map { it.heapDumpPath }).containsExactly(file.absolutePath)
      assertThat(heapDumps.openingHeapDumpPaths()).isEmpty()
    }
  }

  @Test
  fun `opening the same heap dump twice is one heap dump`() {
    val file = temporaryFolder.leakyHeapDump().file
    headless().use { heapDumps ->
      val first = runBlocking { heapDumps.open(file) }
      val second = runBlocking { heapDumps.open(file) }

      // Not merely equal ids: a second open would be a second index of the same file, on a second thread,
      // writing the notes of the first.
      assertThat(second.windowId).isEqualTo(first.windowId)
      assertThat(heapDumps.openHeapDumps()).hasSize(1)
    }
  }

  @Test
  fun `showing a place says there is no window rather than that it was shown`() {
    val file = temporaryFolder.leakyHeapDump().file
    val paths = temporaryFolder.newFolder("paths-of-the-shown-place")
    headless(paths = paths).use { heapDumps ->
      val dump = runBlocking { heapDumps.open(file) }

      val shown = dump.show(Place.Leaks())

      assertThat(shown.problem)
        .contains(NO_UI_OPTION)
        .contains(file.name)
      // And a link all the same, which is the half of it an agent passes on: a link names the heap dump rather
      // than a window, so one from a run with no window opens this file for whoever clicks it. Nothing but the
      // dump and the place on it — no window of this run to prefer, and no path, because opening the dump
      // wrote down where it is.
      val link = DeepLink.parse(shown.link!!)
      assertThat(link.heapDumpName).isEqualTo(file.name)
      assertThat(link.place).isEqualTo(Place.Leaks())
      assertThat(link.windowId).isNull()
      assertThat(link.heapDumpPath).isNull()
      assertThat(HeapDumpPaths(paths).resolve(link).heapDumpPath).isEqualTo(file.absoluteFile)
    }
  }

  @Test
  fun `a file that is no heap dump is refused, and can be opened again once it is one`() {
    val notADump = temporaryFolder.newFile("not-a-heap-dump.hprof")
    headless().use { heapDumps ->
      assertThatThrownBy { runBlocking { heapDumps.open(notADump) } }
        .isInstanceOf(AgentRefusal::class.java)
        .hasMessageContaining(notADump.absolutePath)

      // The failure isn't remembered for the rest of the session: a path given before the file was written is
      // a path worth giving again, which is most of how a dump taken by hand arrives.
      val real = temporaryFolder.leakyHeapDump().file
      real.copyTo(notADump, overwrite = true)
      assertThat(runBlocking { heapDumps.open(notADump) }.heapDumpPath).isEqualTo(notADump.absolutePath)
    }
  }

  @Test
  fun `a verdict recorded with no window is on disk for the next window to read`() {
    val dumped = temporaryFolder.leakyHeapDump()
    val statuses = temporaryFolder.newFolder("leak-statuses")
    val notes = temporaryFolder.newFolder("notes")
    headless(dumped.file, statuses = statuses, notes = notes).use { heapDumps ->
      val dump = runBlocking { heapDumps.open(dumped.file) }

      runBlocking {
        dump.setVerdict(
          LeakStatusOverride(dumped.watchedObjectId, LeakStatus.STUCK, "The app said it was done with it."),
          solved = emptyList()
        )
        dump.appendToNote(Place.Object(dumped.watchedObjectId), "Held by the presenters map.")
      }

      // Read back the way another run of the app reads it, which is the whole claim: an investigation over
      // ssh today is one a window opens tomorrow.
      val reread = ExplorerLeakStatuses(statuses).of(dumped.file)
      runBlocking { reread.read() }
      assertThat(reread.overrides[dumped.watchedObjectId]?.status).isEqualTo(LeakStatus.STUCK)
      val rereadNote = ExplorerNotes(notes).of(dumped.file).of(Place.Object(dumped.watchedObjectId))
      runBlocking { rereadNote.read() }
      assertThat(rereadNote.text).contains("Held by the presenters map.")
    }
  }

  private fun headless(
    vararg heapDumpFiles: File,
    statuses: File = temporaryFolder.newFolder("statuses-${heapDumpFiles.size}"),
    notes: File = temporaryFolder.newFolder("notes-${heapDumpFiles.size}"),
    paths: File = temporaryFolder.newFolder("paths-${heapDumpFiles.size}")
  ) = HeadlessAgentHeapDumps(
    // Nothing here reaches a device, and an `adb` that answers nothing is what proves it: a test that took
    // the machine's would have whatever is plugged in to answer for.
    deviceHeapDumps = DeviceHeapDumps(NoAdb),
    heapDumpFiles = heapDumpFiles.toList(),
    notes = ExplorerNotes(notes),
    leakStatuses = ExplorerLeakStatuses(statuses),
    // This machine's own is where the app writes these, and a test writing there would leave records of heap
    // dumps that only ever existed in a temporary folder.
    heapDumpPaths = HeapDumpPaths(paths)
  )

  /** An `adb` that isn't there, which is what a build server running this has. */
  private object NoAdb : Adb {
    override fun run(arguments: List<String>) = AdbOutput(exitCode = 1, text = "")
  }
}
