package shark

import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.buffer

/**
 * A [DualSourceProvider] that asks [cancelSignal] whether to stop before every read it hands on to
 * [delegate], throwing [CanceledException] when the answer is yes.
 *
 * Reading the heap dump is what heap analysis work spends most of its time doing, so checking here is
 * what makes that work stop shortly after it's asked to, without the code doing the work having to
 * check anything itself.
 *
 * [HprofIndex.indexRecordsOf] wraps the source provider it's given in one of these, so this only has
 * to be applied by hand to work that reads a heap dump without opening a [HeapGraph] on it, like
 * [HprofPrimitiveArrayStripper] or [HprofDeobfuscator].
 */
class CancelableSourceProvider(
  private val delegate: DualSourceProvider,
  private val cancelSignal: CancelSignal
) : DualSourceProvider {

  override fun openStreamingSource(): BufferedSource {
    val delegateSource = delegate.openStreamingSource()
    // Wrapped as a Source and buffered again rather than delegating the whole BufferedSource API,
    // which would mean a check on each of its several dozen methods. Okio moves whole segments
    // between the two buffers, so the extra layer doesn't copy the bytes it passes along.
    return object : Source {
      override fun read(
        sink: Buffer,
        byteCount: Long
      ): Long {
        cancelSignal.throwIfCanceled()
        return delegateSource.read(sink, byteCount)
      }

      override fun timeout() = delegateSource.timeout()

      override fun close() = delegateSource.close()
    }.buffer()
  }

  override fun openRandomAccessSource(): RandomAccessSource {
    val delegateSource = delegate.openRandomAccessSource()
    // Implemented rather than delegated with `by`, so that the default RandomAccessSource
    // implementation of asStreamingSource() reads through the check below instead of past it.
    return object : RandomAccessSource {
      override fun read(
        sink: Buffer,
        position: Long,
        byteCount: Long
      ): Long {
        cancelSignal.throwIfCanceled()
        return delegateSource.read(sink, position, byteCount)
      }

      override fun close() = delegateSource.close()
    }
  }
}
