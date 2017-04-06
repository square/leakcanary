package com.squareup.leakcanary;

import java.util.List;
import org.junit.Test;

import static com.squareup.leakcanary.TestUtil.HeapDumpFile.ASYNC_TASK_PRE_M;
import static com.squareup.leakcanary.TestUtil.HeapDumpFile.ASYNC_TASK_M;
import static com.squareup.leakcanary.TestUtil.findTrackedReferences;
import static org.assertj.core.api.Assertions.assertThat;

public class TrackedReferencesTest {

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
}
