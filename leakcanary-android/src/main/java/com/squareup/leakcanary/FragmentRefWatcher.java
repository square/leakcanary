package com.squareup.leakcanary;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager.FragmentLifecycleCallbacks;
import android.os.Build.VERSION_CODES;

@TargetApi(VERSION_CODES.O)
final class FragmentRefWatcher {

  private final RefWatcher refWatcher;

  FragmentRefWatcher(RefWatcher refWatcher) {
    this.refWatcher = refWatcher;
  }

  void watch(Activity activity) {
    activity.getFragmentManager().registerFragmentLifecycleCallbacks(fragmentLifecycleCallbacks, true);
  }

  void unwatch(Activity activity) {
    activity.getFragmentManager().unregisterFragmentLifecycleCallbacks(fragmentLifecycleCallbacks);
  }

  private final FragmentLifecycleCallbacks fragmentLifecycleCallbacks = new android.app.FragmentManager.FragmentLifecycleCallbacks() {
    @Override
    public void onFragmentDestroyed(android.app.FragmentManager fm, Fragment f) {
      refWatcher.watch(f);
    }
  };


}
