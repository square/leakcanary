package leakcanary

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

  override fun toString(): String {
    return "\n${renderToString()}\n"
  }

}