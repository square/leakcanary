package shark

/**
 * Provides LeakCanary with insights about objects (classes, instances and arrays) found in the
 * heap. [inspect] will be called for each object that LeakCanary wants to know more about.
 * The implementation can then use the provided [ObjectReporter] to provide insights for that
 * object.
 *
 * This is a functional interface with which you can create a [ObjectInspector] from a lambda.
 */
fun interface ObjectInspector {

  /**
   * @see [ObjectInspector]
   */
  fun inspect(reporter: ObjectReporter)

  companion object {
    /**
     * Utility function to create a [ObjectInspector] from the passed in [block] lambda instead of
     * using the anonymous `object : OnHeapAnalyzedListener` syntax.
     *
     * Usage:
     *
     * ```kotlin
     * val inspector = ObjectInspector { reporter ->
     *
     * }
     * ```
     */
    inline operator fun invoke(crossinline block: (ObjectReporter) -> Unit): ObjectInspector =
      object : ObjectInspector {
        override fun inspect(
          reporter: ObjectReporter
        ) {
          block(reporter)
        }
      }
  }
}