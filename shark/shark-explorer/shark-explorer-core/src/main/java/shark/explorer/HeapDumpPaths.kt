package shark.explorer

import java.io.File
import shark.SharkLog

/**
 * Where the heap dumps opened on this machine are, under the file names a [DeepLink] names them by.
 *
 * **This is what lets a link be short.** A link is a line of text someone reads — in a note, a pull request
 * comment, an agent's answer — and a path is most of the characters of one while saying the least: it is
 * unreadable at a glance, it says nothing a reader can act on, and it is the part that a link outliving the
 * run it was copied from has to carry only because nothing else remembers it. So nothing else is where it
 * stops being: a heap dump opening writes down where it was, and a link says only the dump's file name —
 * short, readable, and enough to find the file again here.
 *
 * One file per heap dump opened, named after the dump's path and holding it. A file each rather than one file
 * of all of them, because several runs of this app open heap dumps at the same time and none of them
 * coordinates with the others: a whole file written and renamed into place cannot be read as half of one, and
 * two runs opening two dumps write two files instead of racing over one.
 *
 * The newest [keepCount] are kept, so this is a directory that stops growing rather than a record of every
 * heap dump ever opened. Which is the one thing a link loses by not carrying the path: it goes on working for
 * as long as this machine remembers the file, rather than for as long as the file exists. A link about a dump
 * that has been forgotten asks where it is — see `ExplorerWindows.open` — and can also be given the path by
 * hand, see [DeepLink.heapDumpPath].
 *
 * Machine local, and no worse than the path would have been: a link followed on another machine could never
 * have used this one's paths. What it uses there is the file name, against the dumps that machine has open or
 * has opened, and failing that the reader is asked for the file.
 */
class HeapDumpPaths(
  /** This app's directory for these, which the caller decides, the way [NoteDirectory] takes its root. */
  private val directory: File,
  private val keepCount: Int = KEEP_COUNT
) {

  init {
    require(keepCount >= 1) {
      "Expected to keep at least the heap dump being opened, not $keepCount of them"
    }
  }

  /**
   * Writes down where [heapDumpFile] is, and forgets the oldest of these beyond [keepCount].
   *
   * Called as a heap dump finishes opening, in a window or in a run that has none: a dump that failed to open
   * is not one a link should be sent to. Opening the same dump again rewrites its record, which is what keeps
   * a heap dump somebody keeps coming back to from being forgotten.
   */
  fun record(heapDumpFile: File) {
    val path = normalizedHeapDumpPath(heapDumpFile)
    try {
      writeWholeFile(File(directory, heapDumpFileKey(path)), path.path)
    } catch (throwable: Throwable) {
      // Not a reason to fail the open: what stops working is links to this dump once every window of it has
      // gone, which is worth a line in the log rather than a window that refuses to show a heap dump.
      SharkLog.d(throwable) { "Could not record where $path is: links to it will need its path" }
      return
    }
    forgetOldest()
  }

  /**
   * Every path this machine remembers for a heap dump called [heapDumpName], most recently opened first.
   *
   * More than one when heap dumps off two devices are both called `com.squareup.hprof`, which is what a link
   * naming only the file has no answer for and asks about. Empty for a name nothing here has opened, or has
   * opened recently enough to still be on record. The files themselves may be gone — this says where a dump
   * was, and whoever follows a link is the one that cares whether it is still there.
   */
  fun pathsNamed(heapDumpName: String): List<File> =
    records().filter { it.name == heapDumpName }

  /** Every heap dump on record, most recently opened first, which is the order a link wants them tried in. */
  private fun records(): List<File> =
    files().sortedByDescending { it.lastModified() }.mapNotNull { file ->
      val path = try {
        file.readText().trim()
      } catch (throwable: Throwable) {
        SharkLog.d(throwable) { "Could not read $file, so it names no heap dump" }
        return@mapNotNull null
      }
      if (path.isEmpty()) null else File(path)
    }

  private fun forgetOldest() {
    val forgotten = files().sortedByDescending { it.lastModified() }.drop(keepCount)
    if (forgotten.isEmpty()) {
      return
    }
    SharkLog.d {
      "Forgetting where ${forgotten.size} heap dump(s) opened before the last $keepCount were"
    }
    forgotten.forEach { it.delete() }
  }

  /**
   * The records, and only those: a write in flight is a file in here too, and deleting another run's would
   * make its write fail. See [writeWholeFile].
   */
  private fun files(): List<File> =
    directory.listFiles { file -> file.isFile && !file.name.endsWith(PARTIAL_SUFFIX) }
      .orEmpty()
      .toList()

  companion object {
    /**
     * How many heap dumps are remembered. Enough that a link written weeks ago still opens the dump it names,
     * few enough that this stays a directory somebody can read rather than search.
     */
    const val KEEP_COUNT = 200
  }
}
