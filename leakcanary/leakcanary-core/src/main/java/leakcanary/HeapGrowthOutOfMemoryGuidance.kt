package leakcanary

import leakcanary.HeapLimitSource.AndroidApp
import leakcanary.HeapLimitSource.AndroidInstrumentationTest
import leakcanary.HeapLimitSource.Jvm
import shark.outOfMemoryOrNull

private const val LARGE_HEAP_DOC_URL =
  "https://developer.android.com/guide/topics/manifest/application-element#largeHeap"
private const val SHARK_CLI_DOC_URL = "https://square.github.io/leakcanary/shark/#shark-cli"

private const val BYTES_PER_MB = 1024 * 1024

/**
 * Where the memory limit of the process running heap growth detection comes from, which is what
 * decides how that limit can be raised.
 */
internal sealed class HeapLimitSource {

  /** A JVM, where the limit comes from the -Xmx flag. */
  object Jvm : HeapLimitSource()

  /**
   * An Android process we know nothing more about than that its limit comes from the largeHeap
   * manifest flag, either because it isn't an instrumentation test process or because the androidx
   * test APIs that would tell us about one aren't on the classpath.
   */
  object AndroidApp : HeapLimitSource()

  /**
   * An Android instrumentation test process. Such a process is created from the `ApplicationInfo` of
   * the app under test, so `android:largeHeap="true"` raises the limit only when the app under test
   * declares it: declaring it in `src/androidTest/AndroidManifest.xml`, which is where it ends up in
   * the manifest of the test apk, does nothing at all.
   */
  class AndroidInstrumentationTest(
    val appUnderTestPackageName: String,
    val largeHeapEnabled: Boolean,
    val largeHeapEnabledOnTestApkOnly: Boolean,
    val largeHeapMaxMemoryMb: Int
  ) : HeapLimitSource()
}

/**
 * The message to replace an out of memory failure of heap growth detection with: how much memory the
 * traversal was allowed to use and what can be done about it running out. Null if running out of
 * memory isn't what went wrong.
 *
 * @param heapDumpsDeleted whether the heap dumps of this run are already gone, which changes whether
 * the guidance tells the caller to keep them.
 */
internal fun heapGrowthOutOfMemoryGuidanceOrNull(
  failure: Throwable,
  heapDumpsDeleted: Boolean
): String? {
  if (failure.outOfMemoryOrNull() == null) {
    return null
  }
  return heapGrowthOutOfMemoryGuidance(
    maxMemoryMb = Runtime.getRuntime().maxMemory() / BYTES_PER_MB,
    heapLimitSource = readHeapLimitSource(),
    heapDumpsDeleted = heapDumpsDeleted
  )
}

internal fun heapGrowthOutOfMemoryGuidance(
  maxMemoryMb: Long,
  heapLimitSource: HeapLimitSource,
  heapDumpsDeleted: Boolean
): String {
  val options = mutableListOf<String>()
  when (heapLimitSource) {
    Jvm -> options += "Raise the memory limit of the JVM running this test with the -Xmx flag."
    AndroidApp -> options += "Increase the memory available to the app with " +
      "android:largeHeap=\"true\": $LARGE_HEAP_DOC_URL"
    is AndroidInstrumentationTest -> if (!heapLimitSource.largeHeapEnabled) {
      val appUnderTest = "the app under test (${heapLimitSource.appUnderTestPackageName})"
      options += "Raise that limit to ${heapLimitSource.largeHeapMaxMemoryMb} MB by " +
        if (heapLimitSource.largeHeapEnabledOnTestApkOnly) {
          "moving android:largeHeap=\"true\" to the manifest of $appUnderTest: it is set in the " +
            "manifest of the test apk, where it has no effect, because an instrumentation process " +
            "is created from the ApplicationInfo of the app under test. $LARGE_HEAP_DOC_URL"
        } else {
          "setting android:largeHeap=\"true\" in the manifest of $appUnderTest. Setting it in " +
            "src/androidTest/AndroidManifest.xml has no effect: that manifest ends up in the test " +
            "apk, and an instrumentation process is created from the ApplicationInfo of the app " +
            "under test. $LARGE_HEAP_DOC_URL"
        }
    }
  }
  options += if (heapDumpsDeleted) {
    "Keep the heap dumps (heapDumpStorageStrategy = HeapDumpStorageStrategy.KeepHeapDumps()) then " +
      "detect heap growth from your computer instead, where memory is cheaper: " +
      "shark-cli --hprof <heap dump directory> heap-growth. $SHARK_CLI_DOC_URL"
  } else {
    "Detect heap growth from your computer instead, where memory is cheaper, by running " +
      "shark-cli --hprof <heap dump directory> heap-growth on the heap dumps this run kept. " +
      SHARK_CLI_DOC_URL
  }

  val largeHeapDetail =
    if (heapLimitSource is AndroidInstrumentationTest && heapLimitSource.largeHeapEnabled) {
      ", with android:largeHeap=\"true\" already set in the manifest of the app under test " +
        "(${heapLimitSource.appUnderTestPackageName})"
    } else {
      ""
    }
  return "Not enough memory to detect heap growth: this process can use up to " +
    "$maxMemoryMb MB$largeHeapDetail. You can:" +
    options.joinToString(prefix = "\n- ", separator = "\n- ")
}

/**
 * Reflection is how this module, which has no Android dependency, gets to tell an Android
 * instrumentation test process apart from a JVM one: [AndroidInstrumentationTest] is entirely about
 * the manifest an Android process was created from, and the androidx test API that hands us the app
 * under test and the test apk is only on the classpath when running an instrumentation test.
 */
private fun readHeapLimitSource(): HeapLimitSource {
  // ART reports itself as Dalvik, which it replaced.
  val android = System.getProperty("java.vm.name")?.startsWith("Dalvik") == true
  if (!android) {
    return Jvm
  }
  return readAndroidInstrumentationTestOrNull() ?: AndroidApp
}

private fun readAndroidInstrumentationTestOrNull(): AndroidInstrumentationTest? {
  return try {
    val instrumentation = Class.forName("androidx.test.platform.app.InstrumentationRegistry")
      .getMethod("getInstrumentation")
      .invoke(null)
    val instrumentationClass = Class.forName("android.app.Instrumentation")
    val appUnderTestContext = instrumentationClass.getMethod("getTargetContext")
      .invoke(instrumentation)
    val testApkContext = instrumentationClass.getMethod("getContext").invoke(instrumentation)

    // Methods and fields are looked up on the public API classes rather than on the runtime class of
    // each instance, which is a hidden framework implementation we would not be allowed to call.
    val contextClass = Class.forName("android.content.Context")
    val getApplicationInfo = contextClass.getMethod("getApplicationInfo")
    val applicationInfoClass = Class.forName("android.content.pm.ApplicationInfo")
    val flagsField = applicationInfoClass.getField("flags")
    val largeHeapFlag = applicationInfoClass.getField("FLAG_LARGE_HEAP").getInt(null)
    val largeHeapEnabledIn = { context: Any ->
      flagsField.getInt(getApplicationInfo.invoke(context)) and largeHeapFlag != 0
    }
    val appUnderTestLargeHeap = largeHeapEnabledIn(appUnderTestContext)

    val activityManager = contextClass.getMethod("getSystemService", String::class.java)
      // Context.ACTIVITY_SERVICE
      .invoke(appUnderTestContext, "activity")
    val largeHeapMaxMemoryMb = Class.forName("android.app.ActivityManager")
      .getMethod("getLargeMemoryClass")
      .invoke(activityManager) as Int

    AndroidInstrumentationTest(
      appUnderTestPackageName = contextClass.getMethod("getPackageName")
        .invoke(appUnderTestContext) as String,
      largeHeapEnabled = appUnderTestLargeHeap,
      largeHeapEnabledOnTestApkOnly =
        !appUnderTestLargeHeap && largeHeapEnabledIn(testApkContext),
      largeHeapMaxMemoryMb = largeHeapMaxMemoryMb
    )
  } catch (ignored: Throwable) {
    null
  }
}
