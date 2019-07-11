package leakcanary

import leakcanary.GraphObjectRecord.GraphInstanceRecord

/**
 * Inspector that automatically marks instances of the provided class names as not leaking
 * because they're app wide singletons.
 */
class AppSingletonInspector(private vararg val singletonClasses: String) : ObjectInspector {
  override fun inspect(
    graph: HprofGraph,
    reporter: ObjectReporter
  ) {
    if (reporter.objectRecord is GraphInstanceRecord) {
      reporter.objectRecord.instanceClass
          .classHierarchy
          .forEach { classRecord ->
            if (classRecord.name in singletonClasses) {
              reporter.reportNotLeaking("${classRecord.name} is an app singleton")
            }
          }
    }
  }
}