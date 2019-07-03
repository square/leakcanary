package leakcanary.internal

import leakcanary.AndroidKnownReference
import leakcanary.AndroidLeakTraceInspectors
import leakcanary.HeapAnalysis
import leakcanary.HeapAnalysisFailure
import leakcanary.HeapAnalysisSuccess
import leakcanary.LeakingInstance
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.io.File

class LegacyHprofTest {

  @Test fun preM() {
    val analysis = analyzeHprof("leak_asynctask_pre_m.hprof")

    assertThat(analysis.retainedInstances).hasSize(2)
    val leak1 = analysis.retainedInstances[0] as LeakingInstance
    val leak2 = analysis.retainedInstances[1] as LeakingInstance
    assertThat(leak1.instanceClassName).isEqualTo("com.example.leakcanary.MainActivity")
    assertThat(leak2.instanceClassName).isEqualTo("android.graphics.Bitmap")
  }

  @Test fun androidM() {
    val analysis = analyzeHprof("leak_asynctask_m.hprof")

    assertThat(analysis.retainedInstances).hasSize(1)
    val leak = analysis.retainedInstances[0] as LeakingInstance
    assertThat(leak.instanceClassName).isEqualTo("com.example.leakcanary.MainActivity")
  }

  @Test fun androidO() {
    val analysis = analyzeHprof("leak_asynctask_o.hprof")

    assertThat(analysis.retainedInstances).hasSize(1)
    val leak = analysis.retainedInstances[0] as LeakingInstance
    assertThat(leak.instanceClassName).isEqualTo("com.example.leakcanary.MainActivity")
  }

  @Test fun gcRootInNonPrimaryHeap() {
    val analysis = analyzeHprof("gc_root_in_non_primary_heap.hprof")

    assertThat(analysis.retainedInstances).hasSize(1)
    val leak = analysis.retainedInstances[0] as LeakingInstance
    assertThat(leak.instanceClassName).isEqualTo("com.example.leakcanary.MainActivity")
  }

  private fun analyzeHprof(fileName: String): HeapAnalysisSuccess {
    val classLoader = Thread.currentThread()
        .contextClassLoader
    val url = classLoader.getResource(fileName)
    val hprofFile = File(url.path)

    val analysis = hprofFile.checkForLeaks<HeapAnalysis>(
        leakTraceInspectors = AndroidLeakTraceInspectors.defaultInspectors(),
        exclusions = AndroidKnownReference.mapToExclusions(AndroidKnownReference.appDefaults)
    )
    if (analysis is HeapAnalysisFailure) {
      print(analysis)
    }
    return analysis as HeapAnalysisSuccess
  }

}