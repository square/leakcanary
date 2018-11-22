package com.squareup.leakcanary.tests;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.support.test.InstrumentationRegistry;
import android.support.test.rule.ActivityTestRule;
import android.view.View;
import com.squareup.leakcanary.InstrumentationLeakDetector;
import com.squareup.leakcanary.InstrumentationLeakResults;
import com.squareup.leakcanary.LeakCanary;
import com.squareup.leakcanary.internal.ActivityLifecycleCallbacksAdapter;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static com.squareup.leakcanary.tests.Fragments.waitForFragmentDetached;
import static com.squareup.leakcanary.tests.Fragments.waitForFragmentViewDestroyed;

public class FragmentLeakTest {

  private static final boolean TOUCH_MODE = true;
  private static final boolean LAUNCH_ACTIVITY = true;

  @Rule
  public ActivityTestRule<TestActivity> activityRule =
      new ActivityTestRule<>(TestActivity.class, !TOUCH_MODE, !LAUNCH_ACTIVITY);

  @Before public void setUp() {
    LeakCanary.installedRefWatcher().clearWatchedReferences();
  }

  @After public void tearDown() {
    LeakCanary.installedRefWatcher().clearWatchedReferences();
  }

  @Test
  public void fragmentShouldLeak() throws InterruptedException {
    startActivityAndWaitForCreate();

    LeakingFragment.add(activityRule.getActivity());

    CountDownLatch waitForFragmentDetach = waitForFragmentDetached(activityRule.getActivity());
    activityRule.finishActivity();
    waitForFragmentDetach.await();

    assertLeak(LeakingFragment.class);
  }

  @Test
  public void fragmentViewShouldLeak() throws InterruptedException {
    startActivityAndWaitForCreate();
    TestActivity activity = activityRule.getActivity();

    CountDownLatch waitForFragmentViewDestroyed = waitForFragmentViewDestroyed(activity);
    // First, add a new fragment
    ViewLeakingFragment.addToBackstack(activity);
    // Then, add a new fragment again, which destroys the view of the previous fragment and puts
    // that fragment in the backstack.
    ViewLeakingFragment.addToBackstack(activity);
    waitForFragmentViewDestroyed.await();

    assertLeak(View.class);
  }

  private void startActivityAndWaitForCreate() {
    final CountDownLatch waitForActivityOnCreate = new CountDownLatch(1);
    final Application app =
        (Application) InstrumentationRegistry.getTargetContext().getApplicationContext();
    app.registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacksAdapter() {
      @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        app.unregisterActivityLifecycleCallbacks(this);
        waitForActivityOnCreate.countDown();
      }
    });

    activityRule.launchActivity(null);

    try {
      waitForActivityOnCreate.await();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  private void assertLeak(Class<?> expectedLeakClass) {
    InstrumentationLeakDetector leakDetector = new InstrumentationLeakDetector();
    InstrumentationLeakResults results = leakDetector.detectLeaks();

    if (results.detectedLeaks.size() != 1) {
      throw new AssertionError(
          "Expected exactly one leak, not " + results.detectedLeaks.size() + resultsAsString(
              results.detectedLeaks));
    }

    InstrumentationLeakResults.Result firstResult = results.detectedLeaks.get(0);

    String leakingClassName = firstResult.analysisResult.className;

    if (!leakingClassName.equals(expectedLeakClass.getName())) {
      throw new AssertionError(
          "Expected a leak of " + expectedLeakClass + ", not " + leakingClassName + resultsAsString(
              results.detectedLeaks));
    }
  }

  private String resultsAsString(List<InstrumentationLeakResults.Result> results) {
    Context context = InstrumentationRegistry.getTargetContext();
    StringBuilder message = new StringBuilder();
    message.append("\nLeaks found:\n##################\n");
    for (InstrumentationLeakResults.Result detectedLeak : results) {
      message.append(
          LeakCanary.leakInfo(context, detectedLeak.heapDump, detectedLeak.analysisResult,
              false));
    }
    message.append("\n##################\n");
    return message.toString();
  }
}
