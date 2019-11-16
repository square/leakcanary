package shark

import shark.internal.createSHA1Hash
import java.io.File
import java.io.Serializable

/**
 * The result of an analysis performed by [HeapAnalyzer], either a [HeapAnalysisSuccess] or a
 * [HeapAnalysisFailure]. This class is serializable however there are no guarantees of forward
 * compatibility.
 */
sealed class HeapAnalysis : Serializable {
  /**
   * The hprof file that was analyzed.
   */
  abstract val heapDumpFile: File

  /**
   * The [System.currentTimeMillis] when this [HeapAnalysis] instance was created.
   */
  abstract val createdAtTimeMillis: Long

  /**
   * Total time spent analyzing the heap.
   */
  abstract val analysisDurationMillis: Long
}

/**
 * The analysis performed by [HeapAnalyzer] did not complete successfully.
 */
data class HeapAnalysisFailure(
  override val heapDumpFile: File,
  override val createdAtTimeMillis: Long,
  override val analysisDurationMillis: Long,
  /**
   * An exception wrapping the actual exception that was thrown.
   */
  val exception: HeapAnalysisException
) : HeapAnalysis() {

  override fun toString(): String {
    return """====================================
HEAP ANALYSIS FAILED

You can report this failure at https://github.com/square/leakcanary/issues
Please provide the stacktrace, metadata and the heap dump file.
====================================
STACKTRACE

$exception====================================
METADATA

Build.VERSION.SDK_INT: ${androidSdkInt()}
Build.MANUFACTURER: ${androidManufacturer()}
LeakCanary version: ${leakCanaryVersion()}
Analysis duration: $analysisDurationMillis ms
Heap dump file path: ${heapDumpFile.absolutePath}
Heap dump timestamp: $createdAtTimeMillis
===================================="""
  }
}

/**
 * The result of a successful heap analysis performed by [HeapAnalyzer].
 */
data class HeapAnalysisSuccess(
  override val heapDumpFile: File,
  override val createdAtTimeMillis: Long,
  override val analysisDurationMillis: Long,
  val metadata: Map<String, String>,
  /**
   * The list of [ApplicationLeak] found in the heap dump by [HeapAnalyzer].
   */
  val applicationLeaks: List<ApplicationLeak>,
  /**
   * The list of [LibraryLeak] found in the heap dump by [HeapAnalyzer].
   */
  val libraryLeaks: List<LibraryLeak>
) : HeapAnalysis() {
  /**
   * The list of [Leak] found in the heap dump by [HeapAnalyzer], ie all [applicationLeaks] and
   * all [libraryLeaks] in one list.
   */
  val allLeaks: List<Leak>
    get() = applicationLeaks + libraryLeaks

  override fun toString(): String {
    return """====================================
HEAP ANALYSIS RESULT
====================================
${applicationLeaks.size} APPLICATION LEAKS

References underlined with "~~~" are likely causes.
Learn more at https://squ.re/leaks.
${if (applicationLeaks.isNotEmpty()) "\n" + applicationLeaks.joinToString(
        "\n\n"
    ) + "\n" else ""}====================================
${libraryLeaks.size} LIBRARY LEAKS

Leaks coming from the Android Framework or Google libraries.
${if (libraryLeaks.isNotEmpty()) "\n" + libraryLeaks.joinToString(
        "\n\n"
    ) + "\n" else ""}====================================
METADATA

Please include this in bug reports and Stack Overflow questions.
${if (metadata.isNotEmpty()) "\n" + metadata.map { "${it.key}: ${it.value}" }.joinToString(
        "\n"
    ) else ""}
Analysis duration: $analysisDurationMillis ms
Heap dump file path: ${heapDumpFile.absolutePath}
Heap dump timestamp: $createdAtTimeMillis
===================================="""
  }
}

/**
 * A leak found by [HeapAnalyzer], either an [ApplicationLeak] or a [LibraryLeak].
 */
sealed class Leak : Serializable {
  /**
   * Class name of the leaking object.
   * The class name format is the same as what would be returned by [Class.getName].
   */
  abstract val className: String

  /**
   * Shortest path from GC roots to the leaking object.
   */
  abstract val leakTrace: LeakTrace

  /**
   * The number of bytes which would be freed if all references to the leaking object were
   * released. Null if the retained heap size was not computed.
   */
  abstract val retainedHeapByteSize: Int?

  /**
   * A unique SHA1 hash that represents this group of leaks.
   *
   * For [ApplicationLeak] this is based on [LeakTrace.leakCauses] and for [LibraryLeak] this is
   * based on [LibraryLeak.pattern].
   */
  val groupHash
    get() = createGroupHash()

  /**
   * Returns [className] stripped of any string content before the last period (included).
   */
  val classSimpleName: String
    get() {
      val separator = className.lastIndexOf('.')
      return if (separator == -1) className else className.substring(separator + 1)
    }

  override fun toString(): String {
    return (if (retainedHeapByteSize != null) "$retainedHeapByteSize bytes retained\n" else "") +
        leakTrace
  }

  protected abstract fun createGroupHash(): String
}

/**
 * A leak found by [HeapAnalyzer], where the only path to the leaking object required going
 * through a reference matched by [pattern], as provided to a [LibraryLeakReferenceMatcher]
 * instance. This is a known leak in library code that is beyond your control.
 */
data class LibraryLeak(
  override val className: String,
  override val leakTrace: LeakTrace,
  override val retainedHeapByteSize: Int?,
  /**
   * The pattern that matched one of the references in [leakTrace], as provided to a
   * [LibraryLeakReferenceMatcher] instance.
   */
  val pattern: ReferencePattern,
  /**
   * A description that conveys what we know about this library leak.
   */
  val description: String
) : Leak() {
  override fun createGroupHash() = pattern.toString().createSHA1Hash()

  override fun toString(): String {
    return """Known leak pattern: $pattern
Description: $description
${super.toString()}
"""
  }
}

/**
 * A leak found by [HeapAnalyzer] in your application.
 */
data class ApplicationLeak(
  override val className: String,
  override val leakTrace: LeakTrace,
  override val retainedHeapByteSize: Int?
) : Leak() {
  override fun createGroupHash(): String {
    return leakTrace.leakCauses
        .joinToString(separator = "") { element ->
          val referenceName = element.reference!!.groupingName
          element.className + referenceName
        }
        .createSHA1Hash()
  }

  // Required to avoid the default toString() from data classes
  override fun toString(): String {
    return super.toString()
  }
}

private fun androidSdkInt(): Int {
  return try {
    val versionClass = Class.forName("android.os.Build\$VERSION")
    val sdkIntField = versionClass.getDeclaredField("SDK_INT")
    sdkIntField.get(null) as Int
  } catch (e: Exception) {
    -1
  }
}

private fun androidManufacturer(): String {
  return try {
    val buildClass = Class.forName("android.os.Build")
    val manufacturerField = buildClass.getDeclaredField("MANUFACTURER")
    manufacturerField.get(null) as String
  } catch (e: Exception) {
    "Unknown"
  }
}

private fun leakCanaryVersion(): String {
  return try {
    val versionHolderClass = Class.forName("leakcanary.internal.InternalLeakCanary")
    val versionField = versionHolderClass.getDeclaredField("version")
    versionField.isAccessible = true
    versionField.get(null) as String
  } catch (e: Exception) {
    "Unknown"
  }
}