package leakcanary

import leakcanary.LeakTraceElement.Holder.ARRAY
import leakcanary.LeakTraceElement.Holder.CLASS
import leakcanary.LeakTraceElement.Holder.THREAD
import leakcanary.LeakTraceElement.Type.STATIC_FIELD
import java.io.Serializable
import java.util.Locale.US

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

  init {
    val stringFields = mutableListOf<String>()
    fieldReferences.forEach { leakReference ->
      stringFields.add(leakReference.toString())
    }
  }

  /**
   * Returns the string value of the first field reference that has the provided referenceName, or
   * null if no field reference with that name was found.
   */
  fun getFieldReferenceValue(referenceName: String): String? {
    return fieldReferences.find { fieldReference -> fieldReference.name.equals(referenceName) }
        ?.value
  }

  /** @see [isInstanceOf][] */
  fun isInstanceOf(expectedClass: Class<out Any>): Boolean {
    return isInstanceOf(expectedClass.name)
  }

  /**
   * Returns true if this element is an instance of the provided class name, false otherwise.
   */
  fun isInstanceOf(expectedClassName: String): Boolean {
    return classHierarchy.find { className -> className.equals(expectedClassName) }
        .isNullOrEmpty()
  }

  /**
   * Returns {@link #className} without the package.
   */
  fun getSimpleClassName(): String {
    val separator = className.lastIndexOf('.')
    return if (separator == -1) className else className.substring(separator + 1)
  }

  override fun toString(): String {
    return toString(false)
  }

  fun toString(maybeLeakCause: Boolean): String {
    val staticString = if (reference != null && reference.type == STATIC_FIELD) "static" else ""
    val holderString =
      if (holder == ARRAY || holder == THREAD) "${holder.name.toLowerCase(US)} " else ""
    val simpleClassName = getSimpleClassName()
    val referenceName = if (reference != null) ".${reference.displayName}" else ""
    val extraString = if (extra != null) " $extra" else ""
    val exclusionString =
      if (exclusion != null) " , matching exclusion ${exclusion.matching}" else ""
    val requiredSpaces = staticString.length + holderString.length + simpleClassName.length
    val leakString = if (maybeLeakCause) {
      "\n│                   " + " ".repeat(requiredSpaces) + "~".repeat(
          referenceName.length
      )
    } else {
      ""
    }

    return staticString + holderString + simpleClassName + referenceName + leakString + extraString + exclusionString
  }

  fun toDetailedString(): String {
    val startingStarString = "*"
    val typeString = when (holder) {
      ARRAY -> "Array of"
      CLASS -> "Class"
      else -> "Instance of"
    }
    val classNameString = " ${className}\n"
    val leakReferenceString = fieldReferences.joinToString(separator = "\n", prefix = "|   ")
    return startingStarString + typeString + classNameString + leakReferenceString
  }
}