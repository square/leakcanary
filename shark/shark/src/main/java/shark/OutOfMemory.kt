package shark

/**
 * The [OutOfMemoryError] that made this throwable happen: itself if it is one, otherwise the first
 * one in its cause chain, otherwise null.
 *
 * The whole cause chain is searched rather than just the direct cause, because an [OutOfMemoryError]
 * rarely is the direct cause of an analysis that ran out of memory: Shark's hash maps catch it while
 * allocating their buffers and rethrow it wrapped in a [RuntimeException] that says which buffer
 * they failed to allocate, which is the most common way for the analysis to run out of memory.
 */
fun Throwable.outOfMemoryOrNull(): OutOfMemoryError? {
  return generateSequence(this) { it.cause }
    .filterIsInstance<OutOfMemoryError>()
    .firstOrNull()
}
