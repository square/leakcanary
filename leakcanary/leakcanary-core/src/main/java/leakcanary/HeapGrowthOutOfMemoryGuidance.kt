package leakcanary

import leakcanary.HeapLimitSource.AndroidApp
import leakcanary.HeapLimitSource.AndroidInstrumentationTest
import leakcanary.HeapLimitSource.Jvm

private const val LARGE_HEAP_DOC_URL =
  "https://developer.android.com/guide/topics/manifest/application-element#largeHeap"
private const val SHARK_CLI_DOC_URL = "https://square.github.io/leakcanary/shark/#shark-cli"

/**
 * The class of leakcanary-android-test that reads the heap limit of an Android instrumentation test
 * process, loaded by name because it can only be loaded there. See [readHeapLimitSource].
 */
private const val ANDROID_TEST_HEAP_LIMIT_CLASS_NAME =
  "leakcanary.internal.AndroidTestHeapLimit"

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
   * manifest flag, either because it isn't an instrumentation test process or because the class that
   * would tell us about one couldn't be loaded.
   */
  object AndroidApp : HeapLimitSource()

  /**
   * An Android instrumentation test process, as described by [ANDROID_TEST_HEAP_LIMIT_CLASS_NAME],
   * which reads the manifest flags this module cannot see.
   *
   * @param heapLimitDetail what to add to the sentence stating how much memory the process can use,
   * empty when there is nothing to add.
   * @param raiseHeapLimitOption how to raise the limit, null when it is already raised as far as it
   * goes.
   */
  class AndroidInstrumentationTest(
    val heapLimitDetail: String,
    val raiseHeapLimitOption: String?
  ) : HeapLimitSource()
}

/**
 * The message to replace an out of memory failure of heap growth detection with: how much memory the
 * traversal was allowed to use and what can be done about it running out. Null if running out of
 * memory isn't what went wrong.
 *
 * The whole cause chain is searched rather than just the failure itself, because an
 * [OutOfMemoryError] rarely is what a failed traversal throws: Shark's hash maps catch it while
 * allocating their buffers and rethrow it wrapped in a [RuntimeException] that says which buffer they
 * failed to allocate, which is the most common way for the traversal to run out of memory.
 *
 * @param heapDumpsDeleted whether the heap dumps of this run are already gone, which changes whether
 * the guidance tells the caller to keep them.
 */
internal fun heapGrowthOutOfMemoryGuidanceOrNull(
  failure: Throwable,
  heapDumpsDeleted: Boolean
): String? {
  val outOfMemory = generateSequence(failure) { it.cause }
    .any { it is OutOfMemoryError }
  if (!outOfMemory) {
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
  val heapLimitDetail: String
  when (heapLimitSource) {
    Jvm -> {
      heapLimitDetail = ""
      options += "Raise the memory limit of the JVM running this test with the -Xmx flag."
    }
    AndroidApp -> {
      heapLimitDetail = ""
      options += "Increase the memory available to this process with android:largeHeap=\"true\". " +
        "In an instrumentation test that has to be the manifest of the app under test: an " +
        "instrumentation process is created from the ApplicationInfo of the app under test, so " +
        "setting it in src/androidTest/AndroidManifest.xml has no effect. $LARGE_HEAP_DOC_URL"
    }
    is AndroidInstrumentationTest -> {
      heapLimitDetail = heapLimitSource.heapLimitDetail
      heapLimitSource.raiseHeapLimitOption?.let { options += it }
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

  return "Not enough memory to detect heap growth: this process can use up to " +
    "$maxMemoryMb MB$heapLimitDetail. You can:" +
    options.joinToString(prefix = "\n- ", separator = "\n- ")
}

/**
 * [AndroidInstrumentationTest] is entirely about the manifests an Android process was created from,
 * which this module cannot read: it has no Android dependency, so it loads the class that reads them
 * by name instead. That class lives in leakcanary-android-test, which is compiled against the Android
 * SDK and the androidx test APIs and therefore reads them directly, and it can only be loaded in an
 * Android instrumentation test, which is exactly when it has something to say.
 */
@Suppress("UNCHECKED_CAST")
private fun readHeapLimitSource(): HeapLimitSource {
  // ART reports itself as Dalvik, which it replaced.
  val android = System.getProperty("java.vm.name")?.startsWith("Dalvik") == true
  if (!android) {
    return Jvm
  }
  return try {
    val readHeapLimit = Class.forName(ANDROID_TEST_HEAP_LIMIT_CLASS_NAME)
      .getDeclaredField("INSTANCE")
      .get(null) as () -> Pair<String, String?>
    val (heapLimitDetail, raiseHeapLimitOption) = readHeapLimit()
    AndroidInstrumentationTest(heapLimitDetail, raiseHeapLimitOption)
  } catch (ignored: Throwable) {
    AndroidApp
  }
}
