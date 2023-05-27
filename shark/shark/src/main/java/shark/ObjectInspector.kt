package shark

/**
 * Provides LeakCanary with insights about objects (classes, instances and arrays) found in the
 * heap. [inspect] will be called for each object that LeakCanary wants to know more about.
 * The implementation can then use the provided [ObjectReporter] to provide insights for that
 * object.
 */
fun interface ObjectInspector {

  /**
   * @see [ObjectInspector]
   */
  fun inspect(reporter: ObjectReporter)
}
