package com.squareup.leakcanary.tests;

import androidx.fragment.app.Fragment;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

public class LeakingFragment extends Fragment {

  public static void add(final TestActivity activity) {
    getInstrumentation().runOnMainSync(new Runnable() {
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
