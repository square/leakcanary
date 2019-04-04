package com.squareup.leakcanary

import com.squareup.leakcanary.HeapDumpFile.ASYNC_TASK_P
import com.squareup.leakcanary.HeapDumpFile.GC_ROOT_IN_NON_PRIMARY_HEAP
import com.squareup.leakcanary.HeapDumpFile.MISSING_THREAD
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class HeapAnalyzerHeapDumpTest {

  @Test
  fun findsExpectedRef() {
    val leak = findLeak(ASYNC_TASK_P)!!
    assertThat(leak.leakFound).isTrue()
    assertThat(leak.excludedLeak).isFalse()
    assertThat(leak.className).isEqualTo("com.example.leakcanary.MainActivity")
  }

  @Test
  fun findsSeveralRefs() {
    val results = findAllLeaks(ASYNC_TASK_P)
    assertThat(results).hasSize(3)
  }

  @Test
  fun leakFoundWithGcRootInNonPrimaryHeap() {
    val result = analyze(GC_ROOT_IN_NON_PRIMARY_HEAP)
    assertThat(result.leakFound).isTrue()
  }

  @Test
  fun heapDumpWithMissingNativeThread() {
    val result = analyze(MISSING_THREAD)
    assertThat(result.leakFound).isTrue()
  }
}