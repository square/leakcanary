package com.squareup.leakcanary;

import android.content.Context;
import android.os.Debug;
import com.squareup.leakcanary.internal.AutopsyService;
import java.io.File;
import java.util.List;

import static com.squareup.leakcanary.internal.LeakCanaryInternals.setEnabled;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

public class Doctor {

  private final GcTrigger gcTrigger;
  private final Context context;
  private final Class<? extends AbstractAutopsyResultService> listenerServiceClass;
  private final List<OOMAutopsy.ZombieMatcher> zombieMatchers;
  private final ExcludedRefs excludedRefs;

  public Doctor(GcTrigger gcTrigger, Context context,
      Class<? extends AbstractAutopsyResultService> listenerServiceClass,
      List<OOMAutopsy.ZombieMatcher> zombieMatchers, ExcludedRefs excludedRefs) {
    this.gcTrigger = gcTrigger;
    this.context = context;
    this.listenerServiceClass = listenerServiceClass;
    this.zombieMatchers = zombieMatchers;
    this.excludedRefs = excludedRefs;
    setEnabled(context, listenerServiceClass, true);
    setEnabled(context, Autopsy.class, true);
  }

  public void performDiagnostic() {
    long gcStartNanoTime = System.nanoTime();

    gcTrigger.runGc();

    long startDumpHeap = System.nanoTime();
    long gcDurationMs = NANOSECONDS.toMillis(startDumpHeap - gcStartNanoTime);

    File heapDumpFile = getHeapDumpFile();
    if (heapDumpFile.exists()) {
      // TODO Remove file if older than X.
      // TODO Otherwise log.
      return;
    }

    try {
      Debug.dumpHprofData(heapDumpFile.getAbsolutePath());
    } catch (Exception e) {
      if (heapDumpFile.exists()) {
        heapDumpFile.delete();
      }
      return;
    }

    long heapDumpDurationMs = NANOSECONDS.toMillis(System.nanoTime() - startDumpHeap);

    Bag bag = new Bag(heapDumpFile, excludedRefs, zombieMatchers, gcDurationMs, heapDumpDurationMs);

    AutopsyService.runAnalysis(context, bag, listenerServiceClass);
  }

  private File getHeapDumpFile() {
    return new File(context.getFilesDir(), "autopsy_heapdump.hprof");
  }
}
