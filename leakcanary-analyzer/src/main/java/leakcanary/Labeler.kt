package leakcanary

import leakcanary.GraphObjectRecord.GraphInstanceRecord
import leakcanary.HeapValue.BooleanValue
import leakcanary.HeapValue.ByteValue
import leakcanary.HeapValue.CharValue
import leakcanary.HeapValue.DoubleValue
import leakcanary.HeapValue.FloatValue
import leakcanary.HeapValue.IntValue
import leakcanary.HeapValue.LongValue
import leakcanary.HeapValue.ObjectReference
import leakcanary.HeapValue.ShortValue

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
  ): List<String> {
    val heapValue = GraphHeapValue(HprofGraph(parser), ObjectReference(node.instance))
    val labels = mutableListOf<String>()

    val record = heapValue.readObjectRecord()
    if (record is GraphInstanceRecord) {
      var classRecord = record.readClass()

      val instanceField = record.readFields()

      var classIndex = 0
      while (classRecord.name != "java.lang.Object") {
        val fields = instanceField[classIndex]

        labels.add("Class ${classRecord.name}")
        if (labelStaticFields) {

          for (field in classRecord.staticFields) {
            val valueString = heapValueAsString(field.value)
            labels.add("  static ${field.name}=$valueString")
          }
        }

        for (field in fields) {
          labels.add("  ${field.name}=${heapValueAsString(field.value)}")
        }
        classIndex++
        classRecord = classRecord.readSuperClass()!!
      }
    }
    return labels
  }

  private fun heapValueAsString(heapValue: GraphHeapValue): String {
    return when (val actualValue = heapValue.actual) {
      is ObjectReference -> {
        if (heapValue.isNullReference) {
          "null"
        } else {
          when {
            heapValue.referencesJavaString -> "\"${heapValue.readAsJavaString()!!}\""
            heapValue.referencesClass -> heapValue.readObjectRecord()!!.asClass!!.name
            else -> "@${actualValue.value}"
          }
        }
      }
      is BooleanValue -> actualValue.value.toString()
      is CharValue -> actualValue.value.toString()
      is FloatValue -> actualValue.value.toString()
      is DoubleValue -> actualValue.value.toString()
      is ByteValue -> actualValue.value.toString()
      is ShortValue -> actualValue.value.toString()
      is IntValue -> actualValue.value.toString()
      is LongValue -> actualValue.value.toString()
    }
  }
}

object InstanceDefaultLabeler : Labeler {
  override fun invoke(
    parser: HprofParser,
    node: LeakNode
  ): List<String> {
    val heapValue = GraphHeapValue(HprofGraph(parser), ObjectReference(node.instance))

    val record = heapValue.readObjectRecord()!!
    if (record is GraphInstanceRecord) {
      val labels = mutableListOf<String>()
      if (record instanceOf Thread::class) {
        val threadName = record["name"]!!.value.readAsJavaString()
        labels.add("Thread name: '$threadName'")
      } else {
        val classRecord = record.readClass()
        if (classRecord.name.matches(HeapAnalyzer.ANONYMOUS_CLASS_NAME_PATTERN_REGEX)) {
          val parentClassRecord = classRecord.readSuperClass()!!
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