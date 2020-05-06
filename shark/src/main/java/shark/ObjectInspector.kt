package shark

import shark.ObjectInspector.Companion.invoke

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

    val obfuscationException = IllegalStateException(
        """
The heap dump may contain obfuscated class names. If that's the case then the heap analysis 
is unable to proceed without a mapping file to deobfuscate class names. You can turn off
obfuscation or run LeakCanary on obfuscated builds by following the instructions at 
https://square.github.io/leakcanary/recipes/#using-leakcanary-with-obfuscated-apps
        """
    )
  }
}