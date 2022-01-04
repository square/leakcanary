package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.LeakTraceObject.LeakingStatus.LEAKING
import shark.LeakTraceObject.LeakingStatus.NOT_LEAKING
import shark.LeakTraceObject.ObjectType.INSTANCE

class AndroidObjectInspectorsTest {

  @Test fun `RECOMPOSER leaking status relies on state`() {
    val hprofFile = "compose_leak.hprof".classpathFile()
    val heapAnalyzer = HeapAnalyzer(OnAnalysisProgressListener.NO_OP)
    val analysis = heapAnalyzer.analyze(
      heapDumpFile = hprofFile,
      leakingObjectFinder = { graph ->
        val composeViewClass =
          graph.findClassByName("androidx.compose.ui.platform.AndroidComposeView")
        composeViewClass!!.instances.filter { instance ->
          val filter = AndroidObjectInspectors.VIEW.leakingObjectFilter!!
          filter.invoke(instance)
          true
        }.map { it.objectId }.toSet()
      },
      referenceMatchers = AndroidReferenceMatchers.appDefaults,
      objectInspectors = AndroidObjectInspectors.appDefaults
    )
    println(analysis)
    analysis as HeapAnalysisSuccess
    val recomposerNode = analysis.applicationLeaks.single()
      .leakTraces.single()
      .referencePath.single {
        it.originObject.type == INSTANCE
          && it.owningClassSimpleName == "Recomposer"
      }
    assertThat(recomposerNode.originObject.leakingStatus == NOT_LEAKING)
    assertThat(recomposerNode.originObject.leakingStatusReason == "Recomposer is in state PendingWork")
  }

  @Test fun `COMPOSITION_IMPL leaking status relies on disposal`() {
    val hprofFile = "compose_leak.hprof".classpathFile()
    val heapAnalyzer = HeapAnalyzer(OnAnalysisProgressListener.NO_OP)
    val analysis = heapAnalyzer.analyze(
      heapDumpFile = hprofFile,
      leakingObjectFinder = { graph ->
        val composeViewClass =
          graph.findClassByName("androidx.compose.ui.platform.AndroidComposeView")
        composeViewClass!!.instances.filter { instance ->
          val filter = AndroidObjectInspectors.VIEW.leakingObjectFilter!!
          filter.invoke(instance)
          true
        }.map { it.objectId }.toSet()
      },
      referenceMatchers = AndroidReferenceMatchers.appDefaults,
      objectInspectors = AndroidObjectInspectors.appDefaults
    )
    println(analysis)
    analysis as HeapAnalysisSuccess
    val recomposerNode = analysis.applicationLeaks.single()
      .leakTraces.single()
      .referencePath.single {
        it.owningClassSimpleName == "CompositionImpl"
      }
    assertThat(recomposerNode.originObject.leakingStatus == LEAKING)
    assertThat(recomposerNode.originObject.leakingStatusReason == "Composition disposed")
  }
}
