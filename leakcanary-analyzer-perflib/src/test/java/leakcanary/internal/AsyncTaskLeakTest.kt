package leakcanary.internal

import leakcanary.PerflibExcludedRefs
import leakcanary.PerflibExcludedRefs.BuilderWithParams
import leakcanary.LeakTraceElement.Holder.THREAD
import leakcanary.LeakTraceElement.Type.STATIC_FIELD
import leakcanary.internal.HeapDumpFile.ASYNC_TASK_M
import leakcanary.internal.HeapDumpFile.ASYNC_TASK_O
import leakcanary.internal.HeapDumpFile.ASYNC_TASK_PRE_M
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.lang.ref.PhantomReference
import java.lang.ref.WeakReference
import java.util.Arrays

@RunWith(Parameterized::class)
internal class AsyncTaskLeakTest(private val heapDumpFile: HeapDumpFile) {

  private lateinit var excludedRefs: BuilderWithParams

  @Before
  fun setUp() {
    excludedRefs = PerflibExcludedRefs.builder()
        .clazz(WeakReference::class.java.name)
        .alwaysExclude()
        .clazz("java.lang.ref.FinalizerReference")
        .alwaysExclude()
        .clazz(PhantomReference::class.java.name)
        .alwaysExclude()
  }

  @Test
  fun leakFound() {
    val result = analyze(heapDumpFile, excludedRefs)
    assertThat(result.leakFound).isTrue()
    assertThat(result.excludedLeak).isFalse()
    val gcRoot = result.leakTrace!!.elements[0]
    assertThat(Thread::class.java.name).isEqualTo(gcRoot.className)
    assertThat(THREAD).isEqualTo(gcRoot.holder)
  }

  @Test
  fun excludeThread() {
    excludedRefs.thread(ASYNC_TASK_THREAD)
    val result = analyze(heapDumpFile, excludedRefs)
    assertThat(result.leakFound).isTrue()
    assertThat(result.excludedLeak).isFalse()
    val gcRoot = result.leakTrace!!.elements[0]
    assertThat(ASYNC_TASK_CLASS).isEqualTo(gcRoot.className)
    assertThat(STATIC_FIELD).isEqualTo(gcRoot.reference!!.type)
    assertThat(
        gcRoot.reference!!.name == EXECUTOR_FIELD_1 || gcRoot.reference!!.name == EXECUTOR_FIELD_2
    ).isTrue()
  }

  @Test
  fun excludeStatic() {
    excludedRefs.thread(ASYNC_TASK_THREAD)
    excludedRefs.staticField(
        ASYNC_TASK_CLASS,
        EXECUTOR_FIELD_1
    )
    excludedRefs.staticField(
        ASYNC_TASK_CLASS,
        EXECUTOR_FIELD_2
    )
    val result = analyze(heapDumpFile, excludedRefs)
    assertThat(result.leakFound).isTrue()
    assertThat(result.excludedLeak).isTrue()
    val elements = result.leakTrace!!.elements
    val exclusion = elements[0].exclusion

    val expectedExclusions = Arrays.asList(
        "any threads named $ASYNC_TASK_THREAD",
        "static field $ASYNC_TASK_CLASS#$EXECUTOR_FIELD_1",
        "static field $ASYNC_TASK_CLASS#$EXECUTOR_FIELD_2"
    )
    assertThat(expectedExclusions.contains(exclusion!!.matching)).isTrue()
  }

  companion object {
    private const val ASYNC_TASK_THREAD = "AsyncTask #1"
    private const val ASYNC_TASK_CLASS = "android.os.AsyncTask"
    private const val EXECUTOR_FIELD_1 = "SERIAL_EXECUTOR"
    private const val EXECUTOR_FIELD_2 = "sDefaultExecutor"
    @JvmStatic
    @Parameterized.Parameters
    fun data() = listOf(
        arrayOf(ASYNC_TASK_PRE_M),
        arrayOf(ASYNC_TASK_M),
        arrayOf(ASYNC_TASK_O)
    )
  }
}
