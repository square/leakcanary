package shark.explorer

import java.io.File
import shark.SharkLog

/**
 * Where the heap dumps opened on this machine are, under the ids a [DeepLink] names them by.
 *
 * **This is what lets a link be short.** A link is a line of text someone reads — in a note, a pull request
 * comment, an agent's answer — and a path is most of the characters of one while saying the least: it is
 * unreadable at a glance, it says nothing a reader can act on, and it is the part that a link outliving the
 * run it was copied from has to carry only because nothing else remembers it. So nothing else is where it
 * stops being: a heap dump opening writes down where it was, and a link says the dump's file name and the
 * window it was copied from — short, readable, and enough to find the file again here.
 *
 * One file per heap dump opened, named after the window that opened it and holding that dump's path. A file
 * each rather than one file of all of them, because several runs of this app open heap dumps at the same time
 * and none of them coordinates with the others: a whole file written and renamed into place cannot be read as
 * half of one, and two runs opening two dumps write two files instead of racing over one.
 *
 * The newest [keepCount] are kept, so this is a directory that stops growing rather than a record of every
 * heap dump ever opened. Which is the one thing a link loses by not carrying the path: it goes on working for
 * as long as this machine remembers the file, rather than for as long as the file exists. A link that has been
 * forgotten says so and can still be given the path by hand — see [DeepLink.heapDumpPath].
 *
 * Machine local, and no worse than the path would have been: a link followed on another machine could never
 * have used this one's paths. What it uses there is the file name, against the dumps that machine has open or
 * has opened.
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
   * Writes down that the window called [windowId] has [heapDumpFile] open, and forgets the oldest of these
   * beyond [keepCount].
   *
   * Called as a heap dump finishes opening, in a window or in a run that has none: a dump that failed to open
   * is not one a link should be sent to.
   */
  fun record(
    windowId: String,
    heapDumpFile: File
  ) {
    val path = normalizedHeapDumpPath(heapDumpFile)
    try {
      writeWholeFile(File(directory, windowId), path.path)
    } catch (throwable: Throwable) {
      // Not a reason to fail the open: what stops working is links to this dump once every window of it has
      // gone, which is worth a line in the log rather than a window that refuses to show a heap dump.
      SharkLog.d(throwable) { "Could not record where $path is: links to it will need its path" }
      return
    }
    forgetOldest()
  }

  /**
   * [link] with the heap dump's path filled in from what this machine remembers, or [link] as it is when
   * nothing here has that dump on record.
   *
   * What a link says is tried in the order that is right about the most: the window it was copied from, since
   * that window's dump is the one its reader was looking at; then a dump of that file name, newest first,
   * since a name is what a link and a person both call a heap dump; then the name as a window id, for a link
   * whose whole authority is one — `shark://abcd2345/leaks`, which is what this app used to write and what
   * anything can still write, since a window id is enough to find the dump it was showing.
   *
   * A link that already carries a path is left alone. That path was either put there by hand or filled in by
   * another run of this app, and either way it is more specific than a name.
   */
  fun resolve(link: DeepLink): DeepLink {
    if (link.heapDumpPath != null) {
      return link
    }
    val records = records()
    val recorded = link.windowId?.let { id -> records.firstOrNull { it.windowId == id } }
      ?: records.firstOrNull { it.path.name == link.heapDumpName }
      ?: records.firstOrNull { it.windowId == link.heapDumpName }
      ?: return link
    // Worded to read for a link named by a window id as well as by a file name, since both land here.
    SharkLog.d {
      "${link.heapDumpName} is ${recorded.path}, which was last open as ${recorded.windowId}"
    }
    return link.copy(heapDumpPath = recorded.path)
  }

  /** Every heap dump on record, most recently opened first, which is the order all three lookups want. */
  private fun records(): List<Record> =
    files().sortedByDescending { it.lastModified() }.mapNotNull { file ->
      val path = try {
        file.readText().trim()
      } catch (throwable: Throwable) {
        SharkLog.d(throwable) { "Could not read $file, so it names no heap dump" }
        return@mapNotNull null
      }
      if (path.isEmpty()) null else Record(windowId = file.name, path = File(path))
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

  /** One heap dump this machine has opened, and the window it was open in. */
  private class Record(
    val windowId: String,
    val path: File
  )

  companion object {
    /**
     * How many heap dumps are remembered. Enough that a link written weeks ago still opens the dump it names,
     * few enough that this stays a directory somebody can read rather than search.
     */
    const val KEEP_COUNT = 200
  }
}
