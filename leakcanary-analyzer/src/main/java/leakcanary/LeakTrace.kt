package leakcanary

import leakcanary.LeakNodeStatus.NOT_LEAKING
import leakcanary.LeakNodeStatus.UNKNOWN
import leakcanary.internal.renderToString
import java.io.Serializable

/**
 * A chain of references that constitute the shortest strong reference path from a leaking instance
 * to the GC roots. Fixing the leak usually means breaking one of the references in that chain.
 */
data class LeakTrace(
  val elements: List<LeakTraceElement>
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
    return when (elements[index].leakStatusAndReason.status) {
      UNKNOWN -> true
      NOT_LEAKING -> if (index < elements.lastIndex) {
        elements[index + 1].leakStatusAndReason.status != NOT_LEAKING
      } else {
        true
      }
      else -> false
    }
  }
}