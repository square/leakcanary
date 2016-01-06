/*
 * Copyright (C) 2016 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.leakcanary;

import java.io.File;
import java.net.URL;

final class TestUtil {
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
