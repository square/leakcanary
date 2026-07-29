package shark

/**
 * Thrown to unwind the work in progress when a [CancelSignal] asks it to stop.
 *
 * Shark lets this through the handling that turns an exception into a heap analysis failure, so a
 * caller that cancels sees this thrown rather than a failed analysis. Work that was asked to stop
 * didn't fail, and reporting cancellation as a failure would put an analysis nobody asked to finish
 * in front of the user.
 *
 * Unchecked because it comes out of reads that make no declaration about throwing it, which is every
 * read of a heap dump.
 */
class CanceledException(
  /** Why the work stopped, as returned by [CancelSignal.cancelReasonOrNull]. */
  val cancelReason: String
) : RuntimeException(cancelReason)
