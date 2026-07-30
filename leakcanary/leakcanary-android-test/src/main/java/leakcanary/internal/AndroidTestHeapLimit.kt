package leakcanary.internal

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.platform.app.InstrumentationRegistry

private const val LARGE_HEAP_DOC_URL =
  "https://developer.android.com/guide/topics/manifest/application-element#largeHeap"

/**
 * Reads the memory limit of this instrumentation test process from the manifests it was created from,
 * and turns that into the guidance that leakcanary-core adds to an out of memory failure of heap
 * growth detection.
 *
 * leakcanary-core has no Android dependency, so it loads this class by name and calls it through the
 * `() -> Pair<String, String?>` type it implements. Loading fails with a [NoClassDefFoundError]
 * anywhere but in an Android instrumentation test, which is what tells leakcanary-core that it isn't
 * running in one.
 */
internal object AndroidTestHeapLimit : () -> Pair<String, String?> {

  override fun invoke(): Pair<String, String?> {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    // An instrumentation process is created from the ApplicationInfo of the app under test, so the
    // manifest flags that decide the memory limit of this process are the ones of the app under test,
    // never the ones of the test apk.
    val appUnderTestContext = instrumentation.targetContext
    val activityManager =
      appUnderTestContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val largeHeapEnabled = appUnderTestContext.largeHeapEnabled
    return androidTestHeapLimit(
      appUnderTestPackageName = appUnderTestContext.packageName,
      largeHeapEnabled = largeHeapEnabled,
      largeHeapEnabledOnTestApkOnly =
        !largeHeapEnabled && instrumentation.context.largeHeapEnabled,
      largeHeapMaxMemoryMb = activityManager.largeMemoryClass
    )
  }
}

/**
 * What to add to the sentence stating how much memory this process can use, empty when there is
 * nothing to add, and how to raise that limit, null when it is already raised as far as it goes.
 */
internal fun androidTestHeapLimit(
  appUnderTestPackageName: String,
  largeHeapEnabled: Boolean,
  largeHeapEnabledOnTestApkOnly: Boolean,
  largeHeapMaxMemoryMb: Int
): Pair<String, String?> {
  val appUnderTest = "the app under test ($appUnderTestPackageName)"
  if (largeHeapEnabled) {
    return ", with android:largeHeap=\"true\" already set in the manifest of $appUnderTest" to null
  }
  val raiseHeapLimitOption = "Raise that limit to $largeHeapMaxMemoryMb MB by " +
    if (largeHeapEnabledOnTestApkOnly) {
      "moving android:largeHeap=\"true\" to the manifest of $appUnderTest: it is set in the " +
        "manifest of the test apk, where it has no effect, because an instrumentation process is " +
        "created from the ApplicationInfo of the app under test. $LARGE_HEAP_DOC_URL"
    } else {
      "setting android:largeHeap=\"true\" in the manifest of $appUnderTest. Setting it in " +
        "src/androidTest/AndroidManifest.xml has no effect: that manifest ends up in the test apk, " +
        "and an instrumentation process is created from the ApplicationInfo of the app under test. " +
        LARGE_HEAP_DOC_URL
    }
  return "" to raiseHeapLimitOption
}

private val Context.largeHeapEnabled: Boolean
  get() = (applicationInfo.flags and ApplicationInfo.FLAG_LARGE_HEAP) != 0
