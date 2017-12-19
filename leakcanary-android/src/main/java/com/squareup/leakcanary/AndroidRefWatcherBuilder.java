package com.squareup.leakcanary;

import android.app.Application;
import android.content.Context;
import java.util.concurrent.TimeUnit;

import static com.squareup.leakcanary.RefWatcher.DISABLED;
import static java.util.concurrent.TimeUnit.SECONDS;

/** A {@link RefWatcherBuilder} with appropriate Android defaults. */
public final class AndroidRefWatcherBuilder extends RefWatcherBuilder<AndroidRefWatcherBuilder> {

  private static final long DEFAULT_WATCH_DELAY_MILLIS = SECONDS.toMillis(5);

  private final Context context;

  AndroidRefWatcherBuilder(Context context) {
    this.context = context.getApplicationContext();
  }

  /**
   * Sets a custom {@link AbstractAnalysisResultService} to listen to analysis results. This
   * overrides any call to {@link #heapDumpListener(HeapDump.Listener)}.
   */
  public AndroidRefWatcherBuilder listenerServiceClass(
      Class<? extends AbstractAnalysisResultService> listenerServiceClass) {
    return heapDumpListener(new ServiceHeapDumpListener(context, listenerServiceClass));
  }

  /**
   * Sets a custom delay for how long the {@link RefWatcher} should wait until it checks if a
   * tracked object has been garbage collected. This overrides any call to {@link
   * #watchExecutor(WatchExecutor)}.
   */
  public AndroidRefWatcherBuilder watchDelay(long delay, TimeUnit unit) {
    return watchExecutor(new AndroidWatchExecutor(unit.toMillis(delay)));
  }

  /**
   * Sets the maximum number of heap dumps stored. This overrides any call to {@link
   * #heapDumper(HeapDumper)} as well as any call to
   * {@link LeakCanary#setDisplayLeakActivityDirectoryProvider(LeakDirectoryProvider)})}
   *
   * @throws IllegalArgumentException if maxStoredHeapDumps < 1.
   */
  public AndroidRefWatcherBuilder maxStoredHeapDumps(int maxStoredHeapDumps) {
    LeakDirectoryProvider leakDirectoryProvider =
        new DefaultLeakDirectoryProvider(context, maxStoredHeapDumps);
    LeakCanary.setDisplayLeakActivityDirectoryProvider(leakDirectoryProvider);
    return heapDumper(new AndroidHeapDumper(context, leakDirectoryProvider));
  }

  /**
   * Creates a {@link RefWatcher} instance and starts watching activity references (on ICS+).
   */
  public RefWatcher buildAndInstall() {
    RefWatcher refWatcher = build();
    if (refWatcher != DISABLED) {
      LeakCanary.enableDisplayLeakActivity(context);
      ActivityRefWatcher.install((Application) context, refWatcher);
    }
    return refWatcher;
  }

  @Override protected boolean isDisabled() {
    return LeakCanary.isInAnalyzerProcess(context);
  }

  @Override protected HeapDumper defaultHeapDumper() {
    LeakDirectoryProvider leakDirectoryProvider = new DefaultLeakDirectoryProvider(context);
    return new AndroidHeapDumper(context, leakDirectoryProvider);
  }

  @Override protected DebuggerControl defaultDebuggerControl() {
    return new AndroidDebuggerControl();
  }

  @Override protected HeapDump.Listener defaultHeapDumpListener() {
    return new ServiceHeapDumpListener(context, DisplayLeakService.class);
  }

  @Override protected ExcludedRefs defaultExcludedRefs() {
    return AndroidExcludedRefs.createAppDefaults().build();
  }

  @Override protected WatchExecutor defaultWatchExecutor() {
    return new AndroidWatchExecutor(DEFAULT_WATCH_DELAY_MILLIS);
  }
}
