package shark

import shark.internal.lastSegment
import java.io.Serializable
import java.util.Locale

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
  val leakingStatusReason: String
) : Serializable {

  /**
   * Returns {@link #className} without the package, ie stripped of any string content before the
   * last period (included).
   */
  val classSimpleName: String get() = className.lastSegment('.')

  val typeName
    get() = type.name.toLowerCase(Locale.US)

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
}