package com.squareup.leakcanary.internal;

import com.squareup.leakcanary.AnalysisResult;
import com.squareup.leakcanary.HeapDump;

import java.io.File;

public class Leak {
  public final HeapDump heapDump;
  public final AnalysisResult result;
  public final File resultFile;
  public final boolean heapDumpFileExists;
  public final long resultFileLastModified;

  Leak(HeapDump heapDump, AnalysisResult result, File resultFile) {
    this.heapDump = heapDump;
    this.result = result;
    this.resultFile = resultFile;
    heapDumpFileExists = heapDump.heapDumpFile.exists();
    resultFileLastModified = resultFile.lastModified();
  }
}
