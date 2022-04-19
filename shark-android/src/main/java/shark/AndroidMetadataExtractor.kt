package shark

import shark.GcRoot.ThreadObject
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.internal.friendly.mapNativeSizes

object AndroidMetadataExtractor : MetadataExtractor {
  override fun extractMetadata(graph: HeapGraph): Map<String, String> {
    val metadata = mutableMapOf<String, String>()

    val build = AndroidBuildMirror.fromHeapGraph(graph)
    metadata["Build.VERSION.SDK_INT"] = build.sdkInt.toString()
    metadata["Build.MANUFACTURER"] = build.manufacturer
    metadata["LeakCanary version"] = readLeakCanaryVersion(graph)
    metadata["App process name"] = readProcessName(graph)
    metadata["Class count"] = graph.classCount.toString()
    metadata["Instance count"] = graph.instanceCount.toString()
    metadata["Primitive array count"] = graph.primitiveArrayCount.toString()
    metadata["Object array count"] = graph.objectArrayCount.toString()
    metadata["Thread count"] = readThreadCount(graph).toString()
    metadata["Heap total bytes"] = readHeapTotalBytes(graph).toString()
    metadata.putBitmaps(graph)
    metadata.putDbLabels(graph)

    return metadata
  }

  private fun readHeapTotalBytes(graph: HeapGraph): Int {
    return graph.objects.sumBy { heapObject ->
      when(heapObject) {
        is HeapInstance -> {
          heapObject.byteSize
        }
        // This is probably way off but is a cheap approximation.
        is HeapClass -> heapObject.recordSize
        is HeapObjectArray -> heapObject.byteSize
        is HeapPrimitiveArray -> heapObject.byteSize
      }
    }
  }

  private fun MutableMap<String, String>.putBitmaps(
    graph: HeapGraph,
  ) {

    val bitmapClass = graph.findClassByName("android.graphics.Bitmap") ?: return

    val maxDisplayPixels =
      graph.findClassByName("android.util.DisplayMetrics")?.directInstances?.map { instance ->
        val width = instance["android.util.DisplayMetrics", "widthPixels"]?.value?.asInt ?: 0
        val height = instance["android.util.DisplayMetrics", "heightPixels"]?.value?.asInt ?: 0
        width * height
      }?.max() ?: 0

    val maxDisplayPixelsWithThreshold = (maxDisplayPixels * 1.1).toInt()

    val sizeMap = graph.mapNativeSizes()

    var sizeSum = 0
    var count = 0
    var largeBitmapCount = 0
    var largeBitmapSizeSum = 0
    bitmapClass.instances.forEach { bitmap ->
      val width = bitmap["android.graphics.Bitmap", "mWidth"]?.value?.asInt ?: 0
      val height = bitmap["android.graphics.Bitmap", "mHeight"]?.value?.asInt ?: 0
      val size = sizeMap[bitmap.objectId] ?: 0

      count++
      sizeSum += size
      if (maxDisplayPixelsWithThreshold > 0 && width * height > maxDisplayPixelsWithThreshold) {
        largeBitmapCount++
        largeBitmapSizeSum += size
      }
    }
    this["Bitmap count"] = count.toString()
    this["Bitmap total bytes"] = sizeSum.toString()
    this["Large bitmap count"] = largeBitmapCount.toString()
    this["Large bitmap total bytes"] = largeBitmapSizeSum.toString()
  }

  private fun readThreadCount(graph: HeapGraph): Int {
    return graph.gcRoots.filterIsInstance<ThreadObject>().map { it.id }.toSet().size
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

  private fun MutableMap<String, String>.putDbLabels(graph: HeapGraph) {
    val dbClass = graph.findClassByName("android.database.sqlite.SQLiteDatabase") ?: return

    val openDbLabels = dbClass.instances.mapNotNull { instance ->
      val config =
        instance["android.database.sqlite.SQLiteDatabase", "mConfigurationLocked"]?.valueAsInstance
          ?: return@mapNotNull null
      val label =
        config["android.database.sqlite.SQLiteDatabaseConfiguration", "label"]?.value?.readAsJavaString()
          ?: return@mapNotNull null
      val open =
        instance["android.database.sqlite.SQLiteDatabase", "mConnectionPoolLocked"]?.value?.isNonNullReference
          ?: return@mapNotNull null
      label to open
    }

    openDbLabels.forEachIndexed { index, (label, open) ->
      this["Db ${index + 1}"] = (if (open) "open " else "closed ") + label
    }
  }
}
