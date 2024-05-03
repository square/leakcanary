package leakcanary.internal

import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

internal class LazyImmediateFuture<V>(
  valueProvider: () -> V
) : ListenableFuture<V> {

  private val value by lazy {
    valueProvider()
  }

  override fun cancel(mayInterruptIfRunning: Boolean) = false

  override fun isCancelled() = false

  override fun isDone() = true

  override fun get() = value

  override fun get(
    timeout: Long,
    unit: TimeUnit?
  ): V = value

  override fun addListener(
    listener: Runnable,
    executor: Executor
  ) {
    executor.execute(listener)
  }
}
