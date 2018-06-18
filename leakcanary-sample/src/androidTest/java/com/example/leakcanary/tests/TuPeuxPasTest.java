package com.example.leakcanary.tests;

import android.support.test.rule.ActivityTestRule;
import com.example.leakcanary.MainActivity;
import com.example.leakcanary.R;
import org.junit.Rule;
import org.junit.Test;

import static android.support.test.espresso.Espresso.onView;
import static android.support.test.espresso.action.ViewActions.click;
import static android.support.test.espresso.assertion.ViewAssertions.matches;
import static android.support.test.espresso.matcher.ViewMatchers.withId;
import static android.support.test.espresso.matcher.ViewMatchers.withText;

/**
 * This UI test looks like it should succeed, but it will actually fail because
 * it triggers a leak.
 *
 * Run this test with:
 *
 * ./gradlew leakcanary-sample:connectedCheck
 *
 * To set this up, we installed a special RefWatcher dedicated to detecting leaks in
 * instrumentation tests in {@link InstrumentationExampleApplication}, and then added the FailTestOnLeakRunListener
 * to the config of our build.gradle:
 *
 * testInstrumentationRunnerArgument "listener", "com.squareup.leakcanary.FailTestOnLeakRunListener"
 *
 *
 * Why is this class named "TuPeuxPasTest"?
 *
 * This test fails, intentionally. In French, "Tu peux pas test" could mean "you cannot test"
 * written with poor grammar. Except, that's not what it means.
 * If you're curious, interested in French and have time to waste:
 * https://www.youtube.com/watch?v=DZZpbmAc-0A
 * https://www.youtube.com/watch?v=nHeAA6X-XUQ
 */
public class TuPeuxPasTest {

  @Rule
  public ActivityTestRule<MainActivity> activityRule = new ActivityTestRule<>(
      MainActivity.class);

  @Test
  public void clickAsyncWork() {
    onView(withId(R.id.async_work)).perform(click());
  }

  @Test
  public void asyncButtonHasStartText() {
    onView(withId(R.id.async_work)).check(matches(withText(R.string.start_async_work)));
  }
}
