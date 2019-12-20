package shark

import shark.ReferencePathElement.ReferenceType.ARRAY_ENTRY
import shark.ReferencePathElement.ReferenceType.INSTANCE_FIELD
import shark.ReferencePathElement.ReferenceType.LOCAL
import shark.ReferencePathElement.ReferenceType.STATIC_FIELD
import java.io.Serializable

/**
 * A [ReferencePathElement] represents and origin [LeakTraceObject] and either a reference from that
 * object to the [LeakTraceObject] in the next [ReferencePathElement] in [LeakTrace.referencePath],
 * or to [LeakTrace.leakingObject] if this is the last [ReferencePathElement] in
 * [LeakTrace.referencePath].
 */
data class ReferencePathElement(
  val originObject: LeakTraceObject,

  val referenceType: ReferenceType,

  val referenceName: String

) : Serializable {

  enum class ReferenceType {
    INSTANCE_FIELD,
    STATIC_FIELD,
    LOCAL,
    ARRAY_ENTRY
  }

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