package shark.explorer

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Where the notes about one heap dump are kept: a directory of this app's own, named after the dump, holding
 * one markdown file per place written about.
 *
 * **Not beside the heap dump**, which is the tempting place and the wrong one. A dump is opened from
 * wherever it came from — a directory pulled off a device, a temporary file, a read only mount, a checkout
 * of this repository — and writing into all of those means littering some of them and failing on the rest.
 * A directory under [root] always works and is always found again.
 *
 * Markdown on disk, and readable on its own, because a note about a leak outlives the window it was written
 * in: it gets pasted into an issue, read by an agent, or opened in an editor months later. A format only
 * this app can read would make that a chore, which is the same as making it not happen.
 *
 * A file each rather than one file with the places as sections, because notes are written one place at a
 * time: separate files mean a save touches only the note that was typed into, nothing has to be parsed back
 * out of a document that also holds someone's own headings, and the directory listing is the index — which
 * is what [keysWithNotes] is, and what marks the tabs that have been written about.
 */
class NoteDirectory(
  /** This app's own directory, which the caller decides. */
  root: File,
  /** The heap dump these notes are about, which names the directory and nothing more. */
  heapDumpFile: File
) {

  /** The directory itself, shown in the window so that the notes can be found without this app. */
  val directory: File = File(root, directoryNameFor(heapDumpFile))

  /** Where what is written about [place] is kept. */
  fun noteFile(place: Place): NoteFile = NoteFile(File(directory, "${place.noteKey()}$NOTES_SUFFIX"))

  /**
   * Which places this heap dump already has a note about, as [Place.noteKey] keys.
   *
   * A listing rather than a file opened per place, because this answers a question about every tab at once:
   * whether the tab strip marks one. Reading the notes themselves waits until one is looked at.
   */
  fun keysWithNotes(): Set<String> =
    directory.listFiles().orEmpty()
      .filter { it.isFile && it.name.endsWith(NOTES_SUFFIX) }
      .map { it.name.removeSuffix(NOTES_SUFFIX) }
      .toSet()

  companion object {

    /**
     * The dump's file name, and the directory it is in as a hash: `large-dump.hprof-1f3a9c0b`.
     *
     * The name first because these directories are read by people and listed by name, and a hash of the
     * directory after it because two dumps called `large-dump.hprof` from two runs of the same app are two
     * investigations. Which way round to solve that is the whole choice here — a path flattened into a
     * directory name would be unreadable and would hit the 255 character limit, and the name alone would
     * silently merge the notes of every dump ever called `heap.hprof`.
     *
     * [normalizedPath] rather than the path as given, because `./heap.hprof` is how a heap dump gets typed
     * on a command line and it is the same dump as `heap.hprof`.
     */
    private fun directoryNameFor(heapDumpFile: File): String {
      val heapDump = normalizedPath(heapDumpFile)
      return "${heapDump.name}-${Integer.toHexString(heapDump.parent.orEmpty().hashCode())}"
    }

    /**
     * One spelling of a heap dump's path, so that two ways of naming one file are one set of notes.
     *
     * Absolute, since the notes outlive the working directory the app was started in, and with the `.` and
     * `..` steps taken out, since `./heap.hprof` and `heap.hprof` are what the same dump gets called on a
     * command line. Not the canonical path: that resolves symlinks, which means asking the filesystem and
     * getting a different answer once the dump has been deleted.
     */
    private fun normalizedPath(heapDumpFile: File): File = heapDumpFile.absoluteFile.normalize()

    private const val NOTES_SUFFIX = ".md"
  }
}

/**
 * What a note is filed under, which is **what the tab is about rather than how it is arranged**.
 *
 * The distinction is the whole of this function. A place carries the state of the screen showing it as well
 * as its subject — what the object list is filtered to, which leaks are unfolded, how many objects a pile of
 * small ones stands for at this window width — and all of that changes while reading. Keyed on the place
 * itself, a note would follow the filter box: typing a letter into it would be moving to a different
 * notepad, and the note just written would be filed under a search nobody will run again.
 *
 * So: one note per object, one per pile, one for the object list however it is filtered, one for the leaks
 * however they are unfolded, one for the starred objects. The whole heap dump is an object like any other
 * here — the first tab of a window — and is the one to write the note that is about the dump rather than
 * about anything in it.
 */
fun Place.noteKey(): String = when (this) {
  is Place.Object ->
    if (objectId == HeapDominatorTreemap.ROOT_OBJECT_ID) {
      HEAP_DUMP_KEY
    } else {
      "object-${hexObjectId(objectId)}"
    }
  is Place.SmallerObjects -> "smaller-objects-${hexObjectId(parentObjectId)}"
  is Place.Objects -> "object-list"
  is Place.Leaks -> "leaks"
  is Place.Starred -> "starred"
}

/** The note about the heap dump as a whole, which is the place its first tab opens on. */
private const val HEAP_DUMP_KEY = "heap-dump"

/**
 * One note: a markdown file, read and written whole.
 *
 * Both halves throw [IOException] rather than swallowing it, so that a note which could not be read is
 * never quietly saved over by an empty one.
 */
class NoteFile internal constructor(
  /** The file itself, which is shown in the window so that the note can be found without this app. */
  val file: File
) {

  /** What is on disk, or nothing at all for a place nobody has written about yet. */
  fun read(): String = if (file.isFile) file.readText() else ""

  /**
   * Puts [text] on disk, through a file of its own and a rename, so that a run killed halfway through a
   * save leaves the last note rather than half of one. The one thing in this app nobody else has a copy
   * of is what someone typed into it.
   *
   * Nothing written is nothing kept: a note cleared out deletes the file rather than leaving an empty one
   * behind for the next run to find and mark a tab with.
   */
  fun write(text: String) {
    if (text.isEmpty()) {
      if (file.isFile && !file.delete()) {
        throw IOException("Could not delete $file, which is what an empty note is")
      }
      return
    }
    val directory = file.parentFile
    if (!directory.isDirectory && !directory.mkdirs()) {
      throw IOException("Could not create $directory to keep notes in")
    }
    val partial = File(directory, "${file.name}$PARTIAL_SUFFIX")
    partial.writeText(text)
    Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
  }

  companion object {
    /** A save in flight, which is never the file a note is read from. */
    private const val PARTIAL_SUFFIX = ".partial"
  }
}
