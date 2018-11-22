package com.squareup.leakcanary;

import java.util.Date;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests that the {@link InstrumentationLeakDetector} can detect leaks
 * in instrumentation tests
 */
public class InstrumentationLeakDetectorTest {

  private static Object leaking;

  @Before public void setUp() {
    LeakCanary.installedRefWatcher().clearWatchedReferences();
  }

  @After public void tearDown() {
    LeakCanary.installedRefWatcher().clearWatchedReferences();
  }

  @Test public void detectsLeak() {
    leaking = new Date();
    RefWatcher refWatcher = LeakCanary.installedRefWatcher();
    refWatcher.watch(leaking);

    InstrumentationLeakDetector leakDetector = new InstrumentationLeakDetector();
    InstrumentationLeakResults results = leakDetector.detectLeaks();

    if (results.detectedLeaks.size() != 1) {
      throw new AssertionError("Expected exactly one leak, not " + results.detectedLeaks.size());
    }

    InstrumentationLeakResults.Result firstResult = results.detectedLeaks.get(0);

    String leakingClassName = firstResult.analysisResult.className;

    if (!leakingClassName.equals(Date.class.getName())) {
      throw new AssertionError("Expected a leak of Date, not " + leakingClassName);
    }
  }
}
