package shark

import shark.Leak.ApplicationLeak
import shark.Leak.LibraryLeak
import shark.internal.createSHA1Hash
import java.io.File
import java.io.Serializable

sealed class HeapAnalysis : Serializable {
  abstract val heapDumpFile: File
  abstract val createdAtTimeMillis: Long
  /** Total time spent analyzing the heap.  */
  abstract val analysisDurationMillis: Long
}

data class HeapAnalysisFailure(
  override val heapDumpFile: File,
  override val createdAtTimeMillis: Long,
  override val analysisDurationMillis: Long,
  val exception: HeapAnalysisException
) : HeapAnalysis()

data class HeapAnalysisSuccess(
  override val heapDumpFile: File,
  override val createdAtTimeMillis: Long,
  override val analysisDurationMillis: Long,
  val applicationLeaks: List<ApplicationLeak>,
  val libraryLeaks: List<LibraryLeak>
) : HeapAnalysis() {
  val allLeaks: List<Leak>
    get() = applicationLeaks + libraryLeaks
}

sealed class Leak : Serializable {
  /**
   * Class name of the leaking instance.
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
  abstract val retainedHeapSize: Int?

  val groupHash
    get() = createGroupHash()

  val classSimpleName: String
    get() {
      val separator = className.lastIndexOf('.')
      return if (separator == -1) className else className.substring(separator + 1)
    }

  abstract fun createGroupHash(): String

  data class LibraryLeak(
    override val className: String,
    override val leakTrace: LeakTrace,
    override val retainedHeapSize: Int?,
    val pattern: ReferencePattern,
    val description: String
  ) : Leak() {
    override fun createGroupHash() = pattern.toString().createSHA1Hash()
  }

  data class ApplicationLeak(
    override val className: String,
    override val leakTrace: LeakTrace,
    override val retainedHeapSize: Int?
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
}