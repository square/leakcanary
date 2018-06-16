package com.squareup.leakcanary;

public interface AnalyzerProgressListener {

  AnalyzerProgressListener NONE = new AnalyzerProgressListener() {
    @Override public void onProgressUpdate(Step step) {
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

  void onProgressUpdate(Step step);
}