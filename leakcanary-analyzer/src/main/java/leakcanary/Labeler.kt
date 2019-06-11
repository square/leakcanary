package leakcanary

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

typealias Labeler = (HprofParser, LeakNode) -> List<String>

/**
 * Optional labeler that adds a label for each field of each instance in the leak trace.
 * This can make the leak analysis large, sometimes too big to be deserialized from the Sqlite db,
 * to this labeler is not added by default.
 */
class AllFieldsLabeler(
  private val labelStaticFields: Boolean = false
) : Labeler {
  override fun invoke(
    parser: HprofParser,
    node: LeakNode
  ): List<String> = with(HprofGraph(parser)) {
    val labels = mutableListOf<String>()

    val record = ObjectReference(node.instance).record
    if (record is InstanceDumpRecord) {
      var classRecord = record.classRecord

      val allFields = record.fields

      var classIndex = 0
      while (classRecord.name != "java.lang.Object") {
        val fields = allFields[classIndex]

        labels.add("Class ${classRecord.name}")
        if (labelStaticFields) {
          classRecord.staticFieldNames.forEachIndexed { index, name ->
            val valueString = heapValueAsString(classRecord.staticFields[index].value)
            labels.add("  static $name=$valueString")
          }
        }

        for ((name, value) in fields) {
          labels.add("  $name=${heapValueAsString(value)}")
        }
        classIndex++
        classRecord = classRecord.superClassRecord!!
      }
    }
    return labels
  }

  private fun HprofGraph.heapValueAsString(heapValue: HeapValue): String {
    return when (heapValue) {
      is ObjectReference -> {
        if (heapValue.isNull) {
          "null"
        } else {
          when {
            heapValue.isString -> "\"${heapValue.record.string}\""
            heapValue.isClass -> (heapValue.record as ClassDumpRecord).name
            else -> "@${heapValue.value}"
          }
        }
      }
      is BooleanValue -> heapValue.value.toString()
      is CharValue -> heapValue.value.toString()
      is FloatValue -> heapValue.value.toString()
      is DoubleValue -> heapValue.value.toString()
      is ByteValue -> heapValue.value.toString()
      is ShortValue -> heapValue.value.toString()
      is IntValue -> heapValue.value.toString()
      is LongValue -> heapValue.value.toString()
    }
  }
}

object InstanceDefaultLabeler : Labeler {
  override fun invoke(
    parser: HprofParser,
    node: LeakNode
  ): List<String> = with(HprofGraph(parser)) {
    val record = ObjectReference(node.instance).record
    if (record is InstanceDumpRecord) {
      val labels = mutableListOf<String>()
      if (record instanceOf Thread::class) {
        // Sometimes we can't find the String at the expected memory address in the heap dump.
        // See https://github.com/square/leakcanary/issues/417
        val threadName = record["name"].record.string ?: "not available"
        labels.add("Thread name: '$threadName'")
      } else {
        val classRecord = record.classRecord
        if (classRecord.name.matches(HeapAnalyzer.ANONYMOUS_CLASS_NAME_PATTERN_REGEX)) {
          val parentClassRecord = classRecord.superClassRecord!!
          if (parentClassRecord.name == "java.lang.Object") {
            try {
              // This is an anonymous class implementing an interface. The API does not give access
              // to the interfaces implemented by the class. We check if it's in the class path and
              // use that instead.
              val actualClass = Class.forName(classRecord.name)
              val interfaces = actualClass.interfaces
              labels.add(
                  if (interfaces.isNotEmpty()) {
                    val implementedInterface = interfaces[0]
                    "Anonymous class implementing ${implementedInterface.name}"
                  } else {
                    "Anonymous subclass of java.lang.Object"
                  }
              )
            } catch (ignored: ClassNotFoundException) {
            }
          } else {
            // Makes it easier to figure out which anonymous class we're looking at.
            labels.add("Anonymous subclass of ${parentClassRecord.name}")
          }
        }
      }
      return labels
    } else {
      return emptyList()
    }
  }
}