package shark

import okio.BufferedSource
import okio.Source

/**
 * Can open [Source] instances.
 */
fun interface StreamingSourceProvider {
  fun openStreamingSource(): BufferedSource
}
