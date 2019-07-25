package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.io.File

class LegacyHprofTest {

  @Test fun preM() {
    val analysis = analyzeHprof("leak_asynctask_pre_m.hprof")
    assertThat(analysis.applicationLeaks).hasSize(2)
    val leak1 = analysis.applicationLeaks[0]
    val leak2 = analysis.applicationLeaks[1]
    assertThat(leak1.className).isEqualTo("android.graphics.Bitmap")
    assertThat(leak2.className).isEqualTo("com.example.leakcanary.MainActivity")
  }

  @Test fun androidM() {
    val analysis = analyzeHprof("leak_asynctask_m.hprof")

    assertThat(analysis.applicationLeaks).hasSize(1)
    val leak = analysis.applicationLeaks[0]
    assertThat(leak.className).isEqualTo("com.example.leakcanary.MainActivity")
    assertThat(leak.leakTrace.elements[0].labels).contains("GC Root: System class")
  }

  @Test fun androidMStripped() {
    val stripper = HprofPrimitiveArrayStripper()
    val sourceHprof = fileFromResources("leak_asynctask_m.hprof")
    val strippedHprof = stripper.stripPrimitiveArrays(sourceHprof)

    assertThat(readThreadNames(sourceHprof)).contains("AsyncTask #1")
    assertThat(readThreadNames(strippedHprof)).containsOnly("")
  }

  private fun readThreadNames(hprofFile: File): List<String> {
    val threadNames = Hprof.open(hprofFile)
        .use { hprof ->
          val graph = HprofHeapGraph.indexHprof(hprof)
          graph.findClassByName("java.lang.Thread")!!.instances.map { instance ->
            instance["java.lang.Thread", "name"]!!.value.readAsJavaString()!!
          }
              .toList()
        }
    return threadNames
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
    return File(url.path)
  }

  private fun analyzeHprof(hprofFile: File): HeapAnalysisSuccess {
    val heapAnalyzer = HeapAnalyzer(OnAnalysisProgressListener.NO_OP)
    val analysis = heapAnalyzer.checkForLeaks(
        hprofFile, AndroidReferenceMatchers.appDefaults, false, AndroidObjectInspectors.appDefaults
    )
    println(analysis)
    return analysis as HeapAnalysisSuccess
  }
}