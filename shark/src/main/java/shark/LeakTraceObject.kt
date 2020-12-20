package shark

import shark.LeakTrace.Companion.ZERO_WIDTH_SPACE
import shark.LeakTraceObject.LeakingStatus
import shark.LeakTraceObject.LeakingStatus.LEAKING
import shark.LeakTraceObject.LeakingStatus.NOT_LEAKING
import shark.LeakTraceObject.LeakingStatus.UNKNOWN
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
  val leakingStatus: LeakingStatus,
  val leakingStatusReason: String,
  /**
   * The minimum number of bytes which would be freed if all references to this object were
   * released. Not null only if the retained heap size was computed AND [leakingStatus] is
   * equal to [LeakingStatus.UNKNOWN] or [LeakingStatus.LEAKING].
   */
  val retainedHeapByteSize: Int?,
  /**
   * The minimum number of objects which would be unreachable if all references to this object were
   * released. Not null only if the retained heap size was computed AND [leakingStatus] is
   * equal to [LeakingStatus.UNKNOWN] or [LeakingStatus.LEAKING].
   */
  val retainedObjectCount: Int?
) : Serializable {

  /**
   * Returns {@link #className} without the package, ie stripped of any string content before the
   * last period (included).
   */
  val classSimpleName: String get() = className.lastSegment('.')

  val typeName
    get() = type.name.toLowerCase(Locale.US)

  override fun toString(): String {
    val firstLinePrefix = ""
    val additionalLinesPrefix = "$ZERO_WIDTH_SPACE  "
    return toString(firstLinePrefix, additionalLinesPrefix, true)
  }

  internal fun toString(
    firstLinePrefix: String,
    additionalLinesPrefix: String,
    showLeakingStatus: Boolean,
    typeName: String = this.typeName
  ): String {
    val leakStatus = when (leakingStatus) {
      UNKNOWN -> "UNKNOWN"
      NOT_LEAKING -> "NO ($leakingStatusReason)"
      LEAKING -> "YES ($leakingStatusReason)"
    }

    var result = ""
    result += "$firstLinePrefix$className $typeName"
    if (showLeakingStatus) {
      result += "\n${additionalLinesPrefix}Leaking: $leakStatus"
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

  enum class LeakingStatus {
    /** The object was needed and therefore expected to be reachable. */
    NOT_LEAKING,

    /** The object was no longer needed and therefore expected to be unreachable. */
    LEAKING,

    /** No decision can be made about the provided object. */
    UNKNOWN;
  }

  companion object {
    private const val serialVersionUID = -3616216391305196341L

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