package com.squareup.leakcanary

import com.squareup.leakcanary.Reachability.Status.REACHABLE
import com.squareup.leakcanary.Reachability.Status.UNKNOWN
import com.squareup.leakcanary.Reachability.Status.UNREACHABLE
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
    var leakInfo = THE_T + "\n"
    val lastElement = elements.last()
    val lastReachability = expectedReachability.last()
    elements.dropLast(1)
        .forEachIndexed { index, leakTraceElement ->
          val currentReachability = expectedReachability[index]

          leakInfo += """
        |├─ ${leakTraceElement.className}
        |${LINE}${getReachabilityString(currentReachability)}
        |${LINE}${getPossibleLeakString(currentReachability, leakTraceElement, index)}
        |
        """.trimMargin()
        }
    leakInfo += """╰→ ${lastElement.className}
      | ${getReachabilityString(lastReachability)}
    """.trimMargin()

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
    index: Int
  ): String {
    val maybeLeakCause = when (reachability.status) {
      UNKNOWN -> true
      REACHABLE -> {
        if (index < elements.lastIndex) {
          val nextReachability = expectedReachability.get(index + 1);
          nextReachability.status != REACHABLE
        } else {
          true
        }
      }
      else -> false
    }
    return DEFAULT_NEWLINE_SPACE + DOWN_ARROW + " ${leakTraceElement.toString(maybeLeakCause)}"
  }

  private fun getReachabilityString(reachability: Reachability): String {
    return DEFAULT_NEWLINE_SPACE + "Leaking: " + when (reachability.status!!) {
      UNKNOWN -> "UNKNOWN"
      REACHABLE -> "NO (${reachability.reason})"
      UNREACHABLE -> "YES (${reachability.reason})"
    }
  }

  companion object {
    private val DEFAULT_NEWLINE_SPACE = "                 "
    private val LINE = "│"
    private val DOWN_ARROW = "↓"
    private val THE_T = "┬"
  }
}