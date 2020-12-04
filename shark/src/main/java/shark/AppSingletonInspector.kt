package shark

import shark.HeapObject.HeapInstance

/**
 * Inspector that automatically marks instances of the provided class names as not leaking
 * because they're app wide singletons.
 */
class AppSingletonInspector(private vararg val singletonClasses: String) : ObjectInspector {
  override fun inspect(
    reporter: ObjectReporter
  ) {
    if (reporter.heapObject is HeapInstance) {
      reporter.heapObject.instanceClass
        .classHierarchy
        .forEach { heapClass ->
          if (heapClass.name in singletonClasses) {
            reporter.notLeakingReasons += "${heapClass.name} is an app singleton"
          }
        }
    }
  }
}