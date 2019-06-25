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
* Once the leak trace is determined, LeakCanary uses its built-in knowledge of the Android framework to deduct which instances in the leak trace are leaking. You can help LeakCanary by providing **Leak inspectors** tailored to your own app.
* Using the leak status information, LeakCanary narrows down the reference chain to a sub chain of possible leak causes, and displays the result. Leaks are grouped by identical sub chain.

## How do I fix a memory leak?

For each leaking instance, LeakCanary computes a leak trace and displays it in its UI:

![leak trace](images/leaktrace.png)

The leak trace is also logged to Logcat:

```
    ┬
    ├─ leakcanary.internal.InternalLeakCanary
    │    Leaking: NO (it's a GC root and a class is never leaking)
    │    ↓ static InternalLeakCanary.application
    ├─ com.example.leakcanary.ExampleApplication
    │    Leaking: NO (Application is a singleton)
    │    ↓ ExampleApplication.leakedViews
    │                         ~~~~~~~~~~~
    ├─ java.util.ArrayList
    │    Leaking: UNKNOWN
    │    ↓ ArrayList.elementData
    │                ~~~~~~~~~~~
    ├─ java.lang.Object[]
    │    Leaking: UNKNOWN
    │    ↓ array Object[].[0]
    │                     ~~~
    ├─ android.widget.TextView
    │    Leaking: YES (View detached and has parent)
    │    View#mAttachInfo is null (view detached)
    │    View#mParent is set
    │    View.mWindowAttachCount=1
    │    ↓ TextView.mContext
    ╰→ com.example.leakcanary.MainActivity
    ​     Leaking: YES (RefWatcher was watching this and MainActivity#mDestroyed is true)
```

Here's how to read it:

* Each node `├─` in the LeakTrace is either a class, an object array or an instance.
* Going down, each node has a reference to the next node. In the UI, that reference is in purple. In the Logcat representation, the reference is on the line that starts with a down arrow `↓` .
* At the top of the leak trace is a garbage-collection (GC) root. GC roots are special objects that are always reachable. There are four kinds of GC roots worth mentioning:
    * **Local variables**, which belong to the stack of a thread.
    * Instances of **active Java threads**.
    * **Classes**, which never unload on Android.
    * **Native references**, which are controlled by native code.
* At the bottom of the leak trace is the leaking instance.
* The chain of references from the GC root to the leaking instance is what is preventing the leaking instance from being garbage collected. If you can identify the reference that should not exist at that point in time, then you can figure out why it's incorrectly still set and then fix the memory leak.
* LeakCanary runs heuristics to determine the lifecycle of the nodes of the leak trace. It uses the state of these nodes to indicate whether they are leaking or not. For example, if a view is detached then that view is probably leaking. On the other hand the Application class is never leaking. In the leak trace, for each node you'll see "Leaking: YES / NO / UNKNOWN" with an explanation in parenthesis. You can customize this behavior and add your own heuristics.
    * LeakCanary can also provide extra information about the state of a node, e.g. `View.mWindowAttachCount=1`. You can add your own labels.
* If a node is not leaking, then any prior reference that points to it is not the source of the leak, and also not leaking. Similarly, if a node is leaking then any node down the leaktrace is also leaking. From that, we can deduce that the leak is caused by a reference that is after the last `Leaking: NO`	and before the first `Leaking: YES`.
* LeakCanary highlights those references with a **red underline** in the UI, or a **chain of tilde ~~~~~~~~~~~** in the Logcat representation. These are the **only possible causes of the leak**. These are the reference you should spend time investigating.

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