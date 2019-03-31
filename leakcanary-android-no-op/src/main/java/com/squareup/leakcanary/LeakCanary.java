package com.squareup.leakcanary;

import android.app.Application;
import android.content.Context;
import androidx.annotation.NonNull;

/**
 * A no-op version of {@link LeakCanary} that can be used in release builds.
 */
public final class LeakCanary {

  public static @NonNull RefWatcher install(@NonNull Application application) {
    return RefWatcher.DISABLED;
  }

  public static @NonNull RefWatcher installedRefWatcher() {
    return RefWatcher.DISABLED;
  }

  public static boolean isInAnalyzerProcess(@NonNull Context context) {
    return false;
  }

  private LeakCanary() {
    throw new AssertionError();
  }
}
