package shark

import shark.LeakNodeStatus.NOT_LEAKING
import shark.LeakNodeStatus.UNKNOWN
import shark.internal.renderToString
import java.io.Serializable

/**
 * A chain of references that constitute the shortest strong reference path from a GC root to the
 * leaking object. Fixing the leak usually means breaking one of the references in that chain.
 */
data class LeakTrace(
  val elements: List<LeakTraceElement>
) : Serializable {
  val leakCauses = elements.filterIndexed { index, _ ->
    elementMayBeLeakCause(index)
  }

  override fun toString(): String {
    return "\n${renderToString()}\n"
  }

  fun elementMayBeLeakCause(index: Int): Boolean {
    return when (elements[index].leakStatus) {
      UNKNOWN -> true
      NOT_LEAKING -> if (index < elements.lastIndex) {
        elements[index + 1].leakStatus != NOT_LEAKING
      } else {
        false
      }
      else -> false
    }
  }
}