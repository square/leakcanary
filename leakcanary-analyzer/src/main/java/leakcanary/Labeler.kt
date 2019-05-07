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
import leakcanary.ObjectIdMetadata.CLASS
import leakcanary.ObjectIdMetadata.STRING
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
  ): List<String> {
    val labels = mutableListOf<String>()

    val objectId = node.instance
    val record = parser.retrieveRecordById(objectId)
    if (record is InstanceDumpRecord) {
      val instance = parser.hydrateInstance(record)
      instance.classHierarchy.forEachIndexed { classIndex, hydratedClass ->
        if (classIndex < instance.classHierarchy.lastIndex) {
          labels.add("Class ${hydratedClass.className}")
          if (labelStaticFields) {
            hydratedClass.staticFieldNames.forEachIndexed { index, name ->
              val valueString =
                heapValueAsString(parser, hydratedClass.record.staticFields[index].value)
              labels.add("  static $name=$valueString")
            }
          }
          hydratedClass.fieldNames.forEachIndexed { index, name ->
            val heapValue = instance.fieldValues[classIndex][index]
            val valueString = heapValueAsString(parser, heapValue)
            labels.add("  $name=$valueString")
          }
        }
      }
    }
    return labels
  }

  private fun heapValueAsString(
    parser: HprofParser,
    heapValue: HeapValue
  ): String {
    return when (heapValue) {
      is ObjectReference -> {
        if (heapValue.isNull) {
          "null"
        } else {
          when (parser.objectIdMetadata(heapValue.value)) {
            STRING -> "\"${parser.retrieveStringById(heapValue.value)}\""
            CLASS -> parser.className(heapValue.value)
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
  ): List<String> = with(parser) {
    val record = node.instance.objectRecord
    if (record is InstanceDumpRecord) {
      val labels = mutableListOf<String>()
      val instance = record.hydratedInstance
      val className = instance.classHierarchy[0].className

      if (instance.classHierarchy.any { it.className == Thread::class.java.name }) {
        // Sometimes we can't find the String at the expected memory address in the heap dump.
        // See https://github.com/square/leakcanary/issues/417
        val threadName = instance["name"].reference.stringOrNull ?: "not available"
        labels.add("Thread name: '$threadName'")
      } else if (className.matches(HeapAnalyzer.ANONYMOUS_CLASS_NAME_PATTERN_REGEX)) {
        val parentClassName = instance.classHierarchy[1].className
        if (parentClassName == "java.lang.Object") {
          try {
            // This is an anonymous class implementing an interface. The API does not give access
            // to the interfaces implemented by the class. We check if it's in the class path and
            // use that instead.
            val actualClass = Class.forName(instance.classHierarchy[0].className)
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
          labels.add("Anonymous subclass of $parentClassName")
        }
      }
      return labels
    } else {
      return emptyList()
    }
  }
}