package com.squareup.leakcanary

import com.squareup.leakcanary.HeapDumpFile.ASYNC_TASK_M
import com.squareup.leakcanary.HeapDumpFile.ASYNC_TASK_O
import com.squareup.leakcanary.HeapDumpFile.ASYNC_TASK_PRE_M
import org.assertj.core.api.Assertions.assertThat
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * This test makes sure there is no regression on the retained size calculation.
 */
@RunWith(Parameterized::class)
internal class RetainedSizeTest(
  val heapDumpFile: HeapDumpFile,
  val expectedRetainedHeapSize: Long
) {

  @Test @Ignore("KeyedWeakReference has moved packages, need new heap dumps")
  fun leakFound() {
    val result = analyze(heapDumpFile)
    assertThat(expectedRetainedHeapSize).isEqualTo(result.retainedHeapSize)
  }

  companion object {
    @JvmStatic
    @Parameterized.Parameters
    fun data() = listOf(
        arrayOf(ASYNC_TASK_PRE_M, 33367),
        arrayOf(ASYNC_TASK_M, 49584),
        arrayOf(ASYNC_TASK_O, 210978)
    )
  }
}