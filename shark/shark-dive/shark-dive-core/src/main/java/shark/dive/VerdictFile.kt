package shark.dive

import java.io.File
import shark.SharkLog

/**
 * Where the verdicts set by hand on one heap dump are kept: one file of this app's own, named after
 * the dump the way its notes are. See [heapDumpFileKey].
 *
 * One file for the whole dump rather than a file per object, which is the other way round from the notes
 * beside it — and for the reason the notes are split: a note is a document someone edits, and a verdict is
 * three fields the window writes. Every question here is about all of them at once (which objects have one,
 * whether a new one disagrees with another), so a file per object would be a directory to list and a file to
 * open per answer, and nothing would be easier to read.
 *
 * Tab separated, one verdict per line, with the columns named in a comment at the top, because this file is
 * evidence: a verdict set by hand is someone's conclusion about the heap dump, and the next reader may well be
 * a script, an agent or a colleague who doesn't have this app open. The reason is whatever they typed, so its
 * newlines and tabs are escaped rather than allowed to end the line.
 */
class VerdictFile(
  /** This app's own directory, which the caller decides. */
  root: File,
  /** The heap dump these verdicts are about, which names the file and nothing more. */
  heapDumpFile: File
) {

  /** The file itself, shown in the window so that the verdicts can be found without this app. */
  val file: File = File(root, "${heapDumpFileKey(heapDumpFile)}$VERDICTS_SUFFIX")

  /**
   * What is on disk, or nothing at all for a heap dump nobody has set a verdict on.
   *
   * A line that can't be read is skipped rather than thrown over, and says so in the log: this file is
   * hand editable on purpose, and one typo in it must not be a heap dump whose other verdicts have gone.
   */
  fun read(): VerdictOverrides {
    if (!file.isFile) {
      return VerdictOverrides.NONE
    }
    val overrides = file.readLines().mapIndexedNotNull { index, line ->
      if (line.isBlank() || line.startsWith(COMMENT)) null else overrideOf(line, index + 1)
    }
    SharkLog.d { "Read ${overrides.size} verdicts set by hand from $file" }
    return VerdictOverrides.of(overrides)
  }

  /** Puts [overrides] on disk, all of them, replacing whatever was there. See [writeWholeFile]. */
  fun write(overrides: VerdictOverrides) {
    val text = if (overrides.isEmpty) {
      // Which deletes the file: a heap dump whose last verdict has been taken off is one nobody has set one
      // on, and an empty file left behind would be a heap dump that reads as annotated.
      ""
    } else {
      // Sorted, so that the file two runs write for the same verdicts is the same file: this ends up in
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
  ): VerdictOverride? {
    val columns = line.split(SEPARATOR)
    if (columns.size != COLUMN_COUNT) {
      SharkLog.d {
        "Skipping line $lineNumber of $file: ${columns.size} columns rather than $COLUMN_COUNT"
      }
      return null
    }
    val (address, verdictName, reason) = columns
    val objectId = objectIdOfHex(address)
    val verdict = Verdict.values().firstOrNull { it.name == verdictName }
    if (objectId == null || verdict == null) {
      SharkLog.d {
        "Skipping line $lineNumber of $file: \"$address\" is no object address, or \"$verdictName\" is no " +
          "verdict"
      }
      return null
    }
    val unescaped = reason.unescaped()
    if (unescaped.isBlank()) {
      // Which the window can't write and [VerdictOverride] won't hold: a verdict with no reason is one
      // nobody can check, and the file having one means it was edited by hand into a state the app doesn't
      // allow.
      SharkLog.d { "Skipping line $lineNumber of $file: ${hexObjectId(objectId)} has no reason" }
      return null
    }
    return VerdictOverride(objectId = objectId, verdict = verdict, reason = unescaped)
  }

  private fun VerdictOverride.line(): String =
    listOf(exactHexObjectId(objectId), verdict.name, reason.escaped()).joinToString(SEPARATOR)

  companion object {
    /**
     * Matched exactly, so a file written under the old name, `.leak-statuses.tsv`, goes unread. A rename
     * brings one back: it is three columns of text, named after the heap dump.
     */
    private const val VERDICTS_SUFFIX = ".verdicts.tsv"

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
