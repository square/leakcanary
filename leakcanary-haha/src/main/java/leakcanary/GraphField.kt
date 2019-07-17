package leakcanary

import leakcanary.GraphObjectRecord.GraphClassRecord
import leakcanary.GraphObjectRecord.GraphInstanceRecord
import leakcanary.GraphObjectRecord.GraphObjectArrayRecord
import leakcanary.GraphObjectRecord.GraphPrimitiveArrayRecord

class GraphField(
  val classRecord: GraphClassRecord,
  val name: String,
  val value: GraphHeapValue
) {

  val valueAsClass: GraphClassRecord?
    get() = value.asObject?.asClass

  val valueAsInstance: GraphInstanceRecord?
    get() = value.asObject?.asInstance

  val valueAsObjectArray: GraphObjectArrayRecord?
    get() = value.asObject?.asObjectArray

  val valueAsPrimitiveArray: GraphPrimitiveArrayRecord?
    get() = value.asObject?.asPrimitiveArray
}