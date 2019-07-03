package leakcanary

interface LeakTraceInspector {
  fun inspect(
    graph: HprofGraph,
    leakTrace: List<LeakTraceElementReporter>
  )
}