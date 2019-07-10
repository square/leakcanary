package leakcanary

import kotlin.properties.Delegates.notNull

/**
 * Turns BuildFilter into exclusion filters that HeapAnalyzer understand. Since retrieving from
 * the Hprof is not free, [BuildMirror] provides a caching mechanism. Make sure to use different
 * [BuildMirror] and set of filters for every hprof parsing.
 */
class BuildMirror {

  lateinit var manufacturer: String
  var sdkInt: Int by notNull()

  fun wrapFilter(filter: BuildFilter): (HprofGraph) -> Boolean = { graph ->
    if (!::manufacturer.isInitialized) {
      val buildClass = graph.indexedClass("android.os.Build")!!
      val versionClass = graph.indexedClass("android.os.Build\$VERSION")!!
      manufacturer = buildClass["MANUFACTURER"]!!.value.readAsJavaString()!!
      sdkInt = versionClass["SDK_INT"]!!.value.asInt!!
    }
    filter(manufacturer, sdkInt)
  }
}