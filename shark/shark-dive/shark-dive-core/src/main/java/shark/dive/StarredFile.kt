package shark.dive

import java.io.File
import shark.SharkLog

/**
 * Which objects of one heap dump are starred, kept between runs: one file of this app's own, named after the
 * dump the way its notes and its leak statuses are. See [heapDumpFileKey].
 *
 * **Addresses and nothing else.** What a starred object *is* — its class, what it retains, how firmly it is
 * held — is read out of the heap dump when the screen opens, the same way every other list of objects reads
 * it. Keeping a copy here would be a second source for numbers the dump already has, and the two would agree
 * only until the reader set a status by hand and changed which objects are leaks.
 *
 * In the order they were starred rather than sorted, because that order is the reader's: starring is how a
 * handful of objects are held side by side while being compared, and the list they built is the list they
 * expect to come back to.
 *
 * One address per line, with a comment at the top, because this file is a working set someone can keep, mail
 * or check in — a line that can't be read is skipped with a line in the log rather than losing the rest.
 */
class StarredFile(
  /** This app's own directory, which the caller decides. */
  root: File,
  /** The heap dump these stars are about, which names the file and nothing more. */
  heapDumpFile: File
) {

  /** The file itself, so that a working set can be found without this app. */
  val file: File = File(root, "${heapDumpFileKey(heapDumpFile)}$STARRED_SUFFIX")

  /** What is on disk, or nothing at all for a heap dump nobody has starred anything in. */
  fun read(): List<Long> {
    if (!file.isFile) {
      return emptyList()
    }
    val objectIds = file.readLines().mapIndexedNotNull { index, line ->
      when {
        line.isBlank() || line.startsWith(COMMENT) -> null
        else -> objectIdOfHex(line.trim()) ?: null.also {
          SharkLog.d { "Skipping line ${index + 1} of $file: \"$line\" is no object address" }
        }
      }
    }
    SharkLog.d { "Read ${objectIds.size} starred objects from $file" }
    return objectIds.distinct()
  }

  /** Puts [objectIds] on disk, all of them, replacing whatever was there. See [writeWholeFile]. */
  fun write(objectIds: List<Long>) {
    val text = if (objectIds.isEmpty()) {
      // Which deletes the file: a heap dump whose last star has been taken off is one nobody has starred
      // anything in, and an empty file left behind would be a heap dump that reads as worked on.
      ""
    } else {
      objectIds.joinToString(separator = "\n", prefix = "$HEADER\n", postfix = "\n") {
        exactHexObjectId(it)
      }
    }
    writeWholeFile(file, text)
  }

  companion object {
    private const val STARRED_SUFFIX = ".starred.txt"

    private const val COMMENT = "#"

    /** What the lines are, for whoever opens this file without the app that wrote it. */
    private const val HEADER =
      "# Objects starred in Shark Dive, in the order they were starred.\n" +
        "# One address per line."
  }
}
