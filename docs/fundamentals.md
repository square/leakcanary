# Fundamentals

## What is a memory leak?

In a Java based runtime, a memory leak is a programming error that causes an application to keep a reference to an object that is no longer needed. As a result, the memory allocated for that object cannot be reclaimed, eventually leading to an OutOfMemoryError crash.

For example, an Android activity instance is no longer needed after its `onDestroy()` method is called, and storing a reference to that activity in a static field would prevent it from being garbage collected.

## Why should I use LeakCanary?

Memory leaks are very common in Android apps. OutOfMemoryError (OOM) is the top crash for most apps on the play store, however that's usually not counted correctly. When memory is low the OOM can be thrown from anywhere in your code, which means every OOM has a different stacktrace and they're counted as different crashes.

When we first enabled LeakCanary in the Square Point Of Sale app, we were able to find and fix several leaks and reduced the OutOfMemoryError crash rate by **94%**.

## How does LeakCanary work?

* The library automatically watches destroyed activities and destroyed fragments using weak references. You can also watch any instance that is no longer needed, e.g. a detached view.
* If the weak references aren't cleared, after waiting 5 seconds and running the GC, the watched instances are considered *retained*, and potentially leaking.
* When the number of retained instances reaches a threshold, LeakCanary dumps the Java heap into a `.hprof` file stored on the file system. The default threshold is 5 retained instances when the app is visible, 1 otherwise.
* LeakCanary parses the `.hprof` file and finds the chain of references that prevents retained instances from being garbage collected (**leak trace**). A leak trace is technically the *shortest strong reference path from GC Roots to retained instances*, but that's a mouthful.
* Once the leak trace is determined, LeakCanary uses its built in knowledge of the Android framework to deduct which instances in the leak trace are leaking. You can help LeakCanary by providing **Reachability inspectors** tailored to your own app.
* Using the reachability information, LeakCanary narrows down the reference chain to a sub chain of possible leak causes, and displays the result. Leaks are grouped by identical sub chain.

## How do I fix a memory leak?
To fix a memory leak, you need to look at the sub chain of possible leak causes and find which reference is causing the leak, i.e. which reference should have been cleared at the time of the leak. LeakCanary highlights with a red underline wave the references that are the possible causes of the leak.

If you cannot figure out a leak, **please do not file an issue**. Instead, create a [Stack Overflow question](http://stackoverflow.com/questions/tagged/leakcanary?sort=active) using the *leakcanary* tag.

## LeakCanary artifacts

LeakCanary is released as several distinct libraries:

* LeakSentry
    * Detects retained instances.
    * Suitable for release builds.
    * Artifact id: `com.squareup.leakcanary:leaksentry`.
* LeakCanary
    * Dumps the heap and analyzes it.
    * Currently only suitable for debug builds.
    * Depends on LeakSentry.
    * Artifact id: `com.squareup.leakcanary:leakcanary-android`.
* LeakCanary for Instrumentation tests
    * Fails tests if a leak is detected
    * Only suitable for Instrumentation tests
    * Configures LeakCanary to wait for the end of tests before dumping the heap.
    * Artifact id: `com.squareup.leakcanary:leakcanary-android-instrumentation`.
    * See [Running LeakCanary in instrumentation tests](recipes.md#running-leakcanary-in-instrumentation-tests)