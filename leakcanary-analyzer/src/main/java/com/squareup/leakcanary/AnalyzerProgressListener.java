package com.squareup.leakcanary;

import android.support.annotation.NonNull;

public interface AnalyzerProgressListener {

  @NonNull AnalyzerProgressListener NONE = new AnalyzerProgressListener() {
    @Override public void onProgressUpdate(@NonNull Step step) {
    }
  };

  // These steps should be defined in the order in which they occur.
  enum Step {
    READING_HEAP_DUMP_FILE,
    PARSING_HEAP_DUMP,
    DEDUPLICATING_GC_ROOTS,
    FINDING_LEAKING_REF,
    FINDING_SHORTEST_PATH,
    BUILDING_LEAK_TRACE,
    COMPUTING_DOMINATORS,
    COMPUTING_BITMAP_SIZE,
  }

  void onProgressUpdate(@NonNull Step step);
}