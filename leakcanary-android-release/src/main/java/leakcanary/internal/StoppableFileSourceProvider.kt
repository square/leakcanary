package leakcanary.internal

import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.buffer
import okio.source
import shark.DualSourceProvider
import shark.RandomAccessSource
import java.io.File

internal class StoppableFileSourceProvider(
  private val file: File,
  private val throwIfStop: () -> Unit
) : DualSourceProvider {

  override fun openStreamingSource(): BufferedSource {
    val realSource = file.inputStream().source()
    return object : Source by realSource {
      override fun read(
        sink: Buffer,
        byteCount: Long
      ): Long {
        throwIfStop()
        return realSource.read(sink, byteCount)
      }
    }.buffer()
  }

  override fun openRandomAccessSource(): RandomAccessSource {
    val channel = file.inputStream().channel
    return object : RandomAccessSource {
      override fun read(
        sink: Buffer,
        position: Long,
        byteCount: Long
      ): Long {
        throwIfStop()
        return channel.transferTo(position, byteCount, sink)
      }

      override fun close() = channel.close()
    }
  }
}