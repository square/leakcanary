package shark

import okio.BufferedSource
import okio.Source

/**
 * Can open [Source] instances.
 */
interface StreamingSourceProvider {
  fun openStreamingSource(): BufferedSource
}
