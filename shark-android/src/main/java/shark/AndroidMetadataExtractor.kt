package shark

object AndroidMetadataExtractor : MetadataExtractor {
  override fun extractMetadata(graph: HeapGraph): Map<String, String> {
    val metadata = mutableMapOf<String, String>()

    val build = AndroidBuildMirror.fromHeapGraph(graph)
    metadata["Build.VERSION.SDK_INT"] = build.sdkInt.toString()
    metadata["Build.MANUFACTURER"] = build.manufacturer

    metadata["LeakCanary version"] = readLeakCanaryVersion(graph)

    metadata["App process name"] = readProcessName(graph)

    metadata.putOpenDbsLabels(graph)

    return metadata
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

  private fun MutableMap<String, String>.putOpenDbsLabels(graph: HeapGraph) {
    val dbClass = graph.findClassByName("android.database.sqlite.SQLiteDatabase") ?: return

    val openDbs = dbClass.instances.filter { instance ->
      instance["android.database.sqlite.SQLiteDatabase", "mConnectionPoolLocked"]?.value?.isNonNullReference
        ?: false
    }

    val openDbLabels = openDbs.mapNotNull { instance ->
      val config =
        instance["android.database.sqlite.SQLiteDatabase", "mConfigurationLocked"]?.valueAsInstance
          ?: return@mapNotNull null
      config["android.database.sqlite.SQLiteDatabaseConfiguration", "label"]?.value?.readAsJavaString()
    }

    openDbLabels.forEachIndexed { index, label ->
      this["Open Db ${index + 1}"] = label
    }
  }
}
