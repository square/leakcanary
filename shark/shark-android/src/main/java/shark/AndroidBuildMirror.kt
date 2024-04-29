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
  val sdkInt: Int,

  /**
   * Value of android.os.Build.ID
   */
  val id: String
) {
  companion object {
    /**
     * @see AndroidBuildMirror
     */
    fun fromHeapGraph(graph: HeapGraph): AndroidBuildMirror {
      return graph.context.getOrPut(AndroidBuildMirror::class.java.name) {
        // checkNotNull for more helpful message than NPE if this isn't an Android hprof.
        // Not doing checkNotNull for other classes, if android.os.Build is there it's definitely
        // an Android heap dump.
        val buildClass = checkNotNull(graph.findClassByName("android.os.Build")) {
          "android.os.Build class missing from heap dump, is this an Android heap dump?"
        }
        val versionClass = graph.findClassByName("android.os.Build\$VERSION")!!
        val manufacturer = buildClass["MANUFACTURER"]!!.value.readAsJavaString()!!
        val sdkInt = versionClass["SDK_INT"]!!.value.asInt!!
        val id = buildClass["ID"]!!.value.readAsJavaString()!!
        AndroidBuildMirror(manufacturer, sdkInt, id)
      }
    }

    fun applyIf(patternApplies: AndroidBuildMirror.() -> Boolean): (HeapGraph) -> Boolean {
      return { graph ->
        fromHeapGraph(graph)
          .patternApplies()
      }
    }
  }
}
