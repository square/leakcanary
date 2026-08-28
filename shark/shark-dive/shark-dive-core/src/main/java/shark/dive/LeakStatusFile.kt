package shark.dive

import java.io.File
import shark.SharkLog

/**
 * Where the leaking statuses set by hand on one heap dump are kept: one file of this app's own, named after
 * the dump the way its notes are. See [heapDumpFileKey].
 *
 * One file for the whole dump rather than a file per object, which is the other way round from the notes
 * beside it — and for the reason the notes are split: a note is a document someone edits, and a status is
 * three fields the window writes. Every question here is about all of them at once (which objects have one,
 * whether a new one disagrees with another), so a file per object would be a directory to list and a file to
 * open per answer, and nothing would be easier to read.
 *
 * Tab separated, one status per line, with the columns named in a comment at the top, because this file is
 * evidence: a status set by hand is someone's conclusion about the heap dump, and the next reader may well be
 * a script, an agent or a colleague who doesn't have this app open. The reason is whatever they typed, so its
 * newlines and tabs are escaped rather than allowed to end the line.
 */
class LeakStatusFile(
  /** This app's own directory, which the caller decides. */
  root: File,
  /** The heap dump these statuses are about, which names the file and nothing more. */
  heapDumpFile: File
) {

  /** The file itself, shown in the window so that the statuses can be found without this app. */
  val file: File = File(root, "${heapDumpFileKey(heapDumpFile)}$STATUSES_SUFFIX")

  /**
   * What is on disk, or nothing at all for a heap dump nobody has set a status on.
   *
   * A line that can't be read is skipped rather than thrown over, and says so in the log: this file is
   * hand editable on purpose, and one typo in it must not be a heap dump whose other statuses have gone.
   */
  fun read(): LeakStatusOverrides {
    if (!file.isFile) {
      return LeakStatusOverrides.NONE
    }
    val overrides = file.readLines().mapIndexedNotNull { index, line ->
      if (line.isBlank() || line.startsWith(COMMENT)) null else overrideOf(line, index + 1)
    }
    SharkLog.d { "Read ${overrides.size} statuses set by hand from $file" }
    return LeakStatusOverrides.of(overrides)
  }

  /** Puts [overrides] on disk, all of them, replacing whatever was there. See [writeWholeFile]. */
  fun write(overrides: LeakStatusOverrides) {
    val text = if (overrides.isEmpty) {
      // Which deletes the file: a heap dump whose last status has been taken off is one nobody has set one
      // on, and an empty file left behind would be a heap dump that reads as annotated.
      ""
    } else {
      // Sorted, so that the file two runs write for the same statuses is the same file: this ends up in
      // issues and in diffs, and a line order that follows whatever a map handed out reads as a change.
      overrides.all.sortedBy { it.objectId }.joinToString(
        separator = "\n",
        prefix = "$HEADER\n",
        postfix = "\n"
      ) { it.line() }
    }
    writeWholeFile(file, text)
  }

  private fun overrideOf(
    line: String,
    lineNumber: Int
  ): LeakStatusOverride? {
    val columns = line.split(SEPARATOR)
    if (columns.size != COLUMN_COUNT) {
      SharkLog.d {
        "Skipping line $lineNumber of $file: ${columns.size} columns rather than $COLUMN_COUNT"
      }
      return null
    }
    val (address, status, reason) = columns
    val objectId = objectIdOfHex(address)
    val leakStatus = LeakStatus.values().firstOrNull { it.name == status }
    if (objectId == null || leakStatus == null) {
      SharkLog.d {
        "Skipping line $lineNumber of $file: \"$address\" is no object address, or \"$status\" is no status"
      }
      return null
    }
    val unescaped = reason.unescaped()
    if (unescaped.isBlank()) {
      // Which the window can't write and [LeakStatusOverride] won't hold: a status with no reason is one
      // nobody can check, and the file having one means it was edited by hand into a state the app doesn't
      // allow.
      SharkLog.d { "Skipping line $lineNumber of $file: ${hexObjectId(objectId)} has no reason" }
      return null
    }
    return LeakStatusOverride(objectId = objectId, status = leakStatus, reason = unescaped)
  }

  private fun LeakStatusOverride.line(): String =
    listOf(exactHexObjectId(objectId), status.name, reason.escaped()).joinToString(SEPARATOR)

  companion object {
    private const val STATUSES_SUFFIX = ".leak-statuses.tsv"

    private const val SEPARATOR = "\t"
    private const val COLUMN_COUNT = 3

    private const val COMMENT = "#"

    /** What the columns are, for whoever opens this file without the app that wrote it. */
    private const val HEADER =
      "# Verdicts set by hand in Shark Dive.\n" +
        "# object\tverdict\treason, with \\n \\t \\\\ escaped"
  }
}

/**
 * Whatever was typed, on one line: the escapes a tab separated file needs, and no others.
 *
 * The backslash first, so that a reason that already has one comes back as itself rather than as an escape
 * of whatever followed it.
 */
private fun String.escaped(): String = replace("\\", "\\\\")
  .replace("\n", "\\n")
  .replace("\r", "\\r")
  .replace("\t", "\\t")

/**
 * And back, in one pass, because the pairs undone one at a time would read `\\n` — an escaped backslash
 * followed by an `n` — as a newline.
 */
private fun String.unescaped(): String {
  val unescaped = StringBuilder(length)
  var index = 0
  while (index < length) {
    val character = this[index]
    if (character != '\\' || index == lastIndex) {
      unescaped.append(character)
      index++
      continue
    }
    when (val escaped = this[index + 1]) {
      'n' -> unescaped.append('\n')
      'r' -> unescaped.append('\r')
      't' -> unescaped.append('\t')
      '\\' -> unescaped.append('\\')
      // Not an escape this app writes, so it is a backslash someone typed and whatever they typed after it.
      else -> unescaped.append('\\').append(escaped)
    }
    index += 2
  }
  return unescaped.toString()
}
