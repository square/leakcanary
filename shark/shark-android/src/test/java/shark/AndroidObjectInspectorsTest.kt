package shark

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.GcRoot.JniGlobal
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.LeakTraceObject.Verdict.STUCK
import shark.LeakTraceObject.Verdict.EXPECTED
import shark.LeakTraceObject.Verdict.UNKNOWN
import shark.LeakTraceObject.ObjectType.INSTANCE
import shark.ValueHolder.BooleanHolder
import shark.ValueHolder.IntHolder
import shark.ValueHolder.LongHolder
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
    assertThat(recomposerNode.originObject.verdict).isEqualTo(EXPECTED)
    assertThat(recomposerNode.originObject.verdictReason)
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
    assertThat(recomposerNode.originObject.verdict).isEqualTo(STUCK)
    assertThat(recomposerNode.originObject.verdictReason).isEqualTo("Composition disposed")
  }

  @Test fun `COMPOSITION_IMPL with old disposed field true should be leaking`() {
    val analysis = analyzeCompositionImpl(mapOf("disposed" to BooleanHolder(true)))
    val unreachableObject = analysis.unreachableObjects.single()

    assertThat(unreachableObject.verdict).isEqualTo(STUCK)
    assertThat(unreachableObject.verdictReason)
      .contains("Composition disposed")
  }

  @Test fun `COMPOSITION_IMPL with new state field DISPOSED should be leaking`() {
    val analysis = analyzeCompositionImpl(mapOf("state" to IntHolder(3))) // DISPOSED = 3
    val unreachableObject = analysis.unreachableObjects.single()

    assertThat(unreachableObject.verdict).isEqualTo(STUCK)
    assertThat(unreachableObject.verdictReason)
      .contains("Composition disposed")
  }

  @Test fun `COMPOSITION_IMPL with new state field RUNNING should not be leaking`() {
    val analysis = analyzeCompositionImpl(mapOf("state" to IntHolder(0))) // RUNNING = 0
    val unreachableObject = analysis.unreachableObjects.single()

    // Note: Status is still STUCK because this is a watchedInstance (tracked by ObjectWatcher)
    // but the inspector provides the correct reason explaining why it's not actually leaking
    assertThat(unreachableObject.verdict).isEqualTo(STUCK)
    assertThat(unreachableObject.verdictReason)
      .contains("Composition running")
  }

  @Test fun `LIFECYCLE_REGISTRY with DESTROYED state is not reported as not leaking`() {
    val lifecycleRegistry = analyzeLifecycleRegistry(state = "DESTROYED")

    assertThat(lifecycleRegistry.verdict).isEqualTo(UNKNOWN)
    assertThat(lifecycleRegistry.syntheticFields)
      .contains(SyntheticField("LifecycleRegistry.state", "DESTROYED"))
  }

  @Test fun `LIFECYCLE_REGISTRY that is not destroyed is not leaking`() {
    val lifecycleRegistry = analyzeLifecycleRegistry(state = "RESUMED")

    assertThat(lifecycleRegistry.verdict).isEqualTo(EXPECTED)
    assertThat(lifecycleRegistry.verdictReason).isEqualTo("state is RESUMED")
  }

  @Test fun `STUB reports a binder stub as expected`() {
    val stub = analyzeBinderStub()
      .single { it.owningClassSimpleName == "UploadCallbacks\$ResultStub" }
      .originObject

    assertThat(stub.verdict).isEqualTo(EXPECTED)
    assertThat(stub.verdictReason).isEqualTo("UploadCallbacks\$ResultStub is a binder stub")
  }

  @Test fun `STUB points at what the stub holds`() {
    val stub = analyzeBinderStub()
      .single { it.owningClassSimpleName == "UploadCallbacks\$ResultStub" }
      .originObject

    // The link goes to docs/binder-stubs.md.
    assertThat(stub.labels.single()).isEqualTo(
      "Look for the leak in what UploadCallbacks\$ResultStub holds, see" +
        " https://square.github.io/leakcanary/binder-stubs/"
    )
  }

  @Test fun `an object a stub holds keeps its own verdict`() {
    // An expected verdict spreads to what holds an object, never to what it holds.
    val held = analyzeBinderStub()
      .single { it.owningClassSimpleName == "UploadCallbacks" }
      .originObject

    assertThat(held.verdict).isEqualTo(UNKNOWN)
  }

  @Test fun `OBJECT_ANIMATOR reads an animator from before Android 7`() {
    // ValueAnimator.mAnimationEndRequested was added in Android 7.0, and this heap dump is from 6.0.
    val animators = analyzeObjectAnimators("leak_asynctask_m.hprof")

    assertThat(animators).isNotEmpty.allSatisfy { animator ->
      assertThat(animator.syntheticFields).anyMatch { it.name == "ValueAnimator.mRunning" }
      assertThat(animator.syntheticFields).noneMatch { it.name == "ValueAnimator.mAnimationEndRequested" }
    }
  }

  @Test fun `OBJECT_ANIMATOR says whether an animator was asked to end from Android 7 on`() {
    val animators = analyzeObjectAnimators("leak_asynctask_o.hprof")

    assertThat(animators).isNotEmpty.allSatisfy { animator ->
      assertThat(animator.syntheticFields).anyMatch { it.name == "ValueAnimator.mAnimationEndRequested" }
    }
  }

  @Test fun `MESSAGE says how far when is from the heap dump in a larger unit too`() {
    assertThat(messageWhenLabel(150_000)).isEqualTo(
      "Message.when is 150000 ms (2.5 minutes) after the heap dump"
    )
    assertThat(messageWhenLabel(-7_200_000)).isEqualTo(
      "Message.when is 7200000 ms (2 hours) before the heap dump"
    )
    assertThat(messageWhenLabel(1_000)).isEqualTo("Message.when is 1000 ms (1 second) after the heap dump")
    assertThat(messageWhenLabel(129_600_000)).isEqualTo(
      "Message.when is 129600000 ms (1.5 days) after the heap dump"
    )
  }

  @Test fun `MESSAGE picks the unit after rounding`() {
    assertThat(messageWhenLabel(59_999)).isEqualTo("Message.when is 59999 ms (1 minute) after the heap dump")
  }

  @Test fun `MESSAGE says nothing more than ms under a second`() {
    assertThat(messageWhenLabel(-500)).isEqualTo("Message.when is 500 ms before the heap dump")
  }

  /**
   * The label [AndroidObjectInspectors.MESSAGE] gives a message due [offsetMillis] after the heap dump,
   * which the dump DSL takes at 30000 ms of uptime.
   */
  private fun messageWhenLabel(offsetMillis: Long): String {
    val heapDump = dump {
      "android.os.Message" instance {
        field["what"] = IntHolder(0)
        field["when"] = LongHolder(30_000 + offsetMillis)
        field["obj"] = ReferenceHolder(ValueHolder.NULL_REFERENCE)
        field["callback"] = ReferenceHolder(ValueHolder.NULL_REFERENCE)
        field["target"] = ReferenceHolder(ValueHolder.NULL_REFERENCE)
      }
    }
    return heapDump.openHeapGraph().use { graph ->
      val message = graph.findClassByName("android.os.Message")!!.instances.single()
      val reporter = ObjectReporter(message)
      AndroidObjectInspectors.MESSAGE.inspect(reporter)
      reporter.labels.single { it.startsWith("Message.when") }
    }
  }

  /** Every ObjectAnimator of [hprofName], analyzed as if each was a leaking object. */
  private fun analyzeObjectAnimators(hprofName: String): List<LeakTraceObject> {
    val analysis = HeapAnalyzer(OnAnalysisProgressListener.NO_OP).analyze(
      heapDumpFile = hprofName.classpathFile(),
      leakingObjectFinder = { graph ->
        graph.findClassByName("android.animation.ObjectAnimator")!!
          .instances
          .map { it.objectId }
          .toSet()
      },
      referenceMatchers = AndroidReferenceMatchers.appDefaults,
      objectInspectors = AndroidObjectInspectors.appDefaults
    )
    assertThat(analysis).isInstanceOf(HeapAnalysisSuccess::class.java)
    analysis as HeapAnalysisSuccess
    return analysis.allLeaks.flatMap { leak -> leak.leakTraces.map { it.leakingObject } }.toList() +
      analysis.unreachableObjects
  }

  /**
   * The leak trace of a watched object held by a binder stub through the field a compiler writes for a
   * non-static inner class, which is the shape every stub leak has.
   */
  private fun analyzeBinderStub(): List<LeakTraceReference> {
    val heapDump = dump {
      val callbacks = "com.example.UploadCallbacks" instance {
        field["leaked"] = "com.example.LeakingObject" watchedInstance {}
      }
      val stub = instance(
        clazz(
          className = "com.example.UploadCallbacks\$ResultStub",
          superclassId = clazz(className = "android.os.Binder"),
          fields = listOf("this\$0" to ReferenceHolder::class)
        ),
        fields = listOf(callbacks)
      )
      // Another process holding a proxy to the stub, which is why the stub is still here.
      gcRoot(JniGlobal(id = stub.value, jniGlobalRefId = 0))
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
          AndroidObjectInspectors.STUB
        ),
        metadataExtractor = MetadataExtractor.NO_OP
      )
    } as HeapAnalysisSuccess

    return analysis.applicationLeaks.single().leakTraces.single().referencePath
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
