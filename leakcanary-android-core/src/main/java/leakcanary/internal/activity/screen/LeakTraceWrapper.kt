package leakcanary.internal.activity.screen

import java.lang.StringBuilder

/**
 * Performs word wrapping of leak traces.
 */
internal object LeakTraceWrapper {
  private const val SPACE = '\u0020'
  private const val TILDE = '\u007E'
  private const val PERIOD = '\u002E'
  private const val ZERO_WIDTH_SPACE = '\u200B'
  private const val LINE_BREAK = '\n'
  private const val LINE_BREAK_LENGTH = 1

  private class Underline(
    internal val position: Int,
    internal val length: Int
  )

  /**
   * This implements a greedy wrapping algorithm.
   *
   * Each line that is longer than [width], is wrapped by taking the maximum amount of words that fit
   * within the bounds delimited by [width]. This is done by walking back from the character at [width]
   * position, until the first separator is found (a [SPACE] or [PERIOD]).
   *
   * Additionally, [Underline] characters are tracked and added when necessary.
   *
   * Finally, all lines start with an offset which includes a decorator character and some level of
   * indentation.
   */
  fun wrap(
    heapAnalysisString: String,
    width: Int
  ): String {
    var wrappedTrace = ""
    val lines = heapAnalysisString.lines()

    for (currentLineIndex in lines.indices) {
      val currentLine = lines[currentLineIndex]

      if (currentLine.contains(TILDE)) {
        check(currentLineIndex > 0) {
          "A $TILDE character cannot be placed on the first line of a leak trace"
        }
        /**
         * [TILDE] will be added manually during the wrapping if they underline a line that will be
         * wrapped
         */
        if (lines[currentLineIndex - 1].length > width) {
          continue
        }
      }
      if (currentLine.length <= width) {
        wrappedTrace = wrappedTrace.plus("${currentLine}$LINE_BREAK")
        continue
      }

      val nextWrappedTrace = doWrap(
          currentLine, getUnderlineIfAny(lines[currentLineIndex + 1]), width
      )
      wrappedTrace = wrappedTrace.plus("$nextWrappedTrace$LINE_BREAK")
    }
    return wrappedTrace.trimEnd().plus(LINE_BREAK)
  }

  private fun getUnderlineIfAny(line: String): Underline? {
    if (line.contains(TILDE)) {
      val underlineEnd = line.indexOfLast { it == TILDE }
      val underlineStart = line.indexOfFirst { it == TILDE }
      return Underline(underlineStart, underlineEnd - underlineStart + 1)
    }
    return null
  }

  private fun doWrap(
    line: String,
    underline: Underline?,
    width: Int
  ): String {
    var lineCopy = line

    var offset = ""
    DecoratedIndentation.get(line)
        ?.let {
          offset = "${it.decorationString}${"$SPACE".repeat(it.totalIndentation)}"
        }

    var wrappedString = ""
    var totalWrappedLines = 0
    var underlineFound = false
    var periodsFound = 0
    while (lineCopy.isNotEmpty() && lineCopy.length > width) {
      val stringBeforeLimit = lineCopy.substring(0, width)
      val reversedWrapIndex = stringBeforeLimit.reversed()
          .indexOfFirst { it == SPACE || it == PERIOD }

      if (stringBeforeLimit.reversed()[reversedWrapIndex] == PERIOD) {
        periodsFound++
      }

      var wrapIndex = stringBeforeLimit.length - reversedWrapIndex

      var underlineString: String? = null
      if (!underlineFound) {
        underline?.let {
          // periods needs to be removed from the count, as the \n already takes 1 character
          val absoluteWrapIndex =
            wrappedString.length + wrapIndex - periodsFound - totalWrappedLines * offset.length

          if (absoluteWrapIndex > it.position) {
            underlineString = buildUnderlineString(
                it,
                wrapIndex,
                absoluteWrapIndex,
                offset
            )
            underlineFound = true
          }
        }
      }

      // remove the space at the end if there is any
      wrappedString = wrappedString.plus("${stringBeforeLimit.substring(0, wrapIndex)}")
          .trimEnd()

      underlineString?.let {
        wrappedString = wrappedString.plus(underlineString)
      }

      lineCopy = lineCopy.substring(wrapIndex, lineCopy.length)
      lineCopy = StringBuilder(lineCopy).insert(0, "$LINE_BREAK$offset")
          .toString()
      totalWrappedLines++
    }

    // there are still residual words to be added, if we exit the loop with a non-empty line
    if (lineCopy.isNotEmpty()) {
      wrappedString = wrappedString.plus(lineCopy)
    }

    // the underline needs to be rechecked in case it was positioned under the last line
    underline?.let {
      if (!underlineFound) {
        wrappedString = wrappedString.plus(
            buildUnderlineString(
                it,
                lineCopy.length,
                line.length,
                offset
            )
        )
      }
    }
    return wrappedString
  }

  /**
   * Builds the underline string by calculating the position of the first [TILDE] relative to the
   * beginning of the line after the latest wrap.
   */
  private fun buildUnderlineString(
    underline: Underline,
    relativeWrapPosition: Int,
    absoluteWrapPosition: Int,
    offset: String
  ): String {
    val underlineStartToWrapDistance = absoluteWrapPosition - underline.position
    var relativeUnderlineStartPosition = relativeWrapPosition - underlineStartToWrapDistance
    // offset is removed as it will re-added when building the final underline string
    relativeUnderlineStartPosition -= offset.removePrefix("$ZERO_WIDTH_SPACE").length + LINE_BREAK_LENGTH
    val spaces = "$SPACE".repeat(relativeUnderlineStartPosition)
    val underlineCharacters = "$TILDE".repeat(underline.length)
    return "$LINE_BREAK$offset$spaces$underlineCharacters"
  }

  /**
   * Represents the *decoration* and indentation that precedes each line.
   *
   * **Examples:**
   * "├─ android.net.ConnectivityThread instance" -> (decorationString = "├─", totalIndentation = 1)
   * "│    Leaking: NO (a class is never leaking)" -> (decorationString = "│", totalIndentation = 4)
   *
   * @return a [DecoratedIndentation] or `null` if the line doesn't start with any *decoration*
   */
  private class DecoratedIndentation(
    internal val decorationString: String,
    internal val totalIndentation: Int
  ) {
    private class Decoration(
      internal val str: String,
      internal val printable: Boolean
    )

    companion object {
      private const val ZERO_SPACE_WIDTH = '\u200B'

      private val DECORATIONS = listOf(
          Decoration("│", printable = true),
          Decoration("╰→", printable = false),
          Decoration("├─", printable = true),
          Decoration("$ZERO_SPACE_WIDTH", printable = true)
      )

      fun get(line: String): DecoratedIndentation? {
        for (i in DECORATIONS.indices) {
          // remove all non visible characters
          val cleanStr = line.removePrefix("$LINE_BREAK")

          if (cleanStr.startsWith(DECORATIONS[i].str)) {
            val firstNonBlankIndex = cleanStr.substring(DECORATIONS[i].str.length)
                .indexOfFirst {
                  !it.isWhitespace()
                }
            val totalIndentation =
              if (DECORATIONS[i].printable) firstNonBlankIndex else firstNonBlankIndex + DECORATIONS[i].str.length
            val decoration = if (DECORATIONS[i].printable) DECORATIONS[i].str else ""
            return DecoratedIndentation(decoration, totalIndentation)
          }
        }
        return null
      }
    }
  }
}

internal fun String.splitAndKeep(delimiter: Char): List<String> {
  var result = mutableListOf<String>()
  var lastSplitIndex = 0

  for (i in this.indices) {
    if (this[i] == delimiter) {
      result.add(this.substring(lastSplitIndex, i + 1))
      lastSplitIndex = i + 1
    }
  }

  if (lastSplitIndex < this.length) {
    result.add(this.substring(lastSplitIndex, this.length))
  }

  return result
}