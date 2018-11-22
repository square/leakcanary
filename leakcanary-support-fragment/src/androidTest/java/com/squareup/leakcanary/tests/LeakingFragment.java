package com.squareup.leakcanary.tests;

import android.support.test.InstrumentationRegistry;
import android.support.v4.app.Fragment;

public class LeakingFragment extends Fragment {

  public static void add(final TestActivity activity) {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
      @Override public void run() {
        leakingFragment = new LeakingFragment();
        activity.getSupportFragmentManager()
            .beginTransaction()
            .add(0, leakingFragment)
            .commitNow();
      }
    });
  }

  private static LeakingFragment leakingFragment;
}
