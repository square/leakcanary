package com.squareup.leakcanary;

import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentManager.FragmentLifecycleCallbacks;

final class SupportFragmentRefWatcher {

  private final RefWatcher refWatcher;

  SupportFragmentRefWatcher(RefWatcher refWatcher) {
    this.refWatcher = refWatcher;
  }

  void watch(FragmentActivity activity) {
      activity.getSupportFragmentManager().registerFragmentLifecycleCallbacks(supportFragmentLifecycleCallbacks, true);
  }

  void unwatch(FragmentActivity activity) {
      activity.getSupportFragmentManager().unregisterFragmentLifecycleCallbacks(supportFragmentLifecycleCallbacks);
  }

  private final FragmentLifecycleCallbacks supportFragmentLifecycleCallbacks = new FragmentLifecycleCallbacks() {
    @Override
    public void onFragmentDestroyed(FragmentManager fm, android.support.v4.app.Fragment f) {
      refWatcher.watch(f);
    }
  };

}
