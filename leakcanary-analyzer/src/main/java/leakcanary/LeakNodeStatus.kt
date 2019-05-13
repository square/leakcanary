package leakcanary

import java.io.Serializable

/**
 * Evaluates whether a [LeakNode] is leaking or not, and provides a reason for that decision.
 */
typealias LeakInspector = (HprofParser, LeakNode) -> LeakNodeStatusAndReason

enum class LeakNodeStatus {
  NOT_LEAKING,
  LEAKING,
  UNKNOWN;

  companion object {

    private val UNKNOWN_REACHABILITY = LeakNodeStatusAndReason(UNKNOWN, "")

    /** The instance was needed and therefore expected to be reachable.  */
    fun notLeaking(reason: String): LeakNodeStatusAndReason {
      return LeakNodeStatusAndReason(NOT_LEAKING, reason)
    }

    /** The instance was no longer needed and therefore expected to be unreachable.  */
    fun leaking(reason: String): LeakNodeStatusAndReason {
      return LeakNodeStatusAndReason(LEAKING, reason)
    }

    /** No decision can be made about the provided instance.  */
    fun unknown(): LeakNodeStatusAndReason {
      return UNKNOWN_REACHABILITY
    }
  }
}

class LeakNodeStatusAndReason internal constructor(
  val status: LeakNodeStatus,
  val reason: String
) : Serializable