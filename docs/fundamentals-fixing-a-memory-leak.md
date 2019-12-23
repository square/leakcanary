A memory leak is a programming error that causes an application to keep a reference to an object that is no longer needed. Somewhere in the code, there's a reference that should have been cleared and wasn't.

There are 4 steps to fixing a memory leak:

1. Find the leak trace
2. Narrow down the leak trace
3. Find the cause
4. Fix the leak

LeakCanary helps you with the first two steps. The last two steps are up to you!

## 1. Find the leak trace

A **leak trace** is the *best strong reference path from garbage collection roots to the retained object*, ie the path of references that is holding an object in memory, therefore preventing it from being garbage collected.

For example, if we store a helper singleton in a static field:

```java
class Helper {
}

class Utils {
  public static Helper helper = new Helper();
}
```

And then we tell LeakCanary that we expect that singleton instance to be garbage collected soon:

```
AppWatcher.objectWatcher.watch(Utils.helper)
```

The leak trace for that singleton could look like this:

```
┬───
│ GC Root: Local variable in native code
│
├─ dalvik.system.PathClassLoader instance
│    ↓ PathClassLoader.runtimeInternalObjects
├─ java.lang.Object[] array
│    ↓ Object[].[43]
├─ com.example.Utils class
│    ↓ static Utils.helper
╰→ java.example.Helper
```

Let's break it down! At the top, we can see that a `PathClassLoader` instance is held by a garbage collection (GC) root, more specifically a local variable in native code. GC roots are special objects that are always reachable, ie they cannot be garbage collected. There are four kinds of GC roots worth mentioning:

* **Local variables**, which belong to the stack of a thread.
* Instances of **active Java threads**.
* **System Classes**, which never unload.
* **Native references**, which are controlled by native code.

```
┬───
│ GC Root: Local variable in native code
│
├─ dalvik.system.PathClassLoader instance
```

A line starting with `├─ ` represents a Java object (either a class, an object array or an instance), and a line starting with `│    ↓ ` represents a reference to the Java object on the next line.

We can see that `PathClassLoader` has a `runtimeInternalObjects` field which is a reference to an array of `Object`. 

```
├─ dalvik.system.PathClassLoader instance
│    ↓ PathClassLoader.runtimeInternalObjects
├─ java.lang.Object[] array
```

The element at position 43 in that array of `Object` is a reference to our `Utils` class.

```
├─ java.lang.Object[] array
│    ↓ Object[].[43]
├─ com.example.Utils class
```

A line starting with `╰→ ` represents the leaking object. The leaking object was passed to [AppWatcher.objectWatcher](/leakcanary/api/leakcanary-object-watcher-android/leakcanary/-app-watcher/object-watcher/) to confirm it would be garbage collected, and it ended up not being garbage collected which triggered LeakCanary.

We can see that our `Utils` class has a static `helper` field which is a reference to the leaking object, our Helper singleton instance. 

```
├─ com.example.Utils class
│    ↓ static Utils.helper
╰→ java.example.Helper instance
```

## 2. Narrow down the leak trace

Now, let's write some bad Android code:

```kotlin
class ExampleApplication : Application() {
  val leakedViews = mutableListOf<View>()
}

class MainActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.main_activity)
	
	val textView = findViewById<View>(R.id.helper_text)

    val app = application as ExampleApplication
	// What a Terrible Failure!
	app.leakedViews.add(textView)
  }
}

```

Let's pretend that we don't know about this bad code. We just joined a new company that has an existing Android app, we set up LeakCanary, and soon enough tit produces a leak trace that looks like this:

```
┬───
│ GC Root: System class
│
├─ android.provider.FontsContract class
│    ↓ static FontsContract.sContext
├─ com.example.leakcanary.ExampleApplication instance
│    ↓ ExampleApplication.leakedViews
├─ java.util.ArrayList instance
│    ↓ ArrayList.elementData
├─ java.lang.Object[] array
│    ↓ Object[].[0]
├─ android.widget.TextView instance
│    ↓ TextView.mContext
╰→ com.example.leakcanary.MainActivity instance
```

In words: the `FontsContract` class is a system class and has an `sContext` static field which references the `ExampleApplication` instance which has a `leakedViews` field which references an array list which references an array (the array backing the array list implementation) which contains a `TextView` which has an `mContext` field which references a destroyed instance of `MainActivity`. Let's highlight all references we are currently suspecting of causing this leak:

```
┬───
│ GC Root: System class
│
├─ android.provider.FontsContract class
│    ↓ static FontsContract.sContext
│                           ~~~~~~~~
├─ com.example.leakcanary.ExampleApplication instance
│    Leaking: NO (Application is a singleton)
│    ↓ ExampleApplication.leakedViews
│                         ~~~~~~~~~~~
├─ java.util.ArrayList instance
│    ↓ ArrayList.elementData
│                ~~~~~~~~~~~
├─ java.lang.Object[] array
│    ↓ Object[].[0]
│               ~~~
├─ android.widget.TextView instance
│    ↓ TextView.mContext
│               ~~~~~~~~
╰→ com.example.leakcanary.MainActivity instance
```

Our next step is to **reason about the state and lifecycle** of the objects in the leak trace: we know that in an Android app the Application instance is a singleton that is never garbage collected, so it's never leaking. From that, we can conclude that the leak is not caused by `FontsContract.sContext`. Let's update our suspected references:

```
┬───
│ GC Root: System class
│
├─ android.provider.FontsContract class
│    ↓ static FontsContract.sContext
├─ com.example.leakcanary.ExampleApplication instance
│    Leaking: NO (Application is a singleton)
│    ↓ ExampleApplication.leakedViews
│                         ~~~~~~~~~~~
├─ java.util.ArrayList instance
│    ↓ ArrayList.elementData
│                ~~~~~~~~~~~
├─ java.lang.Object[] array
│    ↓ Object[].[0]
│               ~~~
├─ android.widget.TextView instance
│    ↓ TextView.mContext
│               ~~~~~~~~
╰→ com.example.leakcanary.MainActivity instance
```

We also know that the `TexView` instance references our destroyed activity instance via it's `mContext` field. Views should not survive the lifecycle of their context, so we know that this text view is leaking:

```
┬───
│ GC Root: System class
│
├─ android.provider.FontsContract class
│    ↓ static FontsContract.sContext
├─ com.example.leakcanary.ExampleApplication instance
│    Leaking: NO (Application is a singleton)
│    ↓ ExampleApplication.leakedViews
│                         ~~~~~~~~~~~
├─ java.util.ArrayList instance
│    ↓ ArrayList.elementData
│                ~~~~~~~~~~~
├─ java.lang.Object[] array
│    ↓ Object[].[0]
│               ~~~
├─ android.widget.TextView instance
│    Leaking: YES (View.mContext references a destroyed activity)
│    ↓ TextView.mContext
╰→ com.example.leakcanary.MainActivity instance
```

Reasoning about the state and lifecycle of the objects in the leak trace help us narrow down the list of suspect references. The good news here is that most of that reasoning is automated, e.g. LeakCanary already knows that the Application class is a singleton and that Views should not survive the lifecycle of their context and will narrow down the list of suspects references for you. You can help LeakCanary help you by providing additional `ObjectInspector` implementations, see [Identifying leaking objects and labeling objects](recipes.md#identifying-leaking-objects-and-labeling-objects).

## 3. Find the cause

So far, our investigation has taught us that there are 3 suspect references in our leak trace: `ExampleApplication.leakedViews`, `ArrayList.elementData` and `Object[].[0]`:

```
┬───
│ GC Root: System class
│
├─ android.provider.FontsContract class
│    ↓ static FontsContract.sContext
├─ com.example.leakcanary.ExampleApplication instance
│    Leaking: NO (Application is a singleton)
│    ↓ ExampleApplication.leakedViews
│                         ~~~~~~~~~~~
├─ java.util.ArrayList instance
│    ↓ ArrayList.elementData
│                ~~~~~~~~~~~
├─ java.lang.Object[] array
│    ↓ Object[].[0]
│               ~~~
├─ android.widget.TextView instance
│    Leaking: YES (View.mContext references a destroyed activity)
│    ↓ TextView.mContext
╰→ com.example.leakcanary.MainActivity instance
```

`ArrayList.elementData` and `Object[].[0]` are implementation details of `ArrayList`, and it's unlikely that there's a bug in the `ArrayList` implementation instead, so we know the problem is somewhere else, ie the only remaining reference: `ExampleApplication.leakedViews`.

## 4. Fix the leak

Once we've found the reference that's causing the leak, we need to figure out what that reference is about, when it should have been cleared and why it hasn't been. Sometimes it's obvious, like in this example. Sometimes we lack enough information to figure it out. You could either [add labels](recipes.md#identifying-leaking-objects-and-labeling-objects), or explore the hprof directly (see [How can I dig beyond the leak trace?](faq.md/#how-can-i-dig-beyond-the-leak-trace)).


!!! warning
    Memory leaks cannot be fixed by replacing strong references with weak references. It's a common solution when attempting to quickly address memory issues, however it never works. The bugs that were causing references to be kept longer than necessary are still there. On top of that, it creates more bugs as some objects will now be garbage collected sooner than they should. It also makes the code much harder to maintain.


What's next? Try the [code recipes](recipes.md)!
