package com.squareup.leakcanary.tests;

import android.content.Intent;
import android.support.test.InstrumentationRegistry;
import android.support.test.espresso.Espresso;
import android.support.test.espresso.matcher.ViewMatchers;
import android.support.test.rule.ActivityTestRule;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.widget.TextView;

import com.squareup.leakcanary.InstrumentationLeakDetector;
import com.squareup.leakcanary.InstrumentationLeakResults;
import com.squareup.leakcanary.support.fragment.R;

import org.junit.Rule;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;

import static android.support.test.espresso.action.ViewActions.click;

public class FragmentLeakTest {

  @Rule
  public ActivityTestRule<FragmentActivity> activityRule =
      new ActivityTestRule<>(FragmentActivity.class, true, false);

  @Test
  public void fragmentShouldLeak() throws InterruptedException {
    // Fragment detach happens after fragment destroy.
    // This allows us to wait until the destroyed fragment has been passed to LeakCanary
    activityRule.launchActivity(new Intent(InstrumentationRegistry.getTargetContext(), TestActivity.class));
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

  @Test
  public void fragmentViewShouldLeak() {
    activityRule.launchActivity(new Intent(InstrumentationRegistry.getTargetContext(), NastyActivity.class));

    Espresso.onView(ViewMatchers.withId(R.id.add)).perform(click());
    Espresso.onView(ViewMatchers.withId(R.id.add)).perform(click());

    InstrumentationLeakDetector leakDetector = new InstrumentationLeakDetector();
    InstrumentationLeakResults results = leakDetector.detectLeaks();

    if (results.detectedLeaks.size() != 1) {
      throw new AssertionError("Expected exactly one leak, not " + results.detectedLeaks.size());
    }

    InstrumentationLeakResults.Result firstResult = results.detectedLeaks.get(0);

    String leakingClassName = firstResult.analysisResult.className;

    if (!leakingClassName.equals(TextView.class.getName())) {
      throw new AssertionError("Expected a leak of NastyFragment's view, not " + leakingClassName);
    }
  }
}
