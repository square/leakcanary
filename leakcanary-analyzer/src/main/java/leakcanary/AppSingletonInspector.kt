package leakcanary

import leakcanary.GraphObjectRecord.GraphInstanceRecord

/**
 * Inspector that automatically marks instances of the provided class names as not leaking
 * because they're app wide singletons.
 */
class AppSingletonInspector(private vararg val singletonClasses: String) : LeakTraceInspector {
  override fun inspect(
    graph: HprofGraph,
    leakTrace: List<LeakTraceElementReporter>
  ) {
    leakTrace.forEach { reporter ->
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
}