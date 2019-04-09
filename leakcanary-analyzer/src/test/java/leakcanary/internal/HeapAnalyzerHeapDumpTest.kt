package leakcanary.internal

import leakcanary.HeapAnalysisSuccess
import leakcanary.LeakingInstance
import leakcanary.internal.HeapDumpFile.ASYNC_TASK_P
import leakcanary.internal.HeapDumpFile.GC_ROOT_IN_NON_PRIMARY_HEAP
import leakcanary.internal.HeapDumpFile.MISSING_THREAD
import org.assertj.core.api.Assertions.assertThat
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class HeapAnalyzerHeapDumpTest {

  @Test @Ignore("Need new heapdump with className in KeyedWeakReference")
  fun findsExpectedRef() {
    val retainedInstance = findLeak(ASYNC_TASK_P)!!
    assertThat(retainedInstance).isInstanceOf(LeakingInstance::class.java)
    val leak = retainedInstance as LeakingInstance
    assertThat(leak.excludedLeak).isFalse()
    assertThat(leak.instanceClassName).isEqualTo("com.example.leakcanary.MainActivity")
  }

  @Test @Ignore("Need new heapdump with className in KeyedWeakReference")
  fun findsSeveralRefs() {
    val heapAnalysis = findAllLeaks(ASYNC_TASK_P)
    assertThat(heapAnalysis).isInstanceOf(HeapAnalysisSuccess::class.java)
    val results = heapAnalysis as HeapAnalysisSuccess
    assertThat(results.retainedInstances).hasSize(3)
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