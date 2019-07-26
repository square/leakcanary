package shark

import shark.internal.lastSegment
import java.io.Serializable

data class LeakTraceElement(
  /**
   * Information about the reference that points to the next [LeakTraceElement] in
   * [LeakTrace.elements]. Null if this is the last element in the leak trace, ie the leaking
   * object.
   */
  val reference: LeakReference?,

  val holder: Holder,

  val className: String,

  /**
   * Labels that were computed during analysis. A label provides extra information that helps
   * understand the leak trace element.
   */
  val labels: Set<String>,
  val leakStatus: LeakNodeStatus,
  val leakStatusReason: String

) : Serializable {

  /**
   * Returns {@link #className} without the package.
   */
  val classSimpleName: String get() = className.lastSegment('.')

  enum class Type {
    INSTANCE_FIELD,
    STATIC_FIELD,
    LOCAL,
    ARRAY_ENTRY
  }

  enum class Holder {
    OBJECT,
    CLASS,
    THREAD,
    ARRAY
  }
}