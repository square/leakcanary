package shark

import okio.Source

interface StreamingSourceProvider {
  fun openStreamingSource(): Source
}
