package com.squareup.leakcanary;

import java.io.File;
import java.net.URL;

class TestUtil {
  static File fileFromName(String filename) {
    ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
    URL url = classLoader.getResource(filename);
    return new File(url.getPath());
  }

  static AnalysisResult analyze(File heapDumpFile, String referenceKey, ExcludedRefs.Builder excludedRefs) {
    HeapAnalyzer heapAnalyzer = new HeapAnalyzer(excludedRefs.build());
    AnalysisResult result = heapAnalyzer.checkForLeak(heapDumpFile, referenceKey);
    if (result.failure != null) {
      result.failure.printStackTrace();
    }
    if (result.leakTrace != null) {
      System.out.println(result.leakTrace);
    }
    return result;
  }

  private TestUtil() {
    throw new AssertionError();
  }
}
