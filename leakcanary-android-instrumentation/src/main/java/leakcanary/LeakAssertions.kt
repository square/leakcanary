package leakcanary

object LeakAssertions {

  /**
   * Asserts that there are no leak in the heap at this point in time.
   *
   * This method should be called on the instrumentation thread.
   *
   * This method is may block the current thread for a significant amount of time,
   * as it might need to dump the heap and analyze it.
   *
   * If leaks are found, this method is expected to throw an exception, which will fail the test.
   *
   * The specific details depend on what you configured in [DetectLeaksAssert.update].
   *
   * [tag] identifies the calling code, which can then be used for reporting purposes or to skip
   * leak detection for specific tags in a subset of tests (see [SkipLeakDetection]).
   */
  fun assertNoLeaks(tag: String = NO_TAG) {
    DetectLeaksAssert.delegate.assertNoLeaks(tag)
  }

  const val NO_TAG = ""
}





