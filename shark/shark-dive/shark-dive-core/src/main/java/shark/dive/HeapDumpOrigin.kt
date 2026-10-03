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
      val build = graph.findClassByName(ANDROID_BUILD_CLASS_NAME)
      val version = graph.findClassByName(ANDROID_BUILD_VERSION_CLASS_NAME)
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

/**
 * Whether the heap dump has the device [shark.AndroidBuildMirror] is a mirror of, which is what nearly every
 * one of Shark's library leak patterns decides whether it applies by — and what
 * [shark.AndroidMetadataExtractor] reads before anything else. See [HeapDive.readMetadata].
 *
 * By the three fields it reads rather than by the class, because it reads all three with `!!`: a dump that
 * has `android.os.Build` and not its fields — a synthetic one, an Android runtime that strips them — is a
 * bare NPE from inside whatever asked, which for the reference reader is under everything Shark Dive reads.
 * What that looks like is a window that never draws a tree.
 *
 * Beside [HeapDumpOrigin] because it is the same question that class answers field by field: whether this is
 * an Android heap dump at all. One place for it, since the two callers would otherwise each have their own
 * idea of which fields make one.
 */
internal fun HeapGraph.recordsAndroidBuild(): Boolean {
  val buildClass = findClassByName(ANDROID_BUILD_CLASS_NAME) ?: return false
  val versionClass = findClassByName(ANDROID_BUILD_VERSION_CLASS_NAME) ?: return false
  return buildClass["MANUFACTURER"]?.value?.readAsJavaString() != null &&
    buildClass["ID"]?.value?.readAsJavaString() != null &&
    versionClass["SDK_INT"]?.value?.asInt != null
}

/** What every Android heap dump has and no other kind does. See [recordsAndroidBuild]. */
internal const val ANDROID_BUILD_CLASS_NAME = "android.os.Build"

internal const val ANDROID_BUILD_VERSION_CLASS_NAME = "android.os.Build\$VERSION"
