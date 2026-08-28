package shark.dive

import shark.HeapGraph

/**
 * The device and the process a heap dump was written by, as the dump itself records them.
 *
 * Which is what makes going back to that process possible: a heap dump of API 26 and up carries no
 * bitmap pixels unless it was asked to, and the process that wrote it is the only place they still
 * are. See [DeviceHeapDumps].
 *
 * Every field is nullable because a heap dump is not necessarily an Android one, and a stripped one has
 * the fields without the strings.
 */
class HeapDumpOrigin(
  /** `Build.VERSION.SDK_INT`, which decides where the bitmap pixels of this dump are. */
  val sdkInt: Int?,
  /**
   * `Build.FINGERPRINT`: the build, the device and the flavour it was built as, in one string. Two
   * devices of the same model running the same build share it, so it says "this build of this model"
   * rather than "this device", but that is as close as a heap dump gets.
   */
  val fingerprint: String?,
  val manufacturer: String?,
  val model: String?,
  /** The process name, which is the package name unless the app asked for another. */
  val processName: String?
) {

  /** What the dump says it came from, on one line, for a window that has to name it. */
  val description: String
    get() = listOfNotNull(
      listOfNotNull(manufacturer, model).joinToString(" ").takeIf { it.isNotEmpty() },
      sdkInt?.let { "API $it" },
      processName
    ).joinToString(" · ").ifEmpty { UNKNOWN_DESCRIPTION }

  companion object {

    /** What to say about a heap dump that records none of this, which an Android one always does. */
    const val UNKNOWN_DESCRIPTION = "unknown device"

    fun readFrom(graph: HeapGraph): HeapDumpOrigin {
      val build = graph.findClassByName("android.os.Build")
      val version = graph.findClassByName("android.os.Build\$VERSION")
      return HeapDumpOrigin(
        sdkInt = version?.get("SDK_INT")?.value?.asInt,
        fingerprint = build.readStaticString("FINGERPRINT"),
        manufacturer = build.readStaticString("MANUFACTURER"),
        model = build.readStaticString("MODEL"),
        processName = graph.readProcessName()
      )
    }

    private fun shark.HeapObject.HeapClass?.readStaticString(name: String): String? =
      this?.get(name)?.value?.readAsJavaString()

    /**
     * The process name off the `ActivityThread` of the app, which is where the framework keeps what it
     * was told it was launched as.
     */
    private fun HeapGraph.readProcessName(): String? {
      val activityThread = findClassByName("android.app.ActivityThread")
        ?.get("sCurrentActivityThread")?.valueAsInstance
      val boundApplication = activityThread
        ?.get("android.app.ActivityThread", "mBoundApplication")?.valueAsInstance
      return boundApplication?.get("android.app.ActivityThread\$AppBindData", "processName")
        ?.valueAsInstance?.readAsJavaString()
    }
  }
}
