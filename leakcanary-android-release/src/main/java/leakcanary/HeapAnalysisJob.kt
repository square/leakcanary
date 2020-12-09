package leakcanary

import shark.HeapAnalysis

/**
 * A [HeapAnalysisJob] represents a single prepared request to analyze the heap. It cannot be
 * executed twice.
 */
interface HeapAnalysisJob {

  /**
   * In memory store, mutable and thread safe. This allows passing data to interceptors.
   */
  val context: JobContext

  /**
   * true if [execute] has been called. It is an
   * error to call [execute] more than once.
   */
  val executed: Boolean

  /**
   * true of [cancel] has been called or if an [HeapAnalysisInterceptor] has returned
   * [Result.Canceled] from [HeapAnalysisInterceptor.intercept].
   */
  val canceled: Boolean

  /**
   * Starts the analysis job immediately, and blocks until a result is available.
   *
   * @return Either [Result.Done] if the analysis was attempted or [Result.Canceled]
   */
  fun execute(): Result

  /** Cancels the job, if possible. Jobs that are already complete cannot be canceled. */
  fun cancel(cancelReason: String)

  sealed class Result {

    data class Done(
      val analysis: HeapAnalysis,
      /**
       * The time spent stripping the hprof of any data if [HeapAnalysisConfig.stripHeapDump] is
       * true, null otherwise.
       */
      val stripHeapDumpDurationMillis: Long? = null
      ) : Result()

    data class Canceled(val cancelReason: String) : Result()
  }
}