package shark

import shark.LeakTraceReference.ReferenceType.ARRAY_ENTRY
import shark.LeakTraceReference.ReferenceType.INSTANCE_FIELD
import shark.LeakTraceReference.ReferenceType.LOCAL
import shark.LeakTraceReference.ReferenceType.STATIC_FIELD
import shark.internal.lastSegment
import java.io.Serializable

/**
 * A [LeakTraceReference] represents and origin [LeakTraceObject] and either a reference from that
 * object to the [LeakTraceObject] in the next [LeakTraceReference] in [LeakTrace.referencePath],
 * or to [LeakTrace.leakingObject] if this is the last [LeakTraceReference] in
 * [LeakTrace.referencePath].
 */
data class LeakTraceReference(
  val originObject: LeakTraceObject,

  val referenceType: ReferenceType,

  val owningClassName: String,

  val referenceName: String

) : Serializable {

  enum class ReferenceType {
    INSTANCE_FIELD,
    STATIC_FIELD,
    LOCAL,
    ARRAY_ENTRY
  }

  /**
   * Returns {@link #className} without the package, ie stripped of any string content before the
   * last period (included).
   */
  val owningClassSimpleName: String get() = owningClassName.lastSegment('.')


  val referenceDisplayName: String
    get() {
      return when (referenceType) {
        ARRAY_ENTRY -> "[$referenceName]"
        STATIC_FIELD, INSTANCE_FIELD -> referenceName
        LOCAL -> "<Java Local>"
      }
    }

  val referenceGenericName: String
    get() {
      return when (referenceType) {
        // The specific array index in a leak rarely matters, this improves grouping.
        ARRAY_ENTRY -> "[x]"
        STATIC_FIELD, INSTANCE_FIELD -> referenceName
        LOCAL -> "<Java Local>"
      }
    }

  companion object {
    private const val serialVersionUID = 1L
  }

}