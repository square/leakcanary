package leakcanary

import leakcanary.Reachability.Status.REACHABLE
import leakcanary.Reachability.Status.UNKNOWN
import leakcanary.Reachability.Status.UNREACHABLE
import java.io.Serializable

/**
 * A chain of references that constitute the shortest strong reference path from a leaking instance
 * to the GC roots. Fixing the leak usually means breaking one of the references in that chain.
 */
data class LeakTrace(
  val elements: List<LeakTraceElement>,
  val expectedReachability: List<Reachability>
) : Serializable {

  override fun toString(): String {
    var leakInfo = "┬" + "\n"
    val lastElement = elements.last()
    val lastReachability = expectedReachability.last()
    elements.dropLast(1)
        .forEachIndexed { index, leakTraceElement ->
          val currentReachability = expectedReachability[index]
          val numOfSpaces = leakTraceElement.className.lastIndexOf('.') + 3 // 3 indexes from "├─ " in the first line
          leakInfo += """
        #├─ ${leakTraceElement.className}
        #│${getReachabilityString(currentReachability, numOfSpaces)}
        #│${getPossibleLeakString(currentReachability, leakTraceElement, index, numOfSpaces)}
        #
        """.trimMargin("#")
        }
    leakInfo += """╰→ ${lastElement.className}
      #$ZERO_WIDTH_SPACE${getReachabilityString(lastReachability, lastElement.className.lastIndexOf('.') + 3)}
    """.trimMargin("#")

    return leakInfo
  }

  fun toDetailedString(): String {
    return elements.joinToString {
      it.toDetailedString()
    }
  }

  private fun getPossibleLeakString(
    reachability: Reachability,
    leakTraceElement: LeakTraceElement,
    index: Int,
    numOfSpaces: Int
  ): String {
    val maybeLeakCause = when (reachability.status) {
      UNKNOWN -> true
      REACHABLE -> {
        if (index < elements.lastIndex) {
          val nextReachability = expectedReachability[index + 1]
          nextReachability.status != REACHABLE
        } else {
          true
        }
      }
      else -> false
    }
    return " ".repeat(numOfSpaces) + "↓" + " " + leakTraceElement.toString(maybeLeakCause)
  }

  private fun getReachabilityString(
    reachability: Reachability,
    numOfSpaces: Int
  ): String {
    return " ".repeat(numOfSpaces) + "Leaking: " + when (reachability.status!!) {
      UNKNOWN -> "UNKNOWN"
      REACHABLE -> "NO (${reachability.reason})"
      UNREACHABLE -> "YES (${reachability.reason})"
    }
  }

  companion object {
    private val ZERO_WIDTH_SPACE = '\u200b'
  }
}