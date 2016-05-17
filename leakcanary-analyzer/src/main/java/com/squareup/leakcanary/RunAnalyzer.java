/*
 * Copyright (C) 2015 Square, Inc.
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
import java.lang.ref.WeakReference;
import java.util.List;

/**
 * Analyzes exported heap dumps in shell.
 */
public class RunAnalyzer {
  private final ExcludedRefs.BuilderWithParams excludedRefs;
  private final String fileName;

  private RunAnalyzer(String fileName) {
    this.excludedRefs = new ExcludedRefs.BuilderWithParams().clazz(WeakReference.class.getName())
            .alwaysExclude()
            .clazz("java.lang.ref.FinalizerReference")
            .alwaysExclude();
    this.fileName = fileName;
  }

  static File fileFromName(String filename) {
    return new File(filename);
  }

  void run() {
    analyze(fileName, excludedRefs);
  }

  List<AnalysisResult> analyze(String fileName, ExcludedRefs.BuilderWithParams excludedRefs) {
    File file = fileFromName(fileName);
    HeapAnalyzer heapAnalyzer = new HeapAnalyzer(excludedRefs.build());
    List<AnalysisResult> results = heapAnalyzer.checkForLeaks(file);
    for (AnalysisResult result : results) {
      if (result.failure != null) {
        result.failure.printStackTrace();
      }
      if (result.leakTrace != null) {
        System.out.println(result.leakTrace);
      }
    }
    return results;
  }

  public static void main(String[] args) {
    if (args.length <= 0) {
      System.out.println("Usage:");
      System.out.println("java -cp <jar-path>:jar com.squareup.leakcanary.RunAnalyzer <hprof file path>");
      System.out.println("You should specify hprof file path.");
      System.exit(1);
      return;
    }
    new RunAnalyzer(args[0]).run();
  }
}

