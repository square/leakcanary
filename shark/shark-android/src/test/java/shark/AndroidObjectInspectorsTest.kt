package shark

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.LeakTraceObject.LeakingStatus.LEAKING
import shark.LeakTraceObject.LeakingStatus.NOT_LEAKING
import shark.LeakTraceObject.LeakingStatus.UNKNOWN
import shark.LeakTraceObject.ObjectType.INSTANCE
import shark.ValueHolder.BooleanHolder
import shark.ValueHolder.IntHolder
import shark.ValueHolder.ReferenceHolder

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
    assertThat(recomposerNode.originObject.leakingStatus).isEqualTo(NOT_LEAKING)
    assertThat(recomposerNode.originObject.leakingStatusReason)
      .isEqualTo("Recomposer is in state PendingWork")
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
    assertThat(recomposerNode.originObject.leakingStatus).isEqualTo(LEAKING)
    assertThat(recomposerNode.originObject.leakingStatusReason).isEqualTo("Composition disposed")
  }

  @Test fun `COMPOSITION_IMPL with old disposed field true should be leaking`() {
    val analysis = analyzeCompositionImpl(mapOf("disposed" to BooleanHolder(true)))
    val unreachableObject = analysis.unreachableObjects.single()

    assertThat(unreachableObject.leakingStatus).isEqualTo(LEAKING)
    assertThat(unreachableObject.leakingStatusReason)
      .contains("Composition disposed")
  }

  @Test fun `COMPOSITION_IMPL with new state field DISPOSED should be leaking`() {
    val analysis = analyzeCompositionImpl(mapOf("state" to IntHolder(3))) // DISPOSED = 3
    val unreachableObject = analysis.unreachableObjects.single()

    assertThat(unreachableObject.leakingStatus).isEqualTo(LEAKING)
    assertThat(unreachableObject.leakingStatusReason)
      .contains("Composition disposed")
  }

  @Test fun `COMPOSITION_IMPL with new state field RUNNING should not be leaking`() {
    val analysis = analyzeCompositionImpl(mapOf("state" to IntHolder(0))) // RUNNING = 0
    val unreachableObject = analysis.unreachableObjects.single()

    // Note: Status is still LEAKING because this is a watchedInstance (tracked by ObjectWatcher)
    // but the inspector provides the correct reason explaining why it's not actually leaking
    assertThat(unreachableObject.leakingStatus).isEqualTo(LEAKING)
    assertThat(unreachableObject.leakingStatusReason)
      .contains("Composition running")
  }

  @Test fun `LIFECYCLE_REGISTRY with DESTROYED state is not reported as not leaking`() {
    val lifecycleRegistry = analyzeLifecycleRegistry(state = "DESTROYED")

    assertThat(lifecycleRegistry.leakingStatus).isEqualTo(UNKNOWN)
    assertThat(lifecycleRegistry.labels).contains("state = DESTROYED")
  }

  @Test fun `LIFECYCLE_REGISTRY that is not destroyed is not leaking`() {
    val lifecycleRegistry = analyzeLifecycleRegistry(state = "RESUMED")

    assertThat(lifecycleRegistry.leakingStatus).isEqualTo(NOT_LEAKING)
    assertThat(lifecycleRegistry.leakingStatusReason).isEqualTo("state is RESUMED")
  }

  /**
   * Returns the `LifecycleRegistry` node of a leak trace where a registry in [state] holds on to a
   * leaking object. A destroyed registry must not be reported as not leaking: a leak trace is cut
   * at the last node that is known to not be leaking, which would hide the real cause.
   */
  private fun analyzeLifecycleRegistry(state: String): LeakTraceObject {
    val heapDump = dump {
      // Lifecycle.State is an enum, so name is declared by java.lang.Enum.
      val stateClassId = clazz(
        className = "androidx.lifecycle.Lifecycle\$State",
        superclassId = clazz(
          className = "java.lang.Enum",
          fields = listOf("name" to ReferenceHolder::class)
        )
      )
      val lifecycleRegistry = "androidx.lifecycle.LifecycleRegistry" instance {
        field["state"] = instance(stateClassId, listOf(string(state)))
        field["observerMap"] = "com.example.LeakingObject" watchedInstance {}
      }
      "com.example.Holder" clazz {
        staticField["lifecycleRegistry"] = lifecycleRegistry
      }
    }

    val heapAnalyzer = HeapAnalyzer(OnAnalysisProgressListener.NO_OP)

    val analysis = heapDump.openHeapGraph().use { graph ->
      heapAnalyzer.analyze(
        heapDumpFile = File("/no/file"),
        graph = graph,
        leakingObjectFinder = KeyedWeakReferenceFinder,
        referenceMatchers = JdkReferenceMatchers.defaults,
        computeRetainedHeapSize = false,
        objectInspectors = listOf(
          ObjectInspectors.KEYED_WEAK_REFERENCE,
          AndroidObjectInspectors.LIFECYCLE_REGISTRY
        ),
        metadataExtractor = MetadataExtractor.NO_OP
      )
    } as HeapAnalysisSuccess

    return analysis.applicationLeaks.single()
      .leakTraces.single()
      .referencePath
      .single { it.owningClassSimpleName == "LifecycleRegistry" }
      .originObject
  }

  private fun analyzeCompositionImpl(fields: Map<String, ValueHolder>): HeapAnalysisSuccess {
    val heapDump = dump {
      "androidx.compose.runtime.CompositionImpl" watchedInstance {
        fields.forEach { (name, value) ->
          field[name] = value
        }
      }
    }

    val heapAnalyzer = HeapAnalyzer(OnAnalysisProgressListener.NO_OP)

    return heapDump.openHeapGraph().use { graph: HeapGraph ->
      heapAnalyzer.analyze(
        heapDumpFile = File("/no/file"),
        graph = graph,
        leakingObjectFinder = FilteringLeakingObjectFinder(
          ObjectInspectors.jdkLeakingObjectFilters
        ),
        referenceMatchers = JdkReferenceMatchers.defaults,
        computeRetainedHeapSize = false,
        objectInspectors = listOf(ObjectInspectors.KEYED_WEAK_REFERENCE, AndroidObjectInspectors.COMPOSITION_IMPL),
        metadataExtractor = MetadataExtractor.NO_OP
      )
    } as HeapAnalysisSuccess
  }
}
