## What is a memory leak?

In a Java based runtime, a memory leak is a programming error that causes an application to keep a reference to an object that is no longer needed. As a result, the memory allocated for that object cannot be reclaimed, eventually leading to an OutOfMemoryError crash.

For example, an Android activity instance is no longer needed after its `onDestroy()` method is called, and storing a reference to that activity in a static field would prevent it from being garbage collected.

## Common causes for memory leaks

Most memory leaks are caused by bugs related to the lifecycle of objects. Here are a few common Android mistakes:

* Adding a `Fragment` to the backstack without clearing its view fields in `Fragment.onDestroyView()` (see this great [StackOverflow answer](https://stackoverflow.com/a/59504797/703646))
* Storing an Activity context as a field in an object that survives activity recreation due to configuration changes.
* Registering a listener, broadcast receiver or RxJava subscription which references an object with lifecycle, and forgetting to unregister when the lifecycle reaches its end.

## Why should I use LeakCanary?

Memory leaks are very common in Android apps and the accumulation of small memory leaks causes apps to run out of memory and crash with an OutOfMemoryError (OOM). LeakCanary will help you find these memory leaks during development. When we first enabled LeakCanary in the Square Point Of Sale app, we were able to find and fix several leaks and reduced the OOM crash rate by **94%**.

!!! info
    **Most crash reporting tools do not correctly report OOMs**. When memory is low because of memory leak accumulation, an OOM can be thrown from anywhere in the app code, which means every OOM has a different stacktrace. So instead of one crash entry with a 1000 crashes, those get reported as 1000 distinct crashes and hide in the long tail of low occuring crashes.

What's next? Learn [how LeakCanary works](fundamentals-how-leakcanary-works.md)!