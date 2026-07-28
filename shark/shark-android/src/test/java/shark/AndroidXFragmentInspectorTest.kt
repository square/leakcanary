package shark

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.LeakTraceObject.LeakingStatus.LEAKING
import shark.LeakTraceObject.LeakingStatus.NOT_LEAKING
import shark.LeakTraceObject.LeakingStatus.UNKNOWN
import shark.ValueHolder.BooleanHolder
import shark.ValueHolder.IntHolder
import shark.ValueHolder.ReferenceHolder

/**
 * [AndroidObjectInspectors.ANDROIDX_FRAGMENT] has to tell a destroyed fragment apart from a fragment
 * that is still in use, and androidx.fragment 1.1.0 changed what a destroyed fragment looks like:
 * `Fragment#initState()` replaces `Fragment.mLifecycleRegistry`, so a destroyed fragment is reset
 * back to an `INITIALIZED` state instead of staying `DESTROYED`.
 *
 * These tests write heap dumps that look like each version of androidx.fragment, which is the only
 * way to cover both behaviors from a single test run.
 */
class AndroidXFragmentInspectorTest {

  private var lifecycleStateClassId = 0L

  @Test fun `fragment 1_0_0 that was destroyed is leaking`() {
    val fragment = analyzeFragment(fragmentOneZero(state = "DESTROYED"))

    assertThat(fragment.leakingStatus).isEqualTo(LEAKING)
    assertThat(fragment.leakingStatusReason)
      .isEqualTo("Fragment.mLifecycleRegistry.state is DESTROYED")
  }

  @Test fun `fragment 1_0_0 that was never created is not leaking`() {
    val fragment = analyzeFragment(fragmentOneZero(state = "INITIALIZED"))

    assertThat(fragment.leakingStatus).isEqualTo(NOT_LEAKING)
    assertThat(fragment.leakingStatusReason)
      .isEqualTo("Fragment.mLifecycleRegistry.state is INITIALIZED")
  }

  @Test fun `fragment 1_1_0 that was destroyed without being reset is leaking`() {
    val fragment = analyzeFragment(fragmentOneOne(state = "DESTROYED", called = true))

    assertThat(fragment.leakingStatus).isEqualTo(LEAKING)
    assertThat(fragment.leakingStatusReason)
      .isEqualTo("Fragment.mLifecycleRegistry.state is DESTROYED")
  }

  /**
   * The regression this change is about: from androidx.fragment 1.1.0 on a removed fragment is reset
   * back to INITIALIZED, which used to be read as "not leaking".
   */
  @Test fun `fragment 1_1_0 that was reset to INITIALIZED after being destroyed is leaking`() {
    val fragment = analyzeFragment(fragmentOneOne(state = "INITIALIZED", called = true))

    assertThat(fragment.leakingStatus).isEqualTo(LEAKING)
    assertThat(fragment.leakingStatusReason).isEqualTo(
      "Fragment.mLifecycleRegistry.state was reset to INITIALIZED by Fragment#initState() after" +
        " Fragment#onDestroy()"
    )
  }

  @Test fun `fragment 1_1_0 that was never attached is not leaking`() {
    val fragment = analyzeFragment(fragmentOneOne(state = "INITIALIZED", called = false))

    assertThat(fragment.leakingStatus).isEqualTo(NOT_LEAKING)
    assertThat(fragment.leakingStatusReason).isEqualTo(
      "Fragment.mLifecycleRegistry.state is INITIALIZED and the fragment was never attached"
    )
  }

  /**
   * A fragment that was added but not created yet is INITIALIZED with mCalled set to true by
   * `Fragment#onAttach()`. It still has a FragmentManager, which is what tells it apart from a
   * destroyed fragment.
   */
  @Test fun `fragment 1_1_0 that was attached but not created yet is not leaking`() {
    val fragment = analyzeFragment(
      fragmentOneOne(state = "INITIALIZED", called = true, hasFragmentManager = true)
    )

    assertThat(fragment.leakingStatus).isEqualTo(NOT_LEAKING)
    assertThat(fragment.leakingStatusReason).isEqualTo(
      "Fragment.mLifecycleRegistry.state is INITIALIZED and Fragment.mFragmentManager is set"
    )
  }

  @Test fun `fragment 1_1_0 that is in use is not leaking`() {
    val fragment = analyzeFragment(fragmentOneOne(state = "RESUMED", called = true))

    assertThat(fragment.leakingStatus).isEqualTo(NOT_LEAKING)
    assertThat(fragment.leakingStatusReason)
      .isEqualTo("Fragment.mLifecycleRegistry.state is RESUMED")
  }

  @Test fun `fragment without a lifecycle registry has an unknown status`() {
    val fragment = analyzeFragment(fragmentOneOne(state = null, called = true))

    assertThat(fragment.leakingStatus).isEqualTo(UNKNOWN)
    assertThat(fragment.labels).contains("Fragment.mLifecycleRegistry = null")
  }

  @Test fun `fragment tag is reported as a label`() {
    val fragment = analyzeFragment(
      fragmentOneOne(state = "RESUMED", called = true, tag = "my-fragment")
    )

    assertThat(fragment.labels).contains("Fragment.mTag = my-fragment")
  }

  @Test fun `destroyed fragment 1_1_0 matches the leaking object filter`() {
    val leakingObjectFound = fragmentMatchesLeakingObjectFilter(
      fragmentOneOne(state = "INITIALIZED", called = true)
    )

    assertThat(leakingObjectFound).isTrue()
  }

  @Test fun `fragment 1_1_0 that was never attached does not match the leaking object filter`() {
    val leakingObjectFound = fragmentMatchesLeakingObjectFilter(
      fragmentOneOne(state = "INITIALIZED", called = false)
    )

    assertThat(leakingObjectFound).isFalse()
  }

  /**
   * Builds a fragment that looks like an androidx.fragment 1.0.0 fragment: it does not declare
   * `Fragment.mMaxState`.
   */
  private fun fragmentOneZero(
    state: String?
  ): HprofWriterHelper.(ReferenceHolder) -> ReferenceHolder = { view ->
    ANDROIDX_FRAGMENT_CLASS_NAME instance {
      field["mLifecycleRegistry"] = lifecycleRegistry(state)
      field["mFragmentManager"] = NULL_REFERENCE
      field["mTag"] = NULL_REFERENCE
      field["mCalled"] = BooleanHolder(true)
      field["mIndex"] = IntHolder(-1)
      field["mView"] = view
    }
  }

  /**
   * Builds a fragment that looks like an androidx.fragment 1.1.0 (or higher) fragment: it declares
   * `Fragment.mMaxState`.
   */
  private fun fragmentOneOne(
    state: String?,
    called: Boolean,
    hasFragmentManager: Boolean = false,
    tag: String? = null
  ): HprofWriterHelper.(ReferenceHolder) -> ReferenceHolder = { view ->
    ANDROIDX_FRAGMENT_CLASS_NAME instance {
      field["mLifecycleRegistry"] = lifecycleRegistry(state)
      field["mFragmentManager"] = if (hasFragmentManager) {
        "androidx.fragment.app.FragmentManager" instance {}
      } else {
        NULL_REFERENCE
      }
      field["mTag"] = if (tag != null) string(tag) else NULL_REFERENCE
      field["mCalled"] = BooleanHolder(called)
      field["mMaxState"] = lifecycleState("RESUMED")
      field["mView"] = view
    }
  }

  private fun HprofWriterHelper.lifecycleRegistry(state: String?): ReferenceHolder {
    if (state == null) {
      return NULL_REFERENCE
    }
    return "androidx.lifecycle.LifecycleRegistry" instance {
      field["state"] = lifecycleState(state)
    }
  }

  private fun HprofWriterHelper.lifecycleState(name: String): ReferenceHolder =
    instance(lifecycleStateClassId, listOf(string(name)))

  /**
   * Dumps a heap where a class statically references [fragment], which in turn references a watched
   * object, so that the fragment always shows up as a node of a single leak trace.
   */
  private fun dumpWithFragment(
    fragment: HprofWriterHelper.(ReferenceHolder) -> ReferenceHolder
  ) = dump {
    // Lifecycle.State is an enum, so name is declared by java.lang.Enum, which is where
    // AndroidObjectInspectors reads it from.
    lifecycleStateClassId = clazz(
      className = "androidx.lifecycle.Lifecycle\$State",
      superclassId = clazz(
        className = "java.lang.Enum",
        fields = listOf("name" to ReferenceHolder::class)
      )
    )
    val watchedObject = "com.example.LeakingObject" watchedInstance {}
    val fragmentInstance = fragment(watchedObject)
    "com.example.Holder" clazz {
      staticField["fragment"] = fragmentInstance
    }
  }

  private fun analyzeFragment(
    fragment: HprofWriterHelper.(ReferenceHolder) -> ReferenceHolder
  ): LeakTraceObject {
    val heapAnalyzer = HeapAnalyzer(OnAnalysisProgressListener.NO_OP)
    val analysis = dumpWithFragment(fragment).openHeapGraph().use { graph ->
      heapAnalyzer.analyze(
        heapDumpFile = HEAP_DUMP_FILE,
        graph = graph,
        leakingObjectFinder = KeyedWeakReferenceFinder,
        referenceMatchers = JdkReferenceMatchers.defaults,
        computeRetainedHeapSize = false,
        objectInspectors = listOf(
          ObjectInspectors.KEYED_WEAK_REFERENCE,
          AndroidObjectInspectors.ANDROIDX_FRAGMENT
        ),
        metadataExtractor = MetadataExtractor.NO_OP
      )
    } as HeapAnalysisSuccess
    return analysis.applicationLeaks.single()
      .leakTraces.single()
      .referencePath
      .single { it.originObject.className == ANDROIDX_FRAGMENT_CLASS_NAME }
      .originObject
  }

  private fun fragmentMatchesLeakingObjectFilter(
    fragment: HprofWriterHelper.(ReferenceHolder) -> ReferenceHolder
  ): Boolean {
    val filter = AndroidObjectInspectors.ANDROIDX_FRAGMENT.leakingObjectFilter!!
    return dumpWithFragment(fragment).openHeapGraph().use { graph ->
      val fragmentInstance = graph.instances.single {
        it instanceOf ANDROIDX_FRAGMENT_CLASS_NAME
      }
      filter(fragmentInstance)
    }
  }

  companion object {
    private val NULL_REFERENCE = ReferenceHolder(ValueHolder.NULL_REFERENCE)
    private val HEAP_DUMP_FILE = File("/heap/dump/does/not/exist")
  }
}
