package com.squareup.leakcanary;

import com.squareup.haha.perflib.ClassObj;
import com.squareup.haha.perflib.HahaSpy;
import com.squareup.haha.perflib.Heap;
import com.squareup.haha.perflib.HprofParser;
import com.squareup.haha.perflib.Instance;
import com.squareup.haha.perflib.RootObj;
import com.squareup.haha.perflib.RootType;
import com.squareup.haha.perflib.Snapshot;
import com.squareup.haha.perflib.io.HprofBuffer;
import com.squareup.haha.perflib.io.MemoryMappedFileBuffer;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static com.squareup.leakcanary.HahaHelper.threadName;
import static com.squareup.leakcanary.TestUtil.HeapDumpFile.ASYNC_TASK_M;
import static com.squareup.leakcanary.TestUtil.HeapDumpFile.ASYNC_TASK_PRE_M;
import static com.squareup.leakcanary.TestUtil.HeapDumpFile.GC_ROOT_IN_NON_PRIMARY_HEAP;
import static com.squareup.leakcanary.TestUtil.HeapDumpFile.NOT_FOUND;
import static com.squareup.leakcanary.TestUtil.analyze;
import static com.squareup.leakcanary.TestUtil.findTrackedReferences;
import static org.assertj.core.api.Assertions.assertThat;

@RunWith(JUnit4.class)
public class HeapAnalyzerHeapDumpTest {

  @Test public void findsExpectedRef() {
    List<TrackedReference> trackedReferences = findTrackedReferences(ASYNC_TASK_M);
    assertThat(trackedReferences).hasSize(1);
    TrackedReference firstRef = trackedReferences.get(0);
    assertThat(firstRef.key).isEqualTo(ASYNC_TASK_M.referenceKey);
    assertThat(firstRef.className).isEqualTo("com.example.leakcanary.MainActivity");
  }

  @Test public void findsSeveralRefs() {
    List<TrackedReference> trackedReferences = findTrackedReferences(ASYNC_TASK_PRE_M);
    assertThat(trackedReferences).hasSize(2);
  }

  @Test public void leakFoundWithGcRootInNonPrimaryHeap() {
    AnalysisResult result = analyze(GC_ROOT_IN_NON_PRIMARY_HEAP);
    assertThat(result.leakFound).isTrue();
  }

  @Test public void heapdump_with_missing_thread_object() {
    // To get this test to pass, set up a non suspending breakpoint in Heap.addThread that triggers
    // on serialNumber == 1 and evaluates mThreads.put(2, thread).
    AnalysisResult result = analyze(NOT_FOUND);
    assertThat(result.leakFound).isTrue();
  }
}
