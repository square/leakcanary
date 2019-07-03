package leakcanary

import leakcanary.internal.lastSegment
import java.io.Serializable

data class LeakTraceElement(
  /**
   * Information about the reference that points to the next [LeakTraceElement] in the leak
   * chain. Null if this is the last element in the leak trace, ie the leaking object.
   */
  val reference: LeakReference?,

  val holder: Holder,

  val className: String,

  /** If not null, there was no path that could exclude this element.  */
  val exclusion: ExclusionDescription?,

  /**
   * Ordered labels that were computed during analysis. A label provides
   * extra information that helps understand the leak trace element.
   */
  val labels: List<String>,
  val leakStatusAndReason: LeakNodeStatusAndReason
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