package leakcanary.updated

import leakcanary.Exclusion
import leakcanary.HeapValue
import leakcanary.internal.lastSegment
import java.io.Serializable

data class LeakTraceElement(
  /**
   * Information about the reference that points to the next [LeakTraceElement] in the leak
   * chain. Null if this is the last element in the leak trace, ie the leaking object.
   */
  val reference: LeakReference?,

  val holder: Holder,

  /**
   * Class hierarchy for that object. The first element is [.className]. [Object]
   * is excluded. There is always at least one element.
   */
  val classHierarchy: List<String>,

  /** Additional information, may be null.  */
  val extra: String?,

  /** If not null, there was no path that could exclude this element.  */
  val exclusion: Exclusion?,

  /** List of all fields (member and static) for that object.  */
  val fieldReferences: List<LeakReference>
) : Serializable {

  val className: String = classHierarchy[0]

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
    @Suppress("UNCHECKED_CAST")
    return fieldReferences.find { fieldReference -> fieldReference.name == referenceName }
        ?.value as T
  }

  /** @see [isInstanceOf][] */
  fun isInstanceOf(expectedClass: Class<out Any>): Boolean {
    return isInstanceOf(expectedClass.name)
  }

  /**
   * Returns true if this element is an instance of the provided class name, false otherwise.
   */
  fun isInstanceOf(expectedClassName: String): Boolean {
    return classHierarchy.any { it == expectedClassName }
  }

  /**
   * Returns {@link #className} without the package.
   */
  fun getSimpleClassName(): String {
    return className.lastSegment('.')
  }

}