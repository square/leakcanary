package shark

object AndroidMetadataExtractor : MetadataExtractor {
  override fun extractMetadata(graph: HeapGraph): Map<String, String> {
    val build = AndroidBuildMirror.fromHeapGraph(graph)

    val leakCanaryVersion = readLeakCanaryVersion(graph)
    val processName = readProcessName(graph)

    return mapOf(
        "Build.VERSION.SDK_INT" to build.sdkInt.toString(),
        "Build.MANUFACTURER" to build.manufacturer,
        "LeakCanary version" to leakCanaryVersion,
        "App process name" to processName
    )
  }

  private fun readLeakCanaryVersion(graph: HeapGraph): String {
    val versionHolderClass = graph.findClassByName("leakcanary.internal.InternalLeakCanary")
    return versionHolderClass?.get("version")?.value?.readAsJavaString() ?: "Unknown"
  }

  private fun readProcessName(graph: HeapGraph): String {
    val activityThread = graph.findClassByName("android.app.ActivityThread")
        ?.get("sCurrentActivityThread")
        ?.valueAsInstance
    val appBindData = activityThread?.get("android.app.ActivityThread", "mBoundApplication")
        ?.valueAsInstance
    val appInfo = appBindData?.get("android.app.ActivityThread\$AppBindData", "appInfo")
        ?.valueAsInstance

    return appInfo?.get(
        "android.content.pm.ApplicationInfo", "processName"
    )?.valueAsInstance?.readAsJavaString() ?: "Unknown"
  }
}