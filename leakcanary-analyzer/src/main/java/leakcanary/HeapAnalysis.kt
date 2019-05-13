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
  val retainedInstances: List<RetainedInstance>
) : HeapAnalysis()

sealed class RetainedInstance : Serializable {
  /**
   * Key associated to the [leakcanary.KeyedWeakReference] used to detect the memory leak.
   * When analyzing a heap dump manually, search for all [leakcanary.KeyedWeakReference] instances,
   * then open the one that has its "key" field set to this value. Its "referent" field contains the
   * retained instance. Computing the shortest path to GC roots on that retained instance should
   * enable you to figure out the cause of the leak, if any.
   */
  abstract val referenceKey: String
}

data class WeakReferenceMissing(override val referenceKey: String) : RetainedInstance()

sealed class WeakReferenceFound : RetainedInstance() {
  /**
   * User defined name to help identify the retained instance.
   */
  abstract val referenceName: String
  /**
   * Class name of the retained instance.
   * The class name format is the same as what would be returned by [Class.getName].
   */
  abstract val instanceClassName: String
  /** Time from the request to watch the reference until the GC was triggered.  */
  abstract val watchDurationMillis: Long
}

data class WeakReferenceCleared(
  override val referenceKey: String,
  override val referenceName: String,
  override val instanceClassName: String,
  override val watchDurationMillis: Long
) : WeakReferenceFound()

data class NoPathToInstance(
  override val referenceKey: String,
  override val referenceName: String,
  override val instanceClassName: String,
  override val watchDurationMillis: Long
) : WeakReferenceFound()

data class LeakingInstance(
  override val referenceKey: String,
  override val referenceName: String,
  override val instanceClassName: String,
  override val watchDurationMillis: Long,
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

) : WeakReferenceFound() {

  val groupHash = createGroupHash()

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
  if (this is HeapAnalysisFailure) {
    return emptyList()
  }
  val success = this as HeapAnalysisSuccess
  return success.retainedInstances.filter { it is LeakingInstance }
      .map { it as LeakingInstance }
}

fun HeapAnalysis.applicationLeaks(): List<LeakingInstance> =
  leakingInstances().filter { it.exclusionStatus == null }