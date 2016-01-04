package com.squareup.leakcanary;

import android.content.Context;

/**
 * A no-op version of {@link Application} that can be used in release builds.
 */
public class Application extends android.app.Application {

  public static RefWatcher getRefWatcher(Context context) {
    return RefWatcher.DISABLED;
  }
}
