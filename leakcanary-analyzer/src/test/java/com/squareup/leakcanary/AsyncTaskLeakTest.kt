package com.squareup.leakcanary

import com.squareup.leakcanary.HeapDumpFile.ASYNC_TASK_M
import com.squareup.leakcanary.HeapDumpFile.ASYNC_TASK_O
import com.squareup.leakcanary.HeapDumpFile.ASYNC_TASK_PRE_M
import com.squareup.leakcanary.LeakTraceElement.Holder.THREAD
import com.squareup.leakcanary.LeakTraceElement.Type.STATIC_FIELD
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.lang.ref.PhantomReference
import java.lang.ref.WeakReference
import java.util.Arrays

@RunWith(Parameterized::class)
internal class AsyncTaskLeakTest(private val heapDumpFile: HeapDumpFile) {

  private lateinit var excludedRefs: ExcludedRefs.BuilderWithParams

  @Before
  fun setUp() {
    excludedRefs = ExcludedRefs.BuilderWithParams()
        .clazz(WeakReference::class.java.name)
        .alwaysExclude()
        .clazz("java.lang.ref.FinalizerReference")
        .alwaysExclude()
        .clazz(PhantomReference::class.java.name)
        .alwaysExclude()
  }

  @Test @Ignore("KeyedWeakReference has moved packages, need new heap dumps")
  fun leakFound() {
    val result = analyze(heapDumpFile, excludedRefs)
    assertThat(result.leakFound).isTrue()
    assertThat(result.excludedLeak).isFalse()
    val gcRoot = result.leakTrace!!.elements[0]
    assertThat(Thread::class.java.name).isEqualTo(gcRoot.className)
    assertThat(THREAD).isEqualTo(gcRoot.holder)
    assertThat(gcRoot.extra).contains(ASYNC_TASK_THREAD)
  }

  @Test @Ignore("KeyedWeakReference has moved packages, need new heap dumps")
  fun excludeThread() {
    excludedRefs.thread(ASYNC_TASK_THREAD)
    val result = analyze(heapDumpFile, excludedRefs)
    assertThat(result.leakFound).isTrue()
    assertThat(result.excludedLeak).isFalse()
    val gcRoot = result.leakTrace!!.elements[0]
    assertThat(ASYNC_TASK_CLASS).isEqualTo(gcRoot.className)
    assertThat(STATIC_FIELD).isEqualTo(gcRoot.type)
    assertThat(
        gcRoot.referenceName == EXECUTOR_FIELD_1 || gcRoot.referenceName == EXECUTOR_FIELD_2
    ).isTrue()
  }

  @Test @Ignore("KeyedWeakReference has moved packages, need new heap dumps")
  fun excludeStatic() {
    excludedRefs.thread(ASYNC_TASK_THREAD)
        .named(ASYNC_TASK_THREAD)
    excludedRefs.staticField(ASYNC_TASK_CLASS, EXECUTOR_FIELD_1)
        .named(EXECUTOR_FIELD_1)
    excludedRefs.staticField(ASYNC_TASK_CLASS, EXECUTOR_FIELD_2)
        .named(EXECUTOR_FIELD_2)
    val result = analyze(heapDumpFile, excludedRefs)
    assertThat(result.leakFound).isTrue()
    assertThat(result.excludedLeak).isTrue()
    val elements = result.leakTrace!!.elements
    val exclusion = elements[0].exclusion

    val expectedExclusions = Arrays.asList(ASYNC_TASK_THREAD, EXECUTOR_FIELD_1, EXECUTOR_FIELD_2)
    assertThat(expectedExclusions.contains(exclusion.name)).isTrue()
  }

  companion object {
    private val ASYNC_TASK_THREAD = "AsyncTask #1"
    private val ASYNC_TASK_CLASS = "android.os.AsyncTask"
    private val EXECUTOR_FIELD_1 = "SERIAL_EXECUTOR"
    private val EXECUTOR_FIELD_2 = "sDefaultExecutor"
    @JvmStatic
    @Parameterized.Parameters
    fun data() = listOf(
        arrayOf(ASYNC_TASK_PRE_M),
        arrayOf(ASYNC_TASK_M),
        arrayOf(ASYNC_TASK_O)
    )
  }
}
