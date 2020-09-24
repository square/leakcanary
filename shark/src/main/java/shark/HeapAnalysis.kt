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
   * Total time spent dumping the heap.
   */
  abstract val dumpDurationMillis: Long

  /**
   * Total time spent analyzing the heap.
   */
  abstract val analysisDurationMillis: Long

  companion object {
    private const val serialVersionUID: Long = -8657286725869987172
    const val DUMP_DURATION_UNKNOWN: Long = -1
  }
}

/**
 * The analysis performed by [HeapAnalyzer] did not complete successfully.
 */
data class HeapAnalysisFailure(
  override val heapDumpFile: File,
  override val createdAtTimeMillis: Long,
  override val dumpDurationMillis: Long = DUMP_DURATION_UNKNOWN,
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

  companion object {
    private const val serialVersionUID: Long = 8483254400637792414
  }
}

/**
 * The result of a successful heap analysis performed by [HeapAnalyzer].
 */
data class HeapAnalysisSuccess(
  override val heapDumpFile: File,
  override val createdAtTimeMillis: Long,
  override val dumpDurationMillis: Long = DUMP_DURATION_UNKNOWN,
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
  val allLeaks: Sequence<Leak>
    get() = applicationLeaks.asSequence() + libraryLeaks.asSequence()

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

A Library Leak is a leak caused by a known bug in 3rd party code that you do not have control over.
See https://square.github.io/leakcanary/fundamentals-how-leakcanary-works/#4-categorizing-leaks
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
Heap dump duration: ${if (dumpDurationMillis != DUMP_DURATION_UNKNOWN) "$dumpDurationMillis ms" else "Unknown"}
===================================="""
  }

  companion object {
    private const val serialVersionUID: Long = 130453013437459642

    /**
     * If [fromV20] was serialized in LeakCanary 2.0, you must deserialize it and call this
     * method to create a usable [HeapAnalysisSuccess] instance.
     */
    fun upgradeFrom20Deserialized(fromV20: HeapAnalysisSuccess): HeapAnalysisSuccess {
      val applicationLeaks = fromV20.applicationLeaks
          .map { it.leakTraceFromV20() }
          .groupBy { it.signature }
          .values
          .map {
            ApplicationLeak(it)
          }

      val libraryLeaks = fromV20.libraryLeaks
          .map { it to it.leakTraceFromV20() }
          .groupBy { it.second.signature }
          .values
          .map { listOfPairs ->
            val libraryLeakFrom20 = listOfPairs.first()
                .first
            LibraryLeak(pattern = libraryLeakFrom20.pattern,
                description = libraryLeakFrom20.description,
                leakTraces = listOfPairs.map { it.second }
            )
          }
      return HeapAnalysisSuccess(
          heapDumpFile = fromV20.heapDumpFile,
          createdAtTimeMillis = fromV20.createdAtTimeMillis,
          analysisDurationMillis = fromV20.analysisDurationMillis,
          metadata = fromV20.metadata,
          applicationLeaks = applicationLeaks,
          libraryLeaks = libraryLeaks
      )
    }
  }
}

/**
 * A leak found by [HeapAnalyzer], either an [ApplicationLeak] or a [LibraryLeak].
 */
sealed class Leak : Serializable {

  /**
   * Group of leak traces which share the same leak signature.
   */
  abstract val leakTraces: List<LeakTrace>

  /**
   * Sum of [LeakTrace.retainedHeapByteSize] for all elements in [leakTraces].
   * Null if the retained heap size was not computed.
   */
  val totalRetainedHeapByteSize: Int?
    get() = if (leakTraces.first().retainedHeapByteSize == null) {
      null
    } else {
      leakTraces.sumBy { it.retainedHeapByteSize!! }
    }

  /**
   * Sum of [LeakTrace.retainedObjectCount] for all elements in [leakTraces].
   * Null if the retained heap size was not computed.
   */
  val totalRetainedObjectCount: Int?
    get() = if (leakTraces.first().retainedObjectCount == null) {
      null
    } else {
      leakTraces.sumBy { it.retainedObjectCount!! }
    }

  /**
   * A unique SHA1 hash that represents this group of leak traces.
   *
   * For [ApplicationLeak] this is based on [LeakTrace.signature] and for [LibraryLeak] this is
   * based on [LibraryLeak.pattern].
   */
  abstract val signature: String

  abstract val shortDescription: String

  override fun toString(): String {
    return (if (totalRetainedHeapByteSize != null) "$totalRetainedHeapByteSize bytes retained by leaking objects\n" else "") +
        (if (leakTraces.size > 1) "Displaying only 1 leak trace out of ${leakTraces.size} with the same signature\n" else "") +
        "Signature: $signature\n" +
        leakTraces.first()
  }

  companion object {
    private const val serialVersionUID: Long = -2287572510360910916
  }
}

/**
 * A leak found by [HeapAnalyzer], where the only path to the leaking object required going
 * through a reference matched by [pattern], as provided to a [LibraryLeakReferenceMatcher]
 * instance. This is a known leak in library code that is beyond your control.
 */
data class LibraryLeak(
  override val leakTraces: List<LeakTrace>,
  /**
   * The pattern that matched one of the references in each of [leakTraces], as provided to a
   * [LibraryLeakReferenceMatcher] instance.
   */
  val pattern: ReferencePattern,
  /**
   * A description that conveys what we know about this library leak.
   */
  val description: String
) : Leak() {
  override val signature: String
    get() = pattern.toString().createSHA1Hash()

  override val shortDescription: String
    get() = pattern.toString()

  override fun toString(): String {
    return """Leak pattern: $pattern
Description: $description
${super.toString()}
"""
  }

  /** This field is kept to support backward compatible deserialization. */
  private val leakTrace: LeakTrace? = null

  /** This field is kept to support backward compatible deserialization. */
  private val retainedHeapByteSize: Int? = null

  internal fun leakTraceFromV20() = leakTrace!!.fromV20(retainedHeapByteSize)

  companion object {
    private const val serialVersionUID: Long = 3943636164568681903
  }
}

/**
 * A leak found by [HeapAnalyzer] in your application.
 */
data class ApplicationLeak(
  override val leakTraces: List<LeakTrace>
) : Leak() {
  override val signature: String
    get() = leakTraces.first().signature

  override val shortDescription: String
    get() {
      val leakTrace = leakTraces.first()
      return leakTrace.suspectReferenceSubpath.firstOrNull()?.let { firstSuspectReferencePath ->
        val referenceName = firstSuspectReferencePath.referenceGenericName
        firstSuspectReferencePath.originObject.classSimpleName + "." + referenceName
      } ?: leakTrace.leakingObject.className
    }

  // Override required to avoid the default toString() from data classes
  override fun toString(): String {
    return super.toString()
  }

  /** This field is kept to support backward compatible deserialization. */
  private val leakTrace: LeakTrace? = null

  /** This field is kept to support backward compatible deserialization. */
  private val retainedHeapByteSize: Int? = null

  internal fun leakTraceFromV20() = leakTrace!!.fromV20(retainedHeapByteSize)

  companion object {
    private const val serialVersionUID: Long = 524928276700576863
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