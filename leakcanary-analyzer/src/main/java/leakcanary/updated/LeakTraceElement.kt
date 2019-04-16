package leakcanary.updated

import leakcanary.Exclusion
import leakcanary.HeapValue
import leakcanary.HydratedInstance
import leakcanary.internal.lastSegment
import java.io.Serializable

data class LeakTraceElement(
  /**
   * Information about the reference that points to the next [LeakTraceElement] in the leak
   * chain. Null if this is the last element in the leak trace, ie the leaking object.
   */
  val reference: LeakReference?,

  val holder: Holder,

  val instance: HydratedInstance,

  /** Additional information, may be null.  */
  val extra: String?,

  /** If not null, there was no path that could exclude this element.  */
  val exclusion: Exclusion?,

  /** List of all fields (member and static) for that object.  */
  val fieldReferences: List<LeakReference>
) : Serializable {

  val className: String = instance.classHierarchy[0].className

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

  fun <T : HeapValue> getFieldReferenceValue(referenceName: String): T? {
    return instance.fieldValueOrNull(referenceName)
  }

  /** @see [isInstanceOf][] */
  fun isInstanceOf(expectedClass: Class<out Any>): Boolean {
    return isInstanceOf(expectedClass.name)
  }

  /**
   * Returns true if this element is an instance of the provided class name, false otherwise.
   */
  fun isInstanceOf(expectedClassName: String): Boolean {
    return instance.classHierarchy.any { it.className == expectedClassName }
  }

  /**
   * Returns {@link #className} without the package.
   */
  fun getSimpleClassName(): String {
    return className.lastSegment('.')
  }

}