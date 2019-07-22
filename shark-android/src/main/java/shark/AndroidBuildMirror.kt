package shark

/**
 * Caches values from the android.os.Build class in the heap dump.
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
)

val HeapGraph.androidBuildMirror: AndroidBuildMirror
  get() {
    return context.getOrPut(AndroidBuildMirror::class.java.name) {
      val buildClass = findClassByName("android.os.Build")!!
      val versionClass = findClassByName("android.os.Build\$VERSION")!!
      val manufacturer = buildClass["MANUFACTURER"]!!.value.readAsJavaString()!!
      val sdkInt = versionClass["SDK_INT"]!!.value.asInt!!
      AndroidBuildMirror(manufacturer, sdkInt)
    }
  }
