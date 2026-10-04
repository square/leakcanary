package shark

import shark.LeakTrace.Companion.ZERO_WIDTH_SPACE
import shark.LeakTraceObject.Verdict
import shark.LeakTraceObject.Verdict.EXPECTED
import shark.LeakTraceObject.Verdict.STUCK
import shark.LeakTraceObject.Verdict.UNKNOWN
import shark.internal.lastSegment
import java.io.Serializable
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

data class LeakTraceObject(
  val type: ObjectType,
  /**
   * Class name of the object.
   * The class name format is the same as what would be returned by [Class.getName].
   */
  val className: String,

  /**
   * Labels that were computed during analysis. A label provides extra information that helps
   * understand the state of the leak trace object.
   */
  val labels: Set<String>,
  /** What this object is, as worked out by the [ObjectInspector]s. See [Verdict]. */
  val verdict: Verdict,
  /**
   * Why [verdict] is what it is, in words, e.g. `Activity#mDestroyed is true`. Empty for
   * [Verdict.UNKNOWN], which is most objects of a heap dump. Half of these are about another
   * object, because a verdict propagates along a leak trace: `MainActivity↑ is stuck` is this
   * object being held by one that is.
   */
  val verdictReason: String,
  /**
   * The number of bytes credited to this object: its own shallow size, plus the shallow size of
   * every object that is only reachable through the leaking objects of the analysis and that was
   * reached from this one first.
   *
   * Releasing all references to this object can free less than that: when several leaking objects
   * hold on to the same object, its size is credited to only one of them, and the others would
   * keep it alive. In exchange nothing is double counted, so the sizes credited to all the leaking
   * objects of an analysis add up to what they retain together.
   *
   * Not null only if the retained heap size was computed AND [verdict] is equal to
   * [Verdict.UNKNOWN] or [Verdict.STUCK].
   */
  val retainedHeapByteSize: Int?,
  /**
   * The number of objects credited to this object, counting it. Credited the same way as
   * [retainedHeapByteSize]. Not null only if the retained heap size was computed AND
   * [verdict] is equal to [Verdict.UNKNOWN] or [Verdict.STUCK].
   */
  val retainedObjectCount: Int?
) : Serializable {

  /**
   * Returns {@link #className} without the package, ie stripped of any string content before the
   * last period (included).
   */
  val classSimpleName: String get() = className.lastSegment('.')

  val typeName
    get() = type.name.lowercase(Locale.US)

  override fun toString(): String {
    val firstLinePrefix = ""
    val additionalLinesPrefix = "$ZERO_WIDTH_SPACE  "
    return toString(firstLinePrefix, additionalLinesPrefix, true)
  }

  internal fun toString(
    firstLinePrefix: String,
    additionalLinesPrefix: String,
    showVerdict: Boolean,
    typeName: String = this.typeName
  ): String {
    val verdictText = when (verdict) {
      UNKNOWN -> "Unknown"
      EXPECTED -> "Expected ($verdictReason)"
      STUCK -> "Stuck ($verdictReason)"
    }

    var result = ""
    result += "$firstLinePrefix$className $typeName"
    if (showVerdict) {
      result += "\n${additionalLinesPrefix}Verdict: $verdictText"
    }

    if (retainedHeapByteSize != null) {
      val humanReadableRetainedHeapSize =
        humanReadableByteCount(retainedHeapByteSize.toLong())
      result += "\n${additionalLinesPrefix}Retaining $humanReadableRetainedHeapSize in $retainedObjectCount objects"
    }
    for (label in labels) {
      result += "\n${additionalLinesPrefix}$label"
    }
    return result
  }

  enum class ObjectType {
    CLASS,
    ARRAY,
    INSTANCE
  }

  /**
   * Whether an object of a leak trace is meant to still be in memory, worked out by the
   * [ObjectInspector]s and then propagated along the trace: everything above an [EXPECTED] object
   * is expected too, and everything below a [STUCK] one is stuck too. What is left between the last
   * [EXPECTED] object and the first [STUCK] one is where the leak is — the reference that should
   * have been cleared, which is what [LeakTrace.leakFingerprint] hashes.
   *
   * **None of the three is built on "leak"**, deliberately. A leak is one faulty reference, and
   * everything under it is retained by that single mistake, so a word like `Leaking` on twenty
   * objects points a reader at the twenty rather than at the one thing to fix. These are the same
   * three words Shark Dive shows, so that a leak trace and a heap dump open in that window say the
   * same thing about the same object.
   */
  enum class Verdict {
    /**
     * Something knows this object is still needed and therefore expected to be reachable: a live
     * activity, a class, a running thread.
     */
    EXPECTED,

    /**
     * Something knows this object should be gone: a destroyed activity, a watched object still
     * there. Including one nothing reaches any more — it was expected to be gone, and what keeps
     * it here is only that the garbage collector hasn't run.
     */
    STUCK,

    /** Nothing knows either way, which is most of a heap dump. */
    UNKNOWN
  }

  companion object {
    // Bumped when leakingStatus/leakingStatusReason became verdict/verdictReason and LEAKING /
    // NOT_LEAKING became STUCK / EXPECTED. Both the field names and the enum constant names are
    // part of what Java serialization writes, so an analysis serialized by an older version has to
    // fail to deserialize rather than come back with a null verdict on a non-null property.
    private const val serialVersionUID = 8013094195741270893L

    // https://stackoverflow.com/a/3758880
    private fun humanReadableByteCount(bytes: Long): String {
      val unit = 1000
      if (bytes < unit) return "$bytes B"
      val exp = (ln(bytes.toDouble()) / ln(unit.toDouble())).toInt()
      val pre = "kMGTPE"[exp - 1]
      return String.format("%.1f %sB", bytes / unit.toDouble().pow(exp.toDouble()), pre)
    }
  }
}
