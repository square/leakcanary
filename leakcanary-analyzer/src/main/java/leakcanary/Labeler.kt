package leakcanary

import leakcanary.HeapValue.ObjectReference
import leakcanary.Record.HeapDumpRecord.ObjectRecord.InstanceDumpRecord

interface Labeler {

  fun computeLabels(
    parser: HprofParser,
    node: LeakNode
  ): List<String>

  object InstanceDefaultLabeler : Labeler {
    override fun computeLabels(
      parser: HprofParser,
      node: LeakNode
    ): List<String> {
      val objectId = node.instance
      val record = parser.retrieveRecordById(objectId)
      if (record is InstanceDumpRecord) {
        val labels = mutableListOf<String>()
        val instance = parser.hydrateInstance(record)
        val className = instance.classHierarchy[0].className

        if (instance.classHierarchy.any { it.className == Thread::class.java.name }) {
          val nameField = instance.fieldValueOrNull<ObjectReference>("name")
          // Sometimes we can't find the String at the expected memory address in the heap dump.
          // See https://github.com/square/leakcanary/issues/417
          val threadName =
            if (nameField != null) parser.retrieveString(nameField) else "not available"
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
}