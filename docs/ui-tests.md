# Leak detection in UI tests

Running leak detection in UI tests means you can detect memory leaks automatically in Continuous
Integration prior to new leaks being merged into the codebase.

!!! info "Test environment detection"
    In debug builds, LeakCanary looks for retained instances continuously, freezes the VM to take
    a heap dump after a watched object has been retained for 5 seconds, then performs the analysis
    in a background thread and reports the result using notifications. That behavior isn't well suited
    for UI tests, so LeakCanary is automatically disabled when JUnit is on the runtime classpath
    (see [test environment detection](recipes.md#leakcanary-test-environment-detection)).

## Getting started

LeakCanary provides an artifact dedicated to detecting leaks in UI tests:

```groovy
androidTestImplementation 'com.squareup.leakcanary:leakcanary-android-instrumentation:{{ leak_canary.release }}'
// You still need to include the LeakCanary artifact in your app:
debugImplementation 'com.squareup.leakcanary:leakcanary-android:{{ leak_canary.release }}'
```

You can then call `LeakAssertions.assertNoLeak()` at any point in your tests to check for leaks:

 ```kotlin
 class CartTest {

   @Test
   fun addItemToCart() {
     // ...
     LeakAssertions.assertNoLeak()
   }
 }
 ```

If retained instances are detected, LeakCanary will dump and analyze the heap. If application leaks
are found, `LeakAssertions.assertNoLeak()` will throw a `NoLeakAssertionFailedError`.

```
leakcanary.NoLeakAssertionFailedError: Application memory leaks were detected:
====================================
HEAP ANALYSIS RESULT
====================================
1 APPLICATION LEAKS

┬───
│ GC Root: System class
│
├─ com.example.MySingleton class
│    Leaking: NO (a class is never leaking)
│    ↓ static MySingleton.leakedView
│                         ~~~~~~~~~~
├─ android.widget.TextView instance
│    Leaking: YES (View.mContext references a destroyed activity)
│    ↓ TextView.mContext
╰→ com.example.MainActivity instance
     Leaking: YES (Activity#mDestroyed is true)
====================================
  at leakcanary.AndroidDetectLeaksAssert.assertNoLeaks(AndroidDetectLeaksAssert.kt:34)
  at leakcanary.LeakAssertions.assertNoLeaks(LeakAssertions.kt:21)
  at com.example.CartTest.addItemToCart(TuPeuxPasTest.kt:41)
```

!!! bug "Obfuscated instrumentation tests"
    When running instrumentation tests against obfuscated release builds, the LeakCanary classes end
    up spread over the test APK and the main APK. Unfortunately there is a
    [bug](https://issuetracker.google.com/issues/126429384) in the Android Gradle Plugin that leads
    to runtime crashes when running tests, because code from the main APK is changed without the
    using code in the test APK being updated accordingly. If you run into this issue, setting up the
    [Keeper plugin](https://slackhq.github.io/keeper/) should fix it.


## Test rule

 You can use the `DetectLeaksAfterTestSuccess` test rule to automatically call
 `LeakAssertions.assertNoLeak()` at the end of a test:

 ```kotlin
 class CartTest {
   @get:Rule
   val rule = DetectLeaksAfterTestSuccess()

   @Test
   fun addItemToCart() {
     // ...
   }
 }
 ```

 You can call also `LeakAssertions.assertNoLeak()` as many times as you want in a single test:

 ```kotlin
 class CartTest {
   @get:Rule
   val rule = DetectLeaksAfterTestSuccess()

   // This test has 3 leak assertions (2 in the test + 1 from the rule).
   @Test
   fun addItemToCart() {
     // ...
     LeakAssertions.assertNoLeak()
     // ...
     LeakAssertions.assertNoLeak()
     // ...
   }
 }
 ```

## Skipping leak detection

Use `@SkipLeakDetection` to disable `LeakAssertions.assertNoLeak()` calls:

 ```kotlin
 class CartTest {
   @get:Rule
   val rule = DetectLeaksAfterTestSuccess()

   // This test will not perform any leak assertion.
   @SkipLeakDetection("See issue #1234")
   @Test
   fun addItemToCart() {
     // ...
     LeakAssertions.assertNoLeak()
     // ...
     LeakAssertions.assertNoLeak()
     // ...
   }
 }
 ```

You can use **tags** to identify each `LeakAssertions.assertNoLeak()` call and disable only a subset of these calls:

 ```kotlin
 class CartTest {
   @get:Rule
   val rule = DetectLeaksAfterTestSuccess(tag = "EndOfTest")

   // This test will only perform the second leak assertion.
   @SkipLeakDetection("See issue #1234", "First Assertion", "EndOfTest")
   @Test
   fun addItemToCart() {
     // ...
     LeakAssertions.assertNoLeak(tag = "First Assertion")
     // ...
     LeakAssertions.assertNoLeak(tag = "Second Assertion")
     // ...
   }
 }
 ```

Tags can be retrieved by calling `HeapAnalysisSuccess.assertionTag` and are also reported in the
heap analysis result metadata:

```
====================================
METADATA

Please include this in bug reports and Stack Overflow questions.

Build.VERSION.SDK_INT: 23
...
assertionTag: Second Assertion
```

## Test rule chains

```kotlin
// Example test rule chain
@get:Rule
val rule = RuleChain.outerRule(LoginRule())
  .around(ActivityScenarioRule(CartActivity::class.java))
  .around(LoadingScreenRule())

```

If you use a test rule chain, the position of the `DetectLeaksAfterTestSuccess` rule in that chain
could be significant. For example, if you use an `ActivityScenarioRule` that automatically
finishes the activity at the end of a test, having `DetectLeaksAfterTestSuccess` around
`ActivityScenarioRule` will detect leaks after the activity is destroyed and therefore detect any
activity leak. But then  `DetectLeaksAfterTestSuccess` will not detect fragment leaks that go away
when the activity is destroyed.

```kotlin
@get:Rule
val rule = RuleChain.outerRule(LoginRule())
  // Detect leaks AFTER activity is destroyed
  .around(DetectLeaksAfterTestSuccess(tag = "AfterActivityDestroyed"))
  .around(ActivityScenarioRule())
  .around(LoadingScreenRule())
```

If instead you set up `ActivityScenarioRule` around `DetectLeaksAfterTestSuccess`, destroyed
activity leaks will not be detected as the activity will still be created when the leak assertion
rule runs, but more fragment leaks might be detected.

```kotlin
@get:Rule
val rule = RuleChain.outerRule(LoginRule())
  .around(ActivityScenarioRule(CartActivity::class.java))
  // Detect leaks BEFORE activity is destroyed
  .around(DetectLeaksAfterTestSuccess(tag = "BeforeActivityDestroyed"))
  .around(LoadingScreenRule())
```

To detect all leaks, the best option is to
set up the `DetectLeaksAfterTestSuccess` rule twice, before and after the `ActivityScenarioRule`
rule.

```kotlin
// Detect leaks BEFORE and AFTER activity is destroyed
@get:Rule
val rule = RuleChain.outerRule(LoginRule())
  .around(DetectLeaksAfterTestSuccess(tag = "AfterActivityDestroyed"))
  .around(ActivityScenarioRule(CartActivity::class.java))
  .around(DetectLeaksAfterTestSuccess(tag = "BeforeActivityDestroyed"))
  .around(LoadingScreenRule())
```

`RuleChain.detectLeaksAfterTestSuccessWrapping()` is a helper for doing just that:

```kotlin
// Detect leaks BEFORE and AFTER activity is destroyed
@get:Rule
val rule = RuleChain.outerRule(LoginRule())
  // The tag will be suffixed with "Before" and "After".
  .detectLeaksAfterTestSuccessWrapping(tag = "ActivitiesDestroyed") {
    around(ActivityScenarioRule(CartActivity::class.java))
  }
  .around(LoadingScreenRule())
```

## Customizing `assertNoLeak()`

`LeakAssertions.assertNoLeak()` delegates calls to a global `DetectLeaksAssert` implementation,
which by default is an instance of `AndroidDetectLeaksAssert`. You can change the
`DetectLeaksAssert` implementation by calling `DetectLeaksAssert.update(customLeaksAssert)`.

The `AndroidDetectLeaksAssert` implementation performs a heap dump when retained instances are
detected, analyzes the heap, then passes the result to a `HeapAnalysisReporter`. The default
`HeapAnalysisReporter` is `NoLeakAssertionFailedError.throwOnApplicationLeaks()` which throws a
`NoLeakAssertionFailedError` if an application leak is detected.

You could provide a custom implementation to also upload heap analysis results to a central place
before failing the test:
```kotlin
val throwingReporter = NoLeakAssertionFailedError.throwOnApplicationLeaks()

DetectLeaksAssert.update(AndroidDetectLeaksAssert(
  heapAnalysisReporter = { heapAnalysis ->
    // Upload the heap analysis result
    heapAnalysisUploader.upload(heapAnalysis)
    // Fail the test if there are application leaks
    throwingReporter.reportHeapAnalysis(heapAnalysis)
  }
))
```
