A memory leak is a programming error that causes an application to keep a reference to an object that is no longer needed. Somewhere in the code, there's a reference that should have been cleared and wasn't.

There are 4 steps to fixing a memory leak:

1. Find the leak trace
2. Narrow down the leak trace
3. Find the cause
4. Fix the leak

LeakCanary helps you with the first two steps. The last two steps are up to you!

!!! warning
    Memory leaks cannot be fixed by replacing strong references with weak references. It's a common solution when attempting to quickly address memory issues, however it never works. The bugs that were causing references to be kept longer than necessary are still there. On top of that, it creates more bugs as some objects will now be garbage collected sooner than they should. It also makes the code much harder to maintain.

## Find the leak trace

A **leak trace** is the *shortest strong reference path from garbage collection roots to the retained object*, ie the chain of references that is holding an object in memory, therefore preventing it from being garbage collected.

For example, if we store a helper singleton in a static field:

```java
class Helper {
}

class Utils {
  public static Helper helper = new Helper();
}
```

The leak trace for that singleton could look like this:

```
┬
├─ dalvik.system.PathClassLoader
│    GC Root: Local variable in native code
│    ↓ PathClassLoader.runtimeInternalObjects
├─ java.lang.Object[]
│    ↓ array Object[].[43]
├─ com.example.Utils
│    ↓ static Utils.helper
╰→ java.example.Helper
```

Let's break it down! At the top, we can see that `PathClassLoader` is a garbage collection (GC) root, more specifically a `PathClassLoader` instance is held by a local variable in native code. GC roots are special objects that are always reachable, ie they cannot be garbage collected. There are four kinds of GC roots worth mentioning:

* **Local variables**, which belong to the stack of a thread.
* Instances of **active Java threads**.
* **System Classes**, which never unload.
* **Native references**, which are controlled by native code.

```
┬
├─ dalvik.system.PathClassLoader
│    GC Root: Local variable in native code
```

A line starting with `├─ ` represents a Java object (either a class, an object array or an instance), and a line starting with `│    ↓ ` represents a reference to the Java object on the next line.

We can see that `PathClassLoader` has a `runtimeInternalObjects` field which is a reference to an array of `Object`. 

```
│    ↓ PathClassLoader.runtimeInternalObjects
├─ java.lang.Object[]
```

The element at position 43 in that array of `Object` is a reference to our `Utils` class.

```
├─ java.lang.Object[]
│    ↓ array Object[].[43]
├─ com.example.Utils
```

A line starting with `╰→ ` represents the retained object. The retained object was passed to [AppWatcher.objectWatcher](/leakcanary/api/leakcanary-object-watcher-android/leakcanary/-app-watcher/object-watcher/) to confirm it would be garbage collected, and it ended up not being garbage collected which triggered LeakCanary.

We can see that our `Utils` class has a static `helper` field which is a reference to the retained object, our Helper singleton instance. 

```
├─ com.example.Utils
│    ↓ static Utils.helper
╰→ java.example.Helper
```

## Narrow down the leak trace

Let's look at a real Android leak trace:

TODO Introduce a real leaktrace, in text, but simplified. 

Somewhere in that chain of references we know there's a reference that should have been cleared and wasn't. What can we do to narrow down the suspects?

TODO Explain how we can gather more information about the java objects. Then display the leak trace with that. Then explain how we can figure out if an object is leaking or not, and add that information to the leak trace. Then make the leap of explaining that we can do the binary search thing and determine more "LEAKING" state. Show the leaktrace with that. Explain that the references in between the last NO and the first YES and the likely causes, and highlight them as such, with the comment from the string dump about ~~~~. Add an info about "signature" and link back to previous page. Then show the equivalent in LeakCanary's UI.

Then explain that you can help LeakCanary narrow down the cause further by providing helpers that can determine the status. Show the singleton example, and maybe another one.

```
    ├─ android.widget.TextView
    │    Leaking: YES (View detached and has parent)
    │    View#mAttachInfo is null (view detached)
    │    View#mParent is set
    │    View.mWindowAttachCount=1
```

LeakCanary runs heuristics to determine the lifecycle state of the nodes of the leak trace, and therefore whether they are leaking or not. For example, if a view has `View#mAttachInfo = null` and `mParent != null` then it is detached yet has a parent, so that view is probably leaking. In the leak trace, for each node you'll see `Leaking: YES / NO / UNKNOWN` with an explanation in parenthesis. LeakCanary can also surface extra information about the state of a node, e.g. `View.mWindowAttachCount=1`. LeakCanary comes with a set of default heuristics: [AndroidObjectInspectors](/leakcanary/api/shark-android/shark/-android-object-inspectors/). You can add your own heuristics by updating [LeakCanary.Config.objectInspectors](/leakcanary/api/leakcanary-android-core/leakcanary/-leak-canary/-config/object-inspectors/) (see the [recipe](recipes.md#identifying-leaking-objects-and-labeling-objects)).


```
    ┬
    ├─ android.provider.FontsContract
    │    Leaking: NO (ExampleApplication↓ is not leaking and a class is never leaking)
    │    GC Root: System class
    │    ↓ static FontsContract.sContext
    ├─ com.example.leakcanary.ExampleApplication
    │    Leaking: NO (Application is a singleton)
    │    ExampleApplication does not wrap an activity context
    │    ↓ ExampleApplication.leakedViews
    │                         ~~~~~~~~~~~
    ├─ java.util.ArrayList
    │    Leaking: UNKNOWN
    │    ↓ ArrayList.elementData
    │                ~~~~~~~~~~~
    ├─ java.lang.Object[]
    │    Leaking: UNKNOWN
    │    ↓ array Object[].[1]
    │                     ~~~
    ├─ android.widget.TextView
    │    Leaking: YES (View.mContext references a destroyed activity)
    │    ↓ TextView.mContext
    ╰→ com.example.leakcanary.MainActivity
    ​     Leaking: YES (TextView↑ is leaking and Activity#mDestroyed is true and ObjectWatcher was watching this)
```

If a node is not leaking, then any prior reference that points to it is not the source of the leak, and also not leaking. Similarly, if a node is leaking then any node down the leak trace is also leaking. From that, we can deduce that the leak is caused by a reference that is after the last `Leaking: NO`	and before the first `Leaking: YES`.

LeakCanary highlights those references with a **<span style="text-decoration: underline; text-decoration-color: red; text-decoration-style: wavy;">red underline</span>** in the UI, or a **~~~~** underline in the Logcat representation. These highlighted references are the **only possible causes of the leak**. These are the references you should spend time investigating.

In this example, the last `Leaking: NO` is on `com.example.leakcanary.ExampleApplication` and the first `Leaking: YES` is on `android.widget.TextView`, so the leak is caused by one of the 3 references in between:

```
...
    │    ↓ ExampleApplication.leakedViews
    │                         ~~~~~~~~~~~
...
    │    ↓ ArrayList.elementData
    │                ~~~~~~~~~~~
...
    │    ↓ array Object[].[0]
    │                     ~~~
...
```

Looking at the [source](https://github.com/square/leakcanary/blob/master/leakcanary-android-sample/src/main/java/com/example/leakcanary/ExampleApplication.kt#L23), we can see that `ExampleApplication` has a list field:

```
open class ExampleApplication : Application() {
  val leakedViews = mutableListOf<View>()
}
```

It's unlikely that there's a bug in the `ArrayList` implementation itself, so the leak happens because we're adding views to `ExampleApplication.leakedViews`. If we stop doing that, we've fixed the leak!

## Find the cause

1. Find the reference that should have been cleared
2. Figure out why it hasn't been cleared

## Fix the leak

The chain of references from the GC root to the leaking object is what is preventing the leaking object from being garbage collected. If you can identify the reference that should not exist at that point in time, then you can figure out why it's incorrectly still set and then fix the memory leak.

What's next? Try the [code recipes](recipes.md)!
