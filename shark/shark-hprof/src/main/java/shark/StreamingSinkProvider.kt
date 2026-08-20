package shark

import okio.BufferedSink
import okio.GzipSink
import okio.buffer

/**
 * Can open [BufferedSink] instances.
 */
fun interface StreamingSinkProvider {
  fun openStreamingSink(): BufferedSink
}

/**
 * Returns a [StreamingSinkProvider] that gzips what's written to it before handing it to a sink this
 * one opens. The gzip trailer is written when the sink is closed, so what's written to it has to be
 * closed for the result to be a complete gzip stream.
 */
fun StreamingSinkProvider.gzip(): StreamingSinkProvider = StreamingSinkProvider {
  GzipSink(openStreamingSink()).buffer()
}
