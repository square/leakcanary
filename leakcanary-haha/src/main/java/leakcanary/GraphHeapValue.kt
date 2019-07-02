package leakcanary

import leakcanary.GraphObjectRecord.GraphClassRecord
import leakcanary.GraphObjectRecord.GraphInstanceRecord
import leakcanary.GraphObjectRecord.GraphObjectArrayRecord
import leakcanary.GraphObjectRecord.GraphPrimitiveArrayRecord
import leakcanary.HeapValue.BooleanValue
import leakcanary.HeapValue.ByteValue
import leakcanary.HeapValue.CharValue
import leakcanary.HeapValue.DoubleValue
import leakcanary.HeapValue.FloatValue
import leakcanary.HeapValue.IntValue
import leakcanary.HeapValue.LongValue
import leakcanary.HeapValue.ObjectReference
import leakcanary.HeapValue.ShortValue
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ObjectArrayDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord

class GraphHeapValue(
  private val graph: HprofGraph,
  val actual: HeapValue
) {
  val asBoolean: Boolean?
    get() = if (actual is BooleanValue) actual.value else null

  val asChar: Char?
    get() = if (actual is CharValue) actual.value else null

  val asFloat: Float?
    get() = if (actual is FloatValue) actual.value else null

  val asDouble: Double?
    get() = if (actual is DoubleValue) actual.value else null

  val asByte: Byte?
    get() = if (actual is ByteValue) actual.value else null

  val asShort: Short?
    get() = if (actual is ShortValue) actual.value else null

  val asInt: Int?
    get() = if (actual is IntValue) actual.value else null

  val asLong: Long?
    get() = if (actual is LongValue) actual.value else null

  val isNullReference: Boolean
    get() = actual is ObjectReference && actual.isNull

  val referencesJavaString: Boolean
    get() = actual is ObjectReference && graph.referencesJavaString(actual)

  val referencesClass: Boolean
    get() = actual is ObjectReference && graph.referencesClass(actual)


  fun readAsJavaString(): String? {
    return readObjectRecord()?.asInstance?.readAsJavaString()
  }

  fun readObjectRecord(): GraphObjectRecord? {
    return if (actual is ObjectReference && !actual.isNull) {
      return when (val objectRecord = graph.readObjectRecord(actual.value)) {
        is ClassDumpRecord -> GraphClassRecord(graph, objectRecord)
        is InstanceDumpRecord -> GraphInstanceRecord(graph, objectRecord)
        is ObjectArrayDumpRecord -> GraphObjectArrayRecord(graph, objectRecord)
        is PrimitiveArrayDumpRecord -> GraphPrimitiveArrayRecord(objectRecord)
      }
    } else {
      null
    }
  }
}
