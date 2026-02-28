package shark

import okio.BufferedSink

/**
 * Can open [BufferedSink] instances.
 */
fun interface StreamingSinkProvider {
  fun openStreamingSink(): BufferedSink
}
