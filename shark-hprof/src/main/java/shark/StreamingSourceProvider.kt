package shark

import okio.Source

/**
 * Can open [Source] instances.
 */
interface StreamingSourceProvider {
  fun openStreamingSource(): Source
}
