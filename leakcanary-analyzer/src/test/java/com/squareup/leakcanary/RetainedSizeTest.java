package com.squareup.leakcanary;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Collection;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static com.squareup.leakcanary.TestUtil.HeapDumpFile.ASYNC_TASK_M;
import static com.squareup.leakcanary.TestUtil.HeapDumpFile.ASYNC_TASK_O;
import static com.squareup.leakcanary.TestUtil.HeapDumpFile.ASYNC_TASK_PRE_M;
import static com.squareup.leakcanary.TestUtil.analyze;
import static org.junit.Assert.assertEquals;

/**
 * This test makes sure there is no regression on the retained size calculation.
 */
@RunWith(Parameterized.class) //
public class RetainedSizeTest {

  @Parameterized.Parameters public static Collection<Object[]> data() {
    return Arrays.asList(new Object[][] {
        { ASYNC_TASK_PRE_M, 207_407 }, //
        { ASYNC_TASK_M, 1_870 }, //
        { ASYNC_TASK_O, 753 }, //
    });
  }

  private final TestUtil.HeapDumpFile heapDumpFile;
  private final long expectedRetainedHeapSize;
  ExcludedRefs.BuilderWithParams excludedRefs;

  public RetainedSizeTest(TestUtil.HeapDumpFile heapDumpFile, long expectedRetainedHeapSize) {
    this.heapDumpFile = heapDumpFile;
    this.expectedRetainedHeapSize = expectedRetainedHeapSize;
  }

  @Before public void setUp() {
    excludedRefs = new ExcludedRefs.BuilderWithParams().clazz(WeakReference.class.getName())
        .alwaysExclude()
        .clazz("java.lang.ref.FinalizerReference")
        .alwaysExclude();
  }

  @Test public void leakFound() {
    AnalysisResult result = analyze(heapDumpFile, excludedRefs);
    assertEquals(expectedRetainedHeapSize, result.retainedHeapSize);
  }
}
