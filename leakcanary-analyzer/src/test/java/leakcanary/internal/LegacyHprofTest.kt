package leakcanary.internal

import leakcanary.AndroidReferenceMatchers
import leakcanary.AndroidObjectInspectors
import leakcanary.HeapAnalysis
import leakcanary.HeapAnalysisSuccess
import leakcanary.HprofPrimitiveArrayStripper
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.io.File

class LegacyHprofTest {

  @Test fun preM() {
    val analysis = analyzeHprof("leak_asynctask_pre_m.hprof")
    assertThat(analysis.applicationLeaks).hasSize(2)
    val leak1 = analysis.applicationLeaks[0]
    val leak2 = analysis.applicationLeaks[1]
    assertThat(leak1.className).isEqualTo("com.example.leakcanary.MainActivity")
    assertThat(leak2.className).isEqualTo("android.graphics.Bitmap")
  }

  @Test fun androidM() {
    val analysis = analyzeHprof("leak_asynctask_m.hprof")

    assertThat(analysis.applicationLeaks).hasSize(1)
    val leak = analysis.applicationLeaks[0]
    assertThat(leak.className).isEqualTo("com.example.leakcanary.MainActivity")
    assertThat(leak.leakTrace.elements[0].labels).contains("Thread name: 'AsyncTask #1'")
  }

  @Test fun androidMStripped() {
    val stripper = HprofPrimitiveArrayStripper()
    val strippedHprof =
      stripper.stripPrimitiveArrays(fileFromResources("leak_asynctask_m.hprof"))

    val analysis = analyzeHprof(strippedHprof)

    assertThat(analysis.applicationLeaks).hasSize(1)
    val leak = analysis.applicationLeaks[0]
    assertThat(leak.className).isEqualTo("com.example.leakcanary.MainActivity")
    assertThat(leak.leakTrace.elements[0].labels).contains("Thread name: ''")
  }

  @Test fun androidO() {
    val analysis = analyzeHprof("leak_asynctask_o.hprof")

    assertThat(analysis.applicationLeaks).hasSize(1)
    val leak = analysis.applicationLeaks[0]
    assertThat(leak.className).isEqualTo("com.example.leakcanary.MainActivity")
  }

  @Test fun gcRootInNonPrimaryHeap() {
    val analysis = analyzeHprof("gc_root_in_non_primary_heap.hprof")

    assertThat(analysis.applicationLeaks).hasSize(1)
    val leak = analysis.applicationLeaks[0]
    assertThat(leak.className).isEqualTo("com.example.leakcanary.MainActivity")
  }

  private fun analyzeHprof(fileName: String): HeapAnalysisSuccess {
    return analyzeHprof(fileFromResources(fileName))
  }

  private fun fileFromResources(fileName: String): File {
    val classLoader = Thread.currentThread()
        .contextClassLoader
    val url = classLoader.getResource(fileName)
    val hprofFile = File(url.path)
    return hprofFile
  }

  private fun analyzeHprof(hprofFile: File): HeapAnalysisSuccess {
    val analysis = hprofFile.checkForLeaks<HeapAnalysis>(
        objectInspectors = AndroidObjectInspectors.appDefaults,
        referenceMatchers = AndroidReferenceMatchers.appDefaults
    )
    print(analysis)
    return analysis as HeapAnalysisSuccess
  }

}