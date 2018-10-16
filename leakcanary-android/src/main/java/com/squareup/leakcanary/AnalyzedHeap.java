package com.squareup.leakcanary;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * Wraps a {@link HeapDump} and corresponding {@link AnalysisResult}.
 */
public final class AnalyzedHeap {

  @Nullable public static File save(@NonNull HeapDump heapDump, @NonNull AnalysisResult result) {
    File analyzedHeapfile = new File(heapDump.heapDumpFile.getParentFile(),
        heapDump.heapDumpFile.getName() + ".result");
    FileOutputStream fos = null;
    try {
      fos = new FileOutputStream(analyzedHeapfile);
      ObjectOutputStream oos = new ObjectOutputStream(fos);
      oos.writeObject(heapDump);
      oos.writeObject(result);
      return analyzedHeapfile;
    } catch (IOException e) {
      CanaryLog.d(e, "Could not save leak analysis result to disk.");
    } finally {
      if (fos != null) {
        try {
          fos.close();
        } catch (IOException ignored) {
        }
      }
    }
    return null;
  }

  @Nullable public static AnalyzedHeap load(@NonNull File resultFile) {
    FileInputStream fis = null;
    try {
      fis = new FileInputStream(resultFile);
      ObjectInputStream ois = new ObjectInputStream(fis);
      HeapDump heapDump = (HeapDump) ois.readObject();
      AnalysisResult result = (AnalysisResult) ois.readObject();
      return new AnalyzedHeap(heapDump, result, resultFile);
    } catch (IOException | ClassNotFoundException e) {
      // Likely a change in the serializable result class.
      // Let's remove the files, we can't read them anymore.
      boolean deleted = resultFile.delete();
      if (deleted) {
        CanaryLog.d(e, "Could not read result file %s, deleted it.", resultFile);
      } else {
        CanaryLog.d(e, "Could not read result file %s, could not delete it either.",
            resultFile);
      }
    } finally {
      if (fis != null) {
        try {
          fis.close();
        } catch (IOException ignored) {
        }
      }
    }
    return null;
  }

  @NonNull public final HeapDump heapDump;
  @NonNull public final AnalysisResult result;
  @NonNull public final File selfFile;
  public final boolean heapDumpFileExists;
  public final long selfLastModified;

  public AnalyzedHeap(@NonNull HeapDump heapDump, @NonNull AnalysisResult result,
      @NonNull File analyzedHeapFile) {
    this.heapDump = heapDump;
    this.result = result;
    this.selfFile = analyzedHeapFile;
    heapDumpFileExists = heapDump.heapDumpFile.exists();
    selfLastModified = analyzedHeapFile.lastModified();
  }
}
