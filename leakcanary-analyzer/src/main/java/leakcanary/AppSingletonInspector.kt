package leakcanary

import leakcanary.HeapObject.HeapInstance

/**
 * Inspector that automatically marks instances of the provided class names as not leaking
 * because they're app wide singletons.
 */
class AppSingletonInspector(private vararg val singletonClasses: String) : ObjectInspector {
  override fun inspect(
    graph: HeapGraph,
    reporter: ObjectReporter
  ) {
    if (reporter.objectRecord is HeapInstance) {
      reporter.objectRecord.instanceClass
          .classHierarchy
          .forEach { heapClass ->
            if (heapClass.name in singletonClasses) {
              reporter.reportNotLeaking("${heapClass.name} is an app singleton")
            }
          }
    }
  }
}