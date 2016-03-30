package com.squareup.leakcanary;

import java.util.List;
import org.junit.Test;

import static com.squareup.leakcanary.TestUtil.HeapDumpFile.ASYNC_TASK;
import static com.squareup.leakcanary.TestUtil.HeapDumpFile.ASYNC_TASK_MPREVIEW2;
import static com.squareup.leakcanary.TestUtil.HeapDumpFile.ASYNC_TASK_M_POSTPREVIEW2;
import static com.squareup.leakcanary.TestUtil.findTrackedReferences;
import static org.assertj.core.api.Assertions.assertThat;

public class TrackedReferencesTest {

  @Test public void findsExpectedRef() {
    List<TrackedReference> trackedReferences = findTrackedReferences(ASYNC_TASK_M_POSTPREVIEW2);
    assertThat(trackedReferences).hasSize(1);
    TrackedReference firstRef = trackedReferences.get(0);
    assertThat(firstRef.key).isEqualTo(ASYNC_TASK_M_POSTPREVIEW2.referenceKey);
    assertThat(firstRef.className).isEqualTo("com.example.leakcanary.MainActivity");
  }

  @Test public void findsSeveralRefs() {
    List<TrackedReference> trackedReferences = findTrackedReferences(ASYNC_TASK);
    assertThat(trackedReferences).hasSize(2);
  }
}
