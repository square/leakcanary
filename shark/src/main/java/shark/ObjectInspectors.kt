/*
 * Copyright (C) 2018 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package shark

import shark.FilteringLeakingObjectFinder.LeakingObjectFilter
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import java.util.EnumSet

/**
 * A set of default [ObjectInspector]s that knows about common JDK objects.
 */
enum class ObjectInspectors : ObjectInspector {

  KEYED_WEAK_REFERENCE {

    override val leakingObjectFilter = { heapObject: HeapObject ->
      KeyedWeakReferenceFinder.findKeyedWeakReferences(heapObject.graph)
          .any { reference ->
            reference.referent.value == heapObject.objectId
          }
    }

    override fun inspect(
      reporter: ObjectReporter
    ) {
      val graph = reporter.heapObject.graph
      val references = KeyedWeakReferenceFinder.findKeyedWeakReferences(graph)

      val objectId = reporter.heapObject.objectId
      references.forEach { ref ->
        if (ref.referent.value == objectId) {
          reporter.leakingReasons += if (ref.description.isNotEmpty()) {
            "ObjectWatcher was watching this because ${ref.description}"
          } else {
            "ObjectWatcher was watching this"
          }
          reporter.labels += "key = ${ref.key}"
          if (ref.watchDurationMillis != null) {
            reporter.labels += "watchDurationMillis = ${ref.watchDurationMillis}"
          }
          if (ref.retainedDurationMillis != null) {
            reporter.labels += "retainedDurationMillis = ${ref.retainedDurationMillis}"
          }
        }
      }
    }
  },

  CLASSLOADER {
    override fun inspect(
      reporter: ObjectReporter
    ) {
      reporter.whenInstanceOf(ClassLoader::class) {
        notLeakingReasons += "A ClassLoader is never leaking"
      }
    }
  },

  CLASS {
    override fun inspect(
      reporter: ObjectReporter
    ) {
      if (reporter.heapObject is HeapClass) {
        reporter.notLeakingReasons += "a class is never leaking"
      }
    }
  },

  ANONYMOUS_CLASS {
    override fun inspect(
      reporter: ObjectReporter
    ) {
      val heapObject = reporter.heapObject
      if (heapObject is HeapInstance) {
        val instanceClass = heapObject.instanceClass
        if (instanceClass.name.matches(ANONYMOUS_CLASS_NAME_PATTERN_REGEX)) {
          val parentClassRecord = instanceClass.superclass!!
          if (parentClassRecord.name == "java.lang.Object") {
            try {
              // This is an anonymous class implementing an interface. The API does not give access
              // to the interfaces implemented by the class. We check if it's in the class path and
              // use that instead.
              val actualClass = Class.forName(instanceClass.name)
              val interfaces = actualClass.interfaces
              reporter.labels += if (interfaces.isNotEmpty()) {
                val implementedInterface = interfaces[0]
                "Anonymous class implementing ${implementedInterface.name}"
              } else {
                "Anonymous subclass of java.lang.Object"
              }
            } catch (ignored: ClassNotFoundException) {
            }
          } else {
            // Makes it easier to figure out which anonymous class we're looking at.
            reporter.labels += "Anonymous subclass of ${parentClassRecord.name}"
          }
        }
      }
    }
  },

  THREAD {
    override fun inspect(
      reporter: ObjectReporter
    ) {
      reporter.whenInstanceOf(Thread::class) { instance ->
        val threadName = instance[Thread::class, "name"]!!.value.readAsJavaString()
        labels += "Thread name: '$threadName'"
      }
    }
  };

  internal open val leakingObjectFilter: ((heapObject: HeapObject) -> Boolean)? = null

  companion object {
    private const val ANONYMOUS_CLASS_NAME_PATTERN = "^.+\\$\\d+$"
    private val ANONYMOUS_CLASS_NAME_PATTERN_REGEX = ANONYMOUS_CLASS_NAME_PATTERN.toRegex()
    /** @see ObjectInspectors */
    val jdkDefaults: List<ObjectInspector>
      get() {
        return values().toList()
      }

    /**
     * Returns a list of [LeakingObjectFilter] suitable for common JDK projects.
     */
    val jdkLeakingObjectFilters: List<LeakingObjectFilter> =
      createLeakingObjectFilters(EnumSet.allOf(ObjectInspectors::class.java))

    /**
     * Creates a list of [LeakingObjectFilter] based on the passed in [ObjectInspectors].
     */
    fun createLeakingObjectFilters(inspectors: Set<ObjectInspectors>): List<LeakingObjectFilter> =
      inspectors.mapNotNull { it.leakingObjectFilter }
          .map { filter ->
            object : LeakingObjectFilter {
              override fun isLeakingObject(heapObject: HeapObject) = filter(heapObject)
            }
          }
  }
}