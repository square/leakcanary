package leakcanary.internal.activity.screen

/**
 * Performs word wrapping of leak traces.
 */
internal object LeakTraceWrapper {
  private const val SPACE = '\u0020'
  private const val TILDE = '\u007E'
  private const val PERIOD = '\u002E'
  private const val ZERO_SPACE_WIDTH = '\u200B'

  /**
   * This implements a greedy wrapping algorithm.
   *
   * Each line that is longer than [maxWidth], is wrapped by taking the maximum amount of words that fit
   * within the bounds delimited by [maxWidth]. This is done by walking back from the character at [maxWidth]
   * position, until the first separator is found (a [SPACE] or [PERIOD]).
   *
   * Additionally, [Underline] characters are tracked and added when necessary.
   *
   * Finally, all lines start with an offset which includes a decorator character and some level of
   * indentation.
   */
  fun wrap(
    sourceMultilineString: String,
    maxWidth: Int
  ): String {
    // Lines without terminating line separators
    val linesNotWrapped = sourceMultilineString.lines()

    val linesWrapped = mutableListOf<String>()

    for (currentLineIndex in linesNotWrapped.indices) {
      val currentLine = linesNotWrapped[currentLineIndex]

      if (TILDE in currentLine) {
        check(currentLineIndex > 0) {
          "A $TILDE character cannot be placed on the first line of a leak trace"
        }
        continue
      }

      val nextLineWithUnderline = if (currentLineIndex < linesNotWrapped.lastIndex) {
        linesNotWrapped[currentLineIndex + 1].run { if (TILDE in this) this else null }
      } else null

      val currentLineTrimmed = currentLine.trimEnd()
      if (currentLineTrimmed.length <= maxWidth) {
        linesWrapped += currentLineTrimmed
        if (nextLineWithUnderline != null) {
          linesWrapped += nextLineWithUnderline
        }
      } else {
        linesWrapped += wrapLine(currentLineTrimmed, nextLineWithUnderline, maxWidth)
      }
    }
    return linesWrapped.joinToString(separator = "\n") { it.trimEnd() }
  }

  private fun wrapLine(
    currentLine: String,
    nextLineWithUnderline: String?,
    maxWidth: Int
  ): List<String> {

    val twoCharPrefixes = mapOf(
        "├─" to "│ ",
        "│ " to "│ ",
        "╰→" to "$ZERO_SPACE_WIDTH ",
        "$ZERO_SPACE_WIDTH " to "$ZERO_SPACE_WIDTH "
    )

    val twoCharPrefix = currentLine.substring(0, 2)
    val prefixPastFirstLine: String
    val prefixFirstLine: String
    if (twoCharPrefix in twoCharPrefixes) {
      val indexOfFirstNonWhitespace =
        2 + currentLine.substring(2).indexOfFirst { !it.isWhitespace() }
      prefixFirstLine = currentLine.substring(0, indexOfFirstNonWhitespace)
      prefixPastFirstLine =
        twoCharPrefixes[twoCharPrefix] + currentLine.substring(2, indexOfFirstNonWhitespace)
    } else {
      prefixFirstLine = ""
      prefixPastFirstLine = ""
    }

    var lineRemainingChars = currentLine.substring(prefixFirstLine.length)

    val maxWidthWithoutOffset = maxWidth - prefixFirstLine.length

    val lineWrapped = mutableListOf<String>()
    var periodsFound = 0

    var updatedUnderlineStart: Int
    val underlineStart: Int

    if (nextLineWithUnderline != null) {
      underlineStart = nextLineWithUnderline.indexOf(TILDE)
      updatedUnderlineStart = underlineStart - prefixFirstLine.length
    } else {
      underlineStart = -1
      updatedUnderlineStart = -1
    }

    var underlinedLineIndex = -1
    while (lineRemainingChars.isNotEmpty() && lineRemainingChars.length > maxWidthWithoutOffset) {
      val stringBeforeLimit = lineRemainingChars.substring(0, maxWidthWithoutOffset)

      val lastIndexOfSpace = stringBeforeLimit.lastIndexOf(SPACE)
      val lastIndexOfPeriod = stringBeforeLimit.lastIndexOf(PERIOD)

      val lastIndexOfCurrentLine = lastIndexOfSpace.coerceAtLeast(lastIndexOfPeriod).let {
        if (it == -1) {
          stringBeforeLimit.lastIndex
        } else {
          it
        }
      }

      if (lastIndexOfCurrentLine == lastIndexOfPeriod) {
        periodsFound++
      }

      val wrapIndex = lastIndexOfCurrentLine + 1

      // remove spaces at the end if any
      lineWrapped += stringBeforeLimit.substring(0, wrapIndex).trimEnd()

      // This line has an underline and we haven't find its new position after wrapping yet.
      if (nextLineWithUnderline != null && underlinedLineIndex == -1) {
        if (lastIndexOfCurrentLine < updatedUnderlineStart) {
          updatedUnderlineStart -= wrapIndex
        } else {
          underlinedLineIndex = lineWrapped.lastIndex
        }
      }

      lineRemainingChars = lineRemainingChars.substring(wrapIndex, lineRemainingChars.length)
    }

    // there are still residual words to be added, if we exit the loop with a non-empty line
    if (lineRemainingChars.isNotEmpty()) {
      lineWrapped += lineRemainingChars
    }

    if (nextLineWithUnderline != null) {
      if (underlinedLineIndex == -1) {
        underlinedLineIndex = lineWrapped.lastIndex
      }
      val underlineEnd = nextLineWithUnderline.lastIndexOf(TILDE)
      val underlineLength = underlineEnd - underlineStart + 1

      val spacesBeforeTilde = "$SPACE".repeat(updatedUnderlineStart)
      val underlineTildes = "$TILDE".repeat(underlineLength)
      lineWrapped.add(underlinedLineIndex + 1, "$spacesBeforeTilde$underlineTildes")
    }

    return lineWrapped.mapIndexed { index: Int, line: String ->
      (if (index == 0) {
        prefixFirstLine
      } else {
        prefixPastFirstLine
      } + line).trimEnd()
    }
  }
}