package com.squareup.leakcanary.tests;

import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import java.util.concurrent.CountDownLatch;

public final class Fragments {

  public static CountDownLatch waitForFragmentDetached(FragmentActivity activity) {
    final CountDownLatch latch = new CountDownLatch(1);
    final FragmentManager fragmentManager = activity.getSupportFragmentManager();
    fragmentManager.registerFragmentLifecycleCallbacks(
        new FragmentManager.FragmentLifecycleCallbacks() {
          @Override public void onFragmentDetached(FragmentManager fm, Fragment f) {
            fragmentManager.unregisterFragmentLifecycleCallbacks(this);
            latch.countDown();
          }
        }, false);
    return latch;
  }

  public static CountDownLatch waitForFragmentViewDestroyed(FragmentActivity activity) {
    final CountDownLatch latch = new CountDownLatch(1);
    final FragmentManager fragmentManager = activity.getSupportFragmentManager();
    fragmentManager.registerFragmentLifecycleCallbacks(
        new FragmentManager.FragmentLifecycleCallbacks() {
          @Override public void onFragmentViewDestroyed(FragmentManager fm, Fragment f) {
            fragmentManager.unregisterFragmentLifecycleCallbacks(this);
            latch.countDown();
          }
        }, false);
    return latch;
  }

  private Fragments() {
    throw new AssertionError();
  }
}
