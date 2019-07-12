package leakcanary.internal

import leakcanary.AndroidKnownReference
import leakcanary.AndroidObjectInspectors
import leakcanary.HeapAnalysis
import leakcanary.HeapAnalysisFailure
import leakcanary.HeapAnalysisSuccess
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.io.File

class LegacyHprofTest {

  @Test fun preM() {
    val analysis = analyzeHprof("leak_asynctask_pre_m.hprof")

    assertThat(analysis.leakingInstances).hasSize(2)
    val leak1 = analysis.leakingInstances[0]
    val leak2 = analysis.leakingInstances[1]
    assertThat(leak1.instanceClassName).isEqualTo("com.example.leakcanary.MainActivity")
    assertThat(leak2.instanceClassName).isEqualTo("android.graphics.Bitmap")
  }

  @Test fun androidM() {
    val analysis = analyzeHprof("leak_asynctask_m.hprof")

    assertThat(analysis.leakingInstances).hasSize(1)
    val leak = analysis.leakingInstances[0]
    assertThat(leak.instanceClassName).isEqualTo("com.example.leakcanary.MainActivity")
  }

  @Test fun androidO() {
    val analysis = analyzeHprof("leak_asynctask_o.hprof")

    assertThat(analysis.leakingInstances).hasSize(1)
    val leak = analysis.leakingInstances[0]
    assertThat(leak.instanceClassName).isEqualTo("com.example.leakcanary.MainActivity")
  }

  @Test fun gcRootInNonPrimaryHeap() {
    val analysis = analyzeHprof("gc_root_in_non_primary_heap.hprof")

    assertThat(analysis.leakingInstances).hasSize(1)
    val leak = analysis.leakingInstances[0]
    assertThat(leak.instanceClassName).isEqualTo("com.example.leakcanary.MainActivity")
  }

  private fun analyzeHprof(fileName: String): HeapAnalysisSuccess {
    val classLoader = Thread.currentThread()
        .contextClassLoader
    val url = classLoader.getResource(fileName)
    val hprofFile = File(url.path)

    val analysis = hprofFile.checkForLeaks<HeapAnalysis>(
        objectInspectors = AndroidObjectInspectors.defaultInspectors(),
        exclusions = AndroidKnownReference.mapToExclusions(AndroidKnownReference.appDefaults)
    )
    print(analysis)
    return analysis as HeapAnalysisSuccess
  }

}