package leakcanary

interface ObjectInspector {
  fun inspect(
    graph: HprofGraph,
    reporter: ObjectReporter
  )
}