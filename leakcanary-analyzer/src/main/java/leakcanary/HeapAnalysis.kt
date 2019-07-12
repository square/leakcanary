package leakcanary

import leakcanary.Exclusion.Status.WONT_FIX_LEAK
import leakcanary.internal.createSHA1Hash
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
  val leakingInstances: List<LeakingInstance>
) : HeapAnalysis()

data class LeakingInstance(
  /**
   * Class name of the leaking instance.
   * The class name format is the same as what would be returned by [Class.getName].
   */
  val instanceClassName: String,
  /**
   * True if the only path to the leaking reference is through excluded references. Usually, that
   * means you can safely ignore this report.
   */
  val exclusionStatus: Exclusion.Status?,
  /**
   * Shortest path to GC roots for the leaking instance.
   */
  val leakTrace: LeakTrace,
  /**
   * The number of bytes which would be freed if all references to the leaking object were
   * released. Null if the retained heap size was not computed.
   */
  val retainedHeapSize: Int?

) : Serializable {

  val groupHash = createGroupHash()

  val instanceClassSimpleName: String
    get() {
      val separator = instanceClassName.lastIndexOf('.')
      return if (separator == -1) instanceClassName else instanceClassName.substring(separator + 1)
    }

  private fun createGroupHash(): String {
    val uniqueString = if (exclusionStatus == WONT_FIX_LEAK) {
      leakTrace.firstElementExclusion.matching
    } else {
      leakTrace.leakCauses
          .joinToString(separator = "") { element ->
            val referenceName = element.reference!!.groupingName
            element.className + referenceName
          }
    }
    return uniqueString.createSHA1Hash()
  }
}

fun HeapAnalysis.leakingInstances(): List<LeakingInstance> {
  return when (this) {
    is HeapAnalysisFailure -> emptyList()
    is HeapAnalysisSuccess -> leakingInstances
  }
}

fun HeapAnalysis.applicationLeaks(): List<LeakingInstance> =
  leakingInstances().filter { it.exclusionStatus == null }