package com.example.leakcanary;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = TestExampleApplication.class)
public class SampleTest {
  @Test public void testTheThing() throws Exception {
    ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();
    controller.get().findViewById(R.id.async_work).performClick();
    controller.stop();
    controller.destroy();
  }
}
