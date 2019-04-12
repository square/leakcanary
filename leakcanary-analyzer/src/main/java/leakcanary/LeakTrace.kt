package leakcanary

import leakcanary.Reachability.Status.REACHABLE
import leakcanary.Reachability.Status.UNKNOWN
import leakcanary.internal.renderToString
import java.io.Serializable

/**
 * A chain of references that constitute the shortest strong reference path from a leaking instance
 * to the GC roots. Fixing the leak usually means breaking one of the references in that chain.
 */
data class LeakTrace(
  val elements: List<LeakTraceElement>,
  val expectedReachability: List<Reachability>
) : Serializable {

  val firstElementExclusion
    get() = elements.first { element ->
      element.exclusion != null
    }.exclusion!!

  val leakCauses = elements.filterIndexed { index, _ ->
    elementMayBeLeakCause(index)
  }

  override fun toString(): String {
    return "\n${renderToString()}\n"
  }

  fun elementMayBeLeakCause(index: Int): Boolean {
    return when (expectedReachability[index].status) {
      UNKNOWN -> true
      REACHABLE -> if (index < elements.lastIndex) {
        expectedReachability[index + 1].status != REACHABLE
      } else {
        true
      }
      else -> false
    }
  }
}