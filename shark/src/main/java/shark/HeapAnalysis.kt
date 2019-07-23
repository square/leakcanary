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
) : HeapAnalysis()

/**
 * The result of a successful heap analysis performed by [HeapAnalyzer].
 */
data class HeapAnalysisSuccess(
  override val heapDumpFile: File,
  override val createdAtTimeMillis: Long,
  override val analysisDurationMillis: Long,
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
}