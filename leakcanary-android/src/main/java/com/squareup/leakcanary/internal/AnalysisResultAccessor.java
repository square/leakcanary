package com.squareup.leakcanary.internal;

import com.squareup.leakcanary.AnalysisResult;
import com.squareup.leakcanary.CanaryLog;
import com.squareup.leakcanary.HeapDump;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AnalysisResultAccessor {

  public HeapDump renameHeapdump(HeapDump heapDump) {
    String fileName =
        new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS'.hprof'", Locale.US).format(new Date());

    File newFile = new File(heapDump.heapDumpFile.getParent(), fileName);
    boolean renamed = heapDump.heapDumpFile.renameTo(newFile);
    if (!renamed) {
      CanaryLog.d("Could not rename heap dump file %s to %s", heapDump.heapDumpFile.getPath(),
          newFile.getPath());
    }
    return heapDump.buildUpon().heapDumpFile(newFile).build();
  }

  public boolean saveResult(HeapDump heapDump, AnalysisResult result) {
    File resultFile = getResultFile(heapDump);
    FileOutputStream fos = null;
    try {
      fos = new FileOutputStream(resultFile);
      ObjectOutputStream oos = new ObjectOutputStream(fos);
      oos.writeObject(heapDump);
      oos.writeObject(result);
      return true;
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
    return false;
  }

  public File getResultFile(final HeapDump heapDump) {
    return new File(heapDump.heapDumpFile.getParentFile(),
        heapDump.heapDumpFile.getName() + ".result");
  }

  public Leak loadLeak(File resultFile) {
    FileInputStream fis = null;
    try {
      fis = new FileInputStream(resultFile);
      ObjectInputStream ois = new ObjectInputStream(fis);
      HeapDump heapDump = (HeapDump) ois.readObject();
      AnalysisResult result = (AnalysisResult) ois.readObject();
      return new Leak(heapDump, result, resultFile);
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
}
