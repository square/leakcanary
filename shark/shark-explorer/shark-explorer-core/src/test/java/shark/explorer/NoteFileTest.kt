package shark.explorer

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NoteFileTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Test fun `a place nobody has written about has no note`() {
    assertThat(noteFile("heap.hprof", Place.wholeHeapDump()).read()).isEmpty()
  }

  @Test fun `what was written is read back`() {
    val note = noteFile("heap.hprof", Place.wholeHeapDump())

    note.write("# The tab strip\nHeld by com.example.Holder")

    assertThat(note.read()).isEqualTo("# The tab strip\nHeld by com.example.Holder")
  }

  @Test fun `the same place is the same note in another run`() {
    noteFile("heap.hprof", Place.Object(HOLDER_ID)).write("Written last time")

    assertThat(noteFile("heap.hprof", Place.Object(HOLDER_ID)).read()).isEqualTo("Written last time")
  }

  @Test fun `two objects are two notes`() {
    val holder = noteFile("heap.hprof", Place.Object(HOLDER_ID))
    val payload = noteFile("heap.hprof", Place.Object(PAYLOAD_ID))

    holder.write("What holds the payload")
    payload.write("What the payload is")

    assertThat(holder.read()).isEqualTo("What holds the payload")
    assertThat(payload.read()).isEqualTo("What the payload is")
  }

  /** Two runs of one app produce two dumps of one name, and they are two investigations. */
  @Test fun `two heap dumps of one name in two directories are two notes`() {
    val monday = noteFile("dumps/monday/heap.hprof", Place.wholeHeapDump())
    val tuesday = noteFile("dumps/tuesday/heap.hprof", Place.wholeHeapDump())

    monday.write("Monday")
    tuesday.write("Tuesday")

    assertThat(monday.read()).isEqualTo("Monday")
    assertThat(tuesday.read()).isEqualTo("Tuesday")
  }

  /** Which is how a heap dump gets typed on a command line, and it is the same heap dump. */
  @Test fun `a path with a dot step in it is the same notes`() {
    noteFile("dumps/heap.hprof", Place.wholeHeapDump()).write("Written the plain way")

    assertThat(noteFile("dumps/./heap.hprof", Place.wholeHeapDump()).read())
      .isEqualTo("Written the plain way")
  }

  /**
   * Typing into the search box is a place of its own — the query is part of it — and it is not a notepad of
   * its own: a note filed under a half typed search is one nobody will find again.
   */
  @Test fun `however the object list is filtered it is one note`() {
    noteFile("heap.hprof", Place.Objects(ObjectListFilter(query = "Bit"))).write("Every bitmap here")

    assertThat(noteFile("heap.hprof", Place.Objects(ObjectListFilter(query = "Bitmap"))).read())
      .isEqualTo("Every bitmap here")
  }

  /** And the same for unfolding a leak, which is also a move to a place that is the same list. */
  @Test fun `however the leaks are unfolded they are one note`() {
    noteFile("heap.hprof", Place.Leaks()).write("Three of these are the same leak")

    assertThat(noteFile("heap.hprof", Place.Leaks(expandedGroups = setOf("abc"))).read())
      .isEqualTo("Three of these are the same leak")
  }

  /**
   * How many objects a rectangle had no room for depends on how big the window is, so it cannot be part of
   * what the note about that pile is filed under.
   */
  @Test fun `a pile of smaller objects is one note however many are in it`() {
    noteFile("heap.hprof", Place.SmallerObjects(HOLDER_ID, nodeCount = 12, byteCount = 34)).write("Tiny")

    assertThat(
      noteFile("heap.hprof", Place.SmallerObjects(HOLDER_ID, nodeCount = 400, byteCount = 5)).read()
    ).isEqualTo("Tiny")
  }

  /** So that a note can be found, opened and pasted from without going through this app. */
  @Test fun `a note is a markdown file named after what it is about`() {
    val directory = noteDirectory("large-dump.hprof")

    assertThat(directory.directory.name).startsWith("large-dump.hprof")
    assertThat(directory.noteFile(Place.wholeHeapDump()).file.name).isEqualTo("heap-dump.md")
    assertThat(directory.noteFile(Place.Object(HOLDER_ID)).file.name)
      .isEqualTo("object-${hexObjectId(HOLDER_ID)}.md")
    assertThat(directory.noteFile(Place.Leaks()).file.name).isEqualTo("leaks.md")
  }

  @Test fun `the places written about are the notes on disk`() {
    val directory = noteDirectory("heap.hprof")
    directory.noteFile(Place.wholeHeapDump()).write("About the dump")
    directory.noteFile(Place.Starred).write("About what is starred")

    assertThat(directory.keysWithNotes()).containsExactlyInAnyOrder("heap-dump", "starred")
  }

  @Test fun `a note cleared out is a note deleted`() {
    val directory = noteDirectory("heap.hprof")
    val note = directory.noteFile(Place.Starred)
    note.write("Written and thought better of")

    note.write("")

    assertThat(note.file.exists()).isFalse()
    assertThat(note.read()).isEmpty()
    assertThat(directory.keysWithNotes()).isEmpty()
  }

  /** The rename a save goes through is a file of its own, and it is not left in the directory. */
  @Test fun `a save leaves nothing behind it`() {
    val directory = noteDirectory("heap.hprof")
    val note = directory.noteFile(Place.wholeHeapDump())

    note.write("Saved")

    assertThat(directory.directory.listFiles()!!.map { it.name }).containsExactly(note.file.name)
  }

  private fun noteFile(
    heapDumpPath: String,
    place: Place
  ) = noteDirectory(heapDumpPath).noteFile(place)

  private fun noteDirectory(heapDumpPath: String) =
    NoteDirectory(notesRoot, File(testFolder.root, heapDumpPath))

  private val notesRoot by lazy { testFolder.newFolder("notes") }

  companion object {
    private const val HOLDER_ID = 0x82182c00L
    private const val PAYLOAD_ID = 0x1234L
  }
}
