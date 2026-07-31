package leakcanary

import shark.ShortestPathObjectNode

/**
 * What [ObjectGrowthAttributor] found out about the code that grows one of the objects that
 * `shark.ObjectGrowthDetector` reported as growing.
 */
sealed interface GrowthAttribution {

  /** The growing object this is about, one of `shark.HeapDiff.growingObjects`. */
  val growingObject: ShortestPathObjectNode

  /** [ObjectGrowthAttributor] watched [growingObject] while the scenario ran again. */
  class Attributed(
    override val growingObject: ShortestPathObjectNode,
    /**
     * Every distinct stack trace that grew [growingObject] while the scenario ran, most frequent
     * first.
     *
     * Empty when nothing grew [growingObject] through a method
     * [ObjectGrowthAttributor] can see, which means the growth comes from somewhere else: a write
     * straight to a field of the collection, a write to the array behind it, or another object
     * entirely that the heap traversal grouped into the same node.
     */
    val growthStacks: List<GrowthStack>,
  ) : GrowthAttribution {
    override fun toString(): String {
      val stacks = if (growthStacks.isEmpty()) {
        "    Nothing grew this object through a method that could be watched.\n"
      } else {
        growthStacks.joinToString(separator = "\n", postfix = "\n")
      }
      return "${growingObject.pathFromRootAsString()}\n$stacks"
    }
  }

  /**
   * [ObjectGrowthAttributor] could not watch [growingObject]. The growth reported by
   * `shark.ObjectGrowthDetector` still stands, there just is no stack trace to go with it.
   */
  class NotAttributed(
    override val growingObject: ShortestPathObjectNode,
    /** Human readable explanation of why [growingObject] could not be watched. */
    val reason: String,
  ) : GrowthAttribution {
    override fun toString(): String {
      return "${growingObject.pathFromRootAsString()}\n    Not attributed: $reason\n"
    }
  }
}

/**
 * A stack trace that grew a [GrowthAttribution.growingObject], and how many times it did.
 */
class GrowthStack(
  /** Name of the method that was called on the growing object, e.g. `add`. */
  val methodName: String,

  /** Where [methodName] was called from, innermost frame first. */
  val stackTrace: List<StackTraceElement>,

  /**
   * How many times [stackTrace] called [methodName] while the scenario ran. A stack trace that
   * grows the object once per scenario loop is a better suspect than one that only fires once.
   */
  val count: Int,
) {
  override fun toString(): String {
    return "    $count call(s) to $methodName()\n" +
      stackTrace.joinToString(separator = "\n") { "        at $it" }
  }
}
