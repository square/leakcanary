//[leakcanary-android-instrumentation](../../../index.md)/[leakcanary](../index.md)/[LeakAssertions](index.md)/[assertNoLeaks](assert-no-leaks.md)

# assertNoLeaks

[androidJvm]\
fun [assertNoLeaks](assert-no-leaks.md)(tag: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) = NO_TAG)

Asserts that there are no leak in the heap at this point in time.

This method should be called on the instrumentation thread.

This method is may block the current thread for a significant amount of time, as it might need to dump the heap and analyze it.

If leaks are found, this method is expected to throw an exception, which will fail the test.

The specific details depend on what you configured in [DetectLeaksAssert.update](../-detect-leaks-assert/-companion/update.md).

[tag](assert-no-leaks.md) identifies the calling code, which can then be used for reporting purposes or to skip leak detection for specific tags in a subset of tests (see [SkipLeakDetection](../-skip-leak-detection/index.md)).
