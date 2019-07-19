package leakcanary

/**
 * Provides LeakCanary with insights about objects (classes, instances and arrays) found in the
 * heap. [inspect] will be called for each object that LeakCanary wants to know more about.
 * The implementation can then use the provided [ObjectReporter] to provide insights for that
 * object.
 *
 * You can create a [ObjectInspector] from a lambda by calling [invoke].
 */
interface ObjectInspector {

  /**
   * @see [ObjectInspector]
   */
  fun inspect(
    graph: HprofGraph,
    reporter: ObjectReporter
  )

  companion object {
    /**
     * Utility function to create a [ObjectInspector] from the passed in [block] lambda instead of
     * using the anonymous `object : OnHeapAnalyzedListener` syntax.
     *
     * Usage:
     *
     * ```kotlin
     * val inspector = ObjectInspector { graph, reporter ->
     *
     * }
     * ```
     */
    inline operator fun invoke(crossinline block: (HprofGraph, ObjectReporter) -> Unit): ObjectInspector =
      object : ObjectInspector {
        override fun inspect(
          graph: HprofGraph,
          reporter: ObjectReporter
        ) {
          block(graph, reporter)
        }
      }
  }
}