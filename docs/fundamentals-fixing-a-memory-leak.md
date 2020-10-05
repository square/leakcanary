A memory leak is a programming error that causes an application to keep a reference to an object that is no longer needed. Somewhere in the code, there's a reference that should have been cleared and wasn't.

Follow these 4 steps to fix memory leaks:

1. Find the leak trace.
2. Narrow down the suspect references.
3. Find the reference causing the leak.
4. Fix the leak.

LeakCanary helps you with the first two steps. The last two steps are up to you!

## 1. Find the leak trace

A **leak trace** is a shorter name for the *best strong reference path from garbage collection roots to the retained object*, ie the path of references that is holding an object in memory, therefore preventing it from being garbage collected.

For example, let's store a helper singleton in a static field:

```java
class Helper {
}

class Utils {
  public static Helper helper = new Helper();
}
```

Let's tell LeakCanary that the singleton instance is expected to be garbage collected:

```
AppWatcher.objectWatcher.watch(Utils.helper)
```

The leak trace for that singleton looks like this:

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

Let's break it down! At the top, a `PathClassLoader` instance is held by a **garbage collection (GC) root**, more specifically a local variable in native code. GC roots are special objects that are always reachable, ie they cannot be garbage collected. There are 4 main types of GC root:

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

`PathClassLoader` has a `runtimeInternalObjects` field that is a reference to an array of `Object`:

```
├─ dalvik.system.PathClassLoader instance
│    ↓ PathClassLoader.runtimeInternalObjects
├─ java.lang.Object[] array
```

The element at position 43 in that array of `Object` is a reference to the `Utils` class.

```
├─ java.lang.Object[] array
│    ↓ Object[].[43]
├─ com.example.Utils class
```

A line starting with `╰→ ` represents the leaking object, ie the object that is passed to [AppWatcher.objectWatcher.watch()](/leakcanary/api/leakcanary-object-watcher-android/leakcanary/-app-watcher/object-watcher/).

The `Utils` class has a static `helper` field which is a reference to the leaking object, which is the Helper singleton instance:

```
├─ com.example.Utils class
│    ↓ static Utils.helper
╰→ java.example.Helper instance
```

## 2. Narrow down the suspect references

A leak trace is a path of references. Initially, all references in that path are suspected of causing the leak, but LeakCanary can automatically narrow down the suspect references. To understand what that means, let's go through that process manually.

Here's an example of bad Android code:

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
	// This creates a leak, What a Terrible Failure!
	app.leakedViews.add(textView)
  }
}
```

LeakCanary produces a leak trace that looks like this:

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

Here's how to read that leak trace:

> The `FontsContract` class is a system class (see `GC Root: System class`) and has an `sContext` static field which references an `ExampleApplication` instance which has a `leakedViews` field which references an `ArrayList` instance which references an array (the array backing the array list implementation) which has an element that references a `TextView` which has an `mContext` field which references a destroyed instance of `MainActivity`.

LeakCanary highlights all references suspected of causing this leak using ~~~ underlines. Initially, all references are suspect:

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

Then, LeakCanary makes deductions about the **state** and the **lifecycle** of the objects in the leak trace. In an Android app the `Application` instance is a singleton that is never garbage collected, so it's never leaking (`Leaking: NO (Application is a singleton)`). From that, LeakCanary concludes that the leak is not caused by `FontsContract.sContext` (removal of corresponding `~~~`). Here's the updated leak trace:

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

The `TexView` instance references the destroyed `MainActivity` instance via it's `mContext` field. Views should not survive the lifecycle of their context, so LeakCanary knows that this `TexView` instance is leaking (`Leaking: YES (View.mContext references a destroyed activity)`), and therefore that the leak is not caused by `TextView.mContext` (removal of corresponding `~~~`). Here's the updated leak trace:

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

To summarize, LeakCanary inspects the state of objects in the leak trace to figure out if these objects are leaking (`Leaking: YES` vs `Leaking: NO`), and leverages that information to narrow down the suspect references. You can provide custom `ObjectInspector` implementations to improve how LeakCanary works in your codebase (see [Identifying leaking objects and labeling objects](recipes.md#identifying-leaking-objects-and-labeling-objects)).

## 3. Find the reference causing the leak

In the previous example, LeakCanary narrowed down the suspect references to `ExampleApplication.leakedViews`, `ArrayList.elementData` and `Object[].[0]`:

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

`ArrayList.elementData` and `Object[].[0]` are implementation details of `ArrayList`, and it's unlikely that there's a bug in the `ArrayList` implementation, so the reference causing the leak is the only remaining reference: `ExampleApplication.leakedViews`.

## 4. Fix the leak

Once you find the reference causing the leak, you need to figure out what that reference is about, when it should have been cleared and why it hasn't been. Sometimes it's obvious, like in the previous example. Sometimes you need more information to figure it out. You can [add labels](recipes.md#identifying-leaking-objects-and-labeling-objects), or explore the hprof directly (see [How can I dig beyond the leak trace?](faq.md#how-can-i-dig-beyond-the-leak-trace)).


!!! warning
    Memory leaks cannot be fixed by replacing strong references with weak references. It's a common solution when attempting to quickly address memory issues, however it never works. The bugs that were causing references to be kept longer than necessary are still there. On top of that, it creates more bugs as some objects will now be garbage collected sooner than they should. It also makes the code much harder to maintain.


What's next? Customize LeakCanary to your needs with [code recipes](recipes.md)!
