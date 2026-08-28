package shark.dive

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Where the heap dumps this machine has opened are, under the file names a link names them by. Which is what
 * a link not carrying a path rests on, so what is tested here is what a link gets to ask.
 */
class HeapDumpPathsTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  private val directory by lazy { temporaryFolder.newFolder("heap-dump-paths") }

  private val paths by lazy { HeapDumpPaths(directory) }

  @Test fun `a heap dump that has been opened here is on record under its name`() {
    paths.record(File("/dumps/leak.hprof"))

    assertThat(paths.pathsNamed("leak.hprof")).containsExactly(File("/dumps/leak.hprof"))
  }

  /**
   * The whole point of it, and the case the path in a link used to be there for: the run that copied the link
   * has been closed, and where its heap dump was is on disk rather than in the link.
   */
  @Test fun `a link outlives the run that copied it`() {
    val fromAWindow = DeepLink(File("/dumps/leak.hprof"), Place.Leaks())
    paths.record(File("/dumps/leak.hprof"))

    val link = DeepLink.parse(fromAWindow.toUri())

    assertThat(paths.pathsNamed(link.heapDumpName)).containsExactly(File("/dumps/leak.hprof"))
  }

  /**
   * Which is what a link about a name with two heap dumps behind it has to ask about, since it says nothing
   * that tells them apart. Newest first, because the one being worked on is the one opened last.
   */
  @Test fun `two heap dumps of one name are two paths, newest opened first`() {
    paths.record(File("/dumps/pixel/app.hprof"))
    paths.record(File("/dumps/emulator/app.hprof"))
    // Recorded in the same millisecond otherwise, which is not an order to read them in.
    recordOf("/dumps/pixel/app.hprof").setLastModified(FIRST_MODIFIED)
    recordOf("/dumps/emulator/app.hprof").setLastModified(LATER)

    assertThat(paths.pathsNamed("app.hprof"))
      .containsExactly(File("/dumps/emulator/app.hprof"), File("/dumps/pixel/app.hprof"))
  }

  /** Which is what has the reader asked where the file is, rather than a window that says nothing. */
  @Test fun `a heap dump nothing here has opened is nowhere`() {
    paths.record(File("/dumps/leak.hprof"))

    assertThat(paths.pathsNamed("another.hprof")).isEmpty()
  }

  @Test fun `nothing recorded at all is nowhere`() {
    assertThat(paths.pathsNamed("leak.hprof")).isEmpty()
  }

  /** One heap dump is one record, however many times it is opened — and opening it keeps it from eviction. */
  @Test fun `the same heap dump opened twice is one path`() {
    paths.record(File("/dumps/leak.hprof"))
    paths.record(File("/dumps/leak.hprof"))

    assertThat(paths.pathsNamed("leak.hprof")).containsExactly(File("/dumps/leak.hprof"))
    assertThat(directory.list()).hasSize(1)
  }

  /** Because a link is read months later, from a run started in another directory. */
  @Test fun `a recorded path is absolute and has no dots in it`() {
    paths.record(File("dumps/./over/../leak.hprof"))

    assertThat(paths.pathsNamed("leak.hprof"))
      .containsExactly(File(File("").absoluteFile, "dumps/leak.hprof"))
  }

  @Test fun `only the newest heap dumps are remembered`() {
    val directory = temporaryFolder.newFolder("keep-two")
    val paths = HeapDumpPaths(directory, keepCount = 2)

    listOf("first", "second", "third").forEachIndexed { index, name ->
      paths.record(File("/dumps/$name.hprof"))
      // Written in the same millisecond otherwise, which is not an order to evict by.
      recordOf("/dumps/$name.hprof", directory).setLastModified(FIRST_MODIFIED + index * MINUTE)
    }

    // A directory that stops growing, which is the one thing a link loses by not carrying the path: it works
    // for as long as this machine remembers the file rather than for as long as the file exists.
    assertThat(paths.pathsNamed("first.hprof")).isEmpty()
    assertThat(paths.pathsNamed("second.hprof")).containsExactly(File("/dumps/second.hprof"))
    assertThat(paths.pathsNamed("third.hprof")).containsExactly(File("/dumps/third.hprof"))
  }

  @Test fun `a record nobody can read names no heap dump`() {
    val directory = temporaryFolder.newFolder("unreadable")
    File(directory, heapDumpFileKey(File("/dumps/leak.hprof"))).writeText("")

    assertThat(HeapDumpPaths(directory).pathsNamed("leak.hprof")).isEmpty()
  }

  /** A save in flight is a file in this directory too, and one nothing may read or delete. */
  @Test fun `a write in flight is not a heap dump on record`() {
    val directory = temporaryFolder.newFolder("in-flight")
    val paths = HeapDumpPaths(directory, keepCount = 1)
    val inFlight = File(directory, "${heapDumpFileKey(File("/dumps/half-written.hprof"))}.partial")
    inFlight.writeText("/dumps/half-written.hprof")

    paths.record(File("/dumps/leak.hprof"))

    assertThat(inFlight).exists()
    assertThat(paths.pathsNamed("half-written.hprof")).isEmpty()
  }

  @Test fun `remembering none of them is not something to ask for`() {
    assertThatThrownBy { HeapDumpPaths(temporaryFolder.newFolder("none"), keepCount = 0) }
      .hasMessageContaining("not 0 of them")
  }

  /** The file [HeapDumpPaths] writes about one heap dump, for a test that has to touch it directly. */
  private fun recordOf(
    heapDumpPath: String,
    directory: File = this.directory
  ) = File(directory, heapDumpFileKey(File(heapDumpPath)))

  companion object {
    /** Any time at all, since what is read off these is their order. */
    private const val FIRST_MODIFIED = 1_600_000_000_000L
    private const val MINUTE = 60_000L
    private const val LATER = FIRST_MODIFIED + MINUTE
  }
}
