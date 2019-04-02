package com.squareup.leakcanary

import com.squareup.leakcanary.HeapDumpFile.ASYNC_TASK_M
import com.squareup.leakcanary.HeapDumpFile.ASYNC_TASK_PRE_M
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
    val trackedReferences = findTrackedReferences(ASYNC_TASK_M)
    assertThat(trackedReferences).hasSize(1)
    val firstRef = trackedReferences[0]
    assertThat(firstRef.key).isEqualTo(ASYNC_TASK_M.referenceKey)
    assertThat(firstRef.className).isEqualTo("com.example.leakcanary.MainActivity")
  }

  @Test
  fun findsSeveralRefs() {
    val trackedReferences = findTrackedReferences(ASYNC_TASK_PRE_M)
    assertThat(trackedReferences).hasSize(2)
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