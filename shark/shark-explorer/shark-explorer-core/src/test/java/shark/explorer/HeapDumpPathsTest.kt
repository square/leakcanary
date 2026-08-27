package shark.explorer

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Where a heap dump was, remembered by the id a link names it with. Which is what a link not carrying the
 * path rests on, so what is tested here is every way a link asks for one.
 */
class HeapDumpPathsTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  private val paths by lazy { HeapDumpPaths(temporaryFolder.newFolder("heap-dump-paths")) }

  @Test fun `a link naming a heap dump gets the path it was opened from`() {
    paths.record(WINDOW_ID, File("/dumps/leak.hprof"))

    val resolved = paths.resolve(DeepLink.parse("shark://leak.hprof/starred"))

    assertThat(resolved.heapDumpPath).isEqualTo(File("/dumps/leak.hprof"))
    // The rest of the link is what it was: this fills in the one thing a link doesn't say.
    assertThat(resolved.place).isEqualTo(Place.Starred)
    assertThat(resolved.heapDumpName).isEqualTo("leak.hprof")
  }

  /**
   * The whole point of it, and the case the path in a link used to be there for: the run that copied the link
   * has been closed, and where its heap dump was is on disk rather than in the link.
   */
  @Test fun `a link outlives the run that copied it`() {
    val fromAWindow = DeepLink(File("/dumps/leak.hprof"), Place.Leaks(), windowId = WINDOW_ID)
    paths.record(WINDOW_ID, File("/dumps/leak.hprof"))

    val resolved = paths.resolve(DeepLink.parse(fromAWindow.toUri()))

    assertThat(resolved.heapDumpPath).isEqualTo(File("/dumps/leak.hprof"))
  }

  /** Two dumps of one name off two devices are two investigations, so which window it was copied from wins. */
  @Test fun `a link says which of two heap dumps of the same name`() {
    paths.record(WINDOW_ID, File("/dumps/pixel/app.hprof"))
    paths.record(OTHER_WINDOW_ID, File("/dumps/emulator/app.hprof"))

    val resolved = paths.resolve(DeepLink("app.hprof", Place.Starred, windowId = WINDOW_ID))

    assertThat(resolved.heapDumpPath).isEqualTo(File("/dumps/pixel/app.hprof"))
  }

  /**
   * A link whose window is not on record — typed by hand, or copied from a run whose record has been
   * forgotten — is still about a heap dump of that name, and the last one opened is the one being worked on.
   */
  @Test fun `a name with no window falls back to the heap dump opened last`() {
    paths.record(WINDOW_ID, File("/dumps/pixel/app.hprof"))
    paths.record(OTHER_WINDOW_ID, File("/dumps/emulator/app.hprof"))
    // Recorded in the same millisecond otherwise, which is not an order to read them in.
    val directory = File(temporaryFolder.root, "heap-dump-paths")
    File(directory, WINDOW_ID).setLastModified(FIRST_MODIFIED)
    File(directory, OTHER_WINDOW_ID).setLastModified(LATER)

    val resolved = paths.resolve(DeepLink("app.hprof", Place.Starred, windowId = "qrst6789"))

    assertThat(resolved.heapDumpPath).isEqualTo(File("/dumps/emulator/app.hprof"))
  }

  /**
   * `shark://<window>/<place>`, which is a link with nothing in it but an id. Nothing writes one now, and it
   * is the shortest a link can be, so it goes on working: a window id names a heap dump too.
   */
  @Test fun `a link that is only a window id finds that window's heap dump`() {
    paths.record(WINDOW_ID, File("/dumps/leak.hprof"))

    val resolved = paths.resolve(DeepLink.parse("shark://$WINDOW_ID/leaks"))

    assertThat(resolved.heapDumpPath).isEqualTo(File("/dumps/leak.hprof"))
  }

  /** Which is what tells the reader to open the file, rather than a window that says nothing. */
  @Test fun `a heap dump nothing here has opened stays unresolved`() {
    paths.record(WINDOW_ID, File("/dumps/leak.hprof"))

    val resolved = paths.resolve(DeepLink.parse("shark://another.hprof/starred"))

    assertThat(resolved.heapDumpPath).isNull()
  }

  @Test fun `nothing recorded at all resolves nothing`() {
    assertThat(paths.resolve(DeepLink.parse("shark://leak.hprof/starred")).heapDumpPath).isNull()
  }

  /** Handed over by another run, or written by hand: more specific than a name, so it is left alone. */
  @Test fun `a link that already says where the dump is keeps that path`() {
    paths.record(WINDOW_ID, File("/dumps/leak.hprof"))
    val link = DeepLink("leak.hprof", Place.Starred, heapDumpPath = File("/elsewhere/leak.hprof"))

    assertThat(paths.resolve(link).heapDumpPath).isEqualTo(File("/elsewhere/leak.hprof"))
  }

  /** Because a link is read months later, from a run started in another directory. */
  @Test fun `a recorded path is absolute and has no dots in it`() {
    paths.record(WINDOW_ID, File("dumps/./over/../leak.hprof"))

    assertThat(paths.resolve(DeepLink.parse("shark://leak.hprof/starred")).heapDumpPath)
      .isEqualTo(File(File("").absoluteFile, "dumps/leak.hprof"))
  }

  @Test fun `only the newest heap dumps are remembered`() {
    val directory = temporaryFolder.newFolder("keep-two")
    val paths = HeapDumpPaths(directory, keepCount = 2)

    listOf("first", "second", "third").forEachIndexed { index, name ->
      paths.record(name, File("/dumps/$name.hprof"))
      // Written in the same millisecond otherwise, which is not an order to evict by.
      File(directory, name).setLastModified(FIRST_MODIFIED + index * MINUTE)
    }

    // A directory that stops growing, which is the one thing a link loses by not carrying the path: it works
    // for as long as this machine remembers the file rather than for as long as the file exists.
    assertThat(directory.list()).containsExactlyInAnyOrder("second", "third")
  }

  @Test fun `a record nobody can read names no heap dump`() {
    val directory = temporaryFolder.newFolder("unreadable")
    File(directory, WINDOW_ID).writeText("")

    assertThat(HeapDumpPaths(directory).resolve(DeepLink.parse("shark://$WINDOW_ID/leaks")).heapDumpPath)
      .isNull()
  }

  /** A save in flight is a file in this directory too, and one nothing may read or delete. */
  @Test fun `a write in flight is not a heap dump on record`() {
    val directory = temporaryFolder.newFolder("in-flight")
    val paths = HeapDumpPaths(directory, keepCount = 1)
    File(directory, "$WINDOW_ID.partial").writeText("/dumps/half-written.hprof")

    paths.record(OTHER_WINDOW_ID, File("/dumps/leak.hprof"))

    assertThat(File(directory, "$WINDOW_ID.partial")).exists()
    assertThat(paths.resolve(DeepLink.parse("shark://half-written.hprof/starred")).heapDumpPath).isNull()
  }

  @Test fun `remembering none of them is not something to ask for`() {
    assertThatThrownBy { HeapDumpPaths(temporaryFolder.newFolder("none"), keepCount = 0) }
      .hasMessageContaining("not 0 of them")
  }

  companion object {
    private const val WINDOW_ID = "abcd2345"
    private const val OTHER_WINDOW_ID = "wxyz6789"

    /** Any time at all, since what is read off these is their order. */
    private const val FIRST_MODIFIED = 1_600_000_000_000L
    private const val MINUTE = 60_000L
    private const val LATER = FIRST_MODIFIED + MINUTE
  }
}
