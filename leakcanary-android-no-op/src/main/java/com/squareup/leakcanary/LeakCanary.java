package com.squareup.leakcanary;

import android.app.Application;
import android.content.Context;

/**
 * A no-op version of {@link LeakCanary} that can be used in release builds.
 */
public final class LeakCanary {

  public static RefWatcher install(Application application) {
    return RefWatcher.DISABLED;
  }

  public static boolean isInAnalyzerProcess(Context context) {
    return false;
  }

  private LeakCanary() {
    throw new AssertionError();
  }
}
