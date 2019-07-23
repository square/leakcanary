package shark

/**
 * Caches values from the android.os.Build class in the heap dump.
 * Retrieve a cached instances via [fromHeapGraph].
 */
class AndroidBuildMirror(
  /**
   * Value of android.os.Build.MANUFACTURER
   */
  val manufacturer: String,
  /**
   * Value of android.os.Build.VERSION.SDK_INT
   */
  val sdkInt: Int
) {
  companion object {
    /**
     * @see AndroidBuildMirror
     */
    fun fromHeapGraph(graph: HeapGraph): AndroidBuildMirror {
      return graph.context.getOrPut(AndroidBuildMirror::class.java.name) {
        val buildClass = graph.findClassByName("android.os.Build")!!
        val versionClass = graph.findClassByName("android.os.Build\$VERSION")!!
        val manufacturer = buildClass["MANUFACTURER"]!!.value.readAsJavaString()!!
        val sdkInt = versionClass["SDK_INT"]!!.value.asInt!!
        AndroidBuildMirror(manufacturer, sdkInt)
      }
    }
  }
}
