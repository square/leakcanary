package com.squareup.leakcanary.tests;

import android.support.test.rule.ActivityTestRule;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import com.squareup.leakcanary.InstrumentationLeakDetector;
import com.squareup.leakcanary.InstrumentationLeakResults;
import java.util.concurrent.CountDownLatch;
import org.junit.Rule;
import org.junit.Test;

public class FragmentLeakTest {

  @Rule
  public ActivityTestRule<TestActivity> activityRule = new ActivityTestRule<>(
      TestActivity.class);

  @Test
  public void fragmentShouldLeak() throws InterruptedException {
    // Fragment detach happens after fragment destroy.
    // This allows us to wait until the destroyed fragment has been passed to LeakCanary
    final CountDownLatch waitForFragmentDetach = new CountDownLatch(1);
    activityRule.getActivity().getSupportFragmentManager().registerFragmentLifecycleCallbacks(
        new FragmentManager.FragmentLifecycleCallbacks() {
          @Override public void onFragmentDetached(FragmentManager fm, Fragment f) {
            waitForFragmentDetach.countDown();
          }
        }, false);

    activityRule.finishActivity();

    waitForFragmentDetach.await();

    InstrumentationLeakDetector leakDetector = new InstrumentationLeakDetector();
    InstrumentationLeakResults results = leakDetector.detectLeaks();

    if (results.detectedLeaks.size() != 1) {
      throw new AssertionError("Expected exactly one leak, not " + results.detectedLeaks.size());
    }

    InstrumentationLeakResults.Result firstResult = results.detectedLeaks.get(0);

    String leakingClassName = firstResult.analysisResult.className;

    if (!leakingClassName.equals(TestFragment.class.getName())) {
      throw new AssertionError("Expected a leak of TestFragment, not " + leakingClassName);
    }
  }
}
