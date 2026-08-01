# Detecting heap growth

[`LeakAssertions.assertNoLeaks()`](ui-tests.md) answers one question: *did an object that should be
gone survive?* It can only answer it for the objects LeakCanary watches — destroyed activities and
fragments, cleared view models, and whatever else you hand to `AppWatcher.objectWatcher`.

Heap growth detection answers a different question: *does repeating this scenario make the heap grow
forever?* Nothing has to be watched, and the growing objects don't have to be leaking on their own: a
cache that gains an entry every time a screen opens, a listener list nobody unregisters from, a map
keyed by request id that is never pruned. None of those will ever fail a retained object assertion,
and they are what makes an app die of a slow bleed rather than of one big retained activity.

!!! info "LeakCanary 3.0 alpha"
    Heap growth detection ships in the LeakCanary 3.0 alphas, and its API is still moving — expect
    changes between alphas.

## How it works

`findRepeatedlyGrowingObjects()` loops:

1. Run the scenario `scenarioLoopsPerDump` times (2 by default).
2. Run a GC, dump the heap, then traverse it from the GC roots, building the tree of shortest paths
   to every object.
3. Diff that traversal against the previous one, keeping only the nodes whose object count grew by at
   least `scenarioLoopsPerDump` since the previous dump.
4. Stop as soon as nothing is growing anymore, or after `maxHeapDumps` dumps (5 by default).

Only the objects that grow on *every* iteration make it to the end, which is what makes the result
worth failing a test over: a cache that fills up once, a lazily created singleton, a thread pool
growing to its ceiling — all of those stop growing and drop out of the report.

That also means the scenario has to be a **round trip**: it should leave the app where it found it
(screen opened *and* closed, item added *and* removed), otherwise everything it legitimately
allocates and keeps looks exactly like a leak.

## Espresso tests

```groovy
dependencies {
  androidTestImplementation 'com.squareup.leakcanary:leakcanary-android-test:{{ leak_canary.latest_alpha }}'
}
```

!!! warning "`android:largeHeap="true"` goes in the manifest of the app under test"
    Traversing the heap of a process from inside that same process needs more memory than the default
    heap limit gives an app, so heap growth detection needs a large heap. An instrumentation process
    is created from the `ApplicationInfo` of the **app under test**, so setting
    `android:largeHeap="true"` in `src/androidTest/AndroidManifest.xml` — the manifest of the *test*
    apk — has no effect whatsoever. It has to be the manifest of the app module under test.
    `src/debug/AndroidManifest.xml` is a good place for it: instrumentation tests run against the
    debug variant by default, so the released app is left alone.

    The exception is a library module that instruments itself: there the test apk *is* the app under
    test, and `src/androidTest/AndroidManifest.xml` is the right manifest.

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- app/src/debug/AndroidManifest.xml -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

  <!-- Traversing the heap in process needs more memory than the default heap limit. -->
  <application android:largeHeap="true" />
</manifest>
```

```kotlin
class CartTest {

  private val detector = HeapDiff.repeatingAndroidInProcessScenario()

  @Test fun adding_item_to_cart_does_not_grow_heap() {
    // Repeats the scenario until the heap stops growing, or up to maxHeapDumps times.
    val heapDiff = detector.findRepeatedlyGrowingObjects {
      onView(withId(R.id.add_item)).perform(click())
      onView(withId(R.id.clear_cart)).perform(click())
    }

    assertThat(heapDiff.growingObjects).isEmpty()
  }
}
```

Assert on `growingObjects` rather than on `heapDiff.isGrowing`: the assertion failure then prints the
report below, which is the part that tells you what to fix.

## Reading the report

```
┬───
│ GcRoot(ThreadObject) (14 objects)
│ 
├─LOCAL Instrumentation$InstrumentationThread. -> instance of com.example.cart.CartTest (1 objects)
│ 
├─INSTANCE_FIELD CartTest.cart -> instance of com.example.cart.Cart (1 objects)
│ 
╰→INSTANCE_FIELD Cart.itemsById -> instance of java.util.LinkedHashMap (1 objects)
    Retained size: 777 B (+ 132 B)
    Retained objects: 32 (+ 6)
    Children:
    10 objects (2 new): ARRAY_ENTRY LinkedHashMap.[x] -> instance of java.lang.String
    10 objects (2 new): ARRAY_ENTRY LinkedHashMap.[x] -> instance of com.example.cart.Item
```

Read it top down, like a leak trace: a GC root, then the shortest path from that root to the growing
object, which is the last line (`╰→`). Here a `Cart` held by the test class keeps a `LinkedHashMap`
that grows every time the scenario runs — `Cart.clear()` forgot about `itemsById`.

* `(N objects)` is how many objects that node stands for. Nodes are paths, not objects, so a single
  node can stand for thousands of objects reached the same way.
* `Retained size` and `Retained objects` are how much heap the growing object accounts for, and
  `(+ ...)` how much that grew since the previous heap dump.
* `Children` lists the children of the growing node that grew, with how many objects they hold now
  and how many of those are new. `10 objects (2 new)` with the default `scenarioLoopsPerDump = 2`
  means one new object per scenario loop — the map gains one entry per iteration, and the entry keys
  (`String`) and values (`Item`) grow with it.

!!! info "Retained size is shared between growing objects"
    When several growing objects hold onto the same subgraph, that memory is split evenly between
    them, so the retained size of one node isn't what fixing that one node would give back. The
    retained sizes of all the growing objects do add up to the size of the subgraph they hold
    together.

## Tuning the loop

```kotlin
detector.findRepeatedlyGrowingObjects(
  maxHeapDumps = 5,
  scenarioLoopsPerDump = 2
) {
  // scenario
}
```

`scenarioLoopsPerDump` is both how many times the scenario runs between two heap dumps and the
threshold a node has to clear to count as growing. 1 works, but 2 or more is recommended so that the
side effects of dumping the heap don't register as growth.

`maxHeapDumps` caps how long a growing scenario keeps going. Every extra dump costs a GC, a heap dump
and a full traversal, so this is really a time budget. A scenario that isn't growing returns after
the second dump, the first one there is anything to diff against.

## Keeping the heap dumps

By default each heap dump is deleted as soon as it has been traversed, so a run keeps at most one
heap dump on disk. Pass a different `heapDumpStorageStrategy` to keep them:

| Strategy | Keeps |
| --- | --- |
| `DeleteOnHeapDumpClose()` (default) | nothing, each dump goes as soon as it has been traversed |
| `KeepHeapDumps` | every heap dump, always |
| `KeepHeapDumpsOnObjectsGrowing()` | every heap dump, but only if objects were growing or the run failed |
| `KeepZippedHeapDumpsOnObjectsGrowing()` | the same, zipped — typically 4x smaller, at the cost of a few seconds per dump |

```kotlin
private val detector = HeapDiff.repeatingAndroidInProcessScenario(
  heapDumpStorageStrategy = HeapDumpStorageStrategy.KeepHeapDumpsOnObjectsGrowing()
)
```

Where the dumps land, named `<datetime>_<TestClass>-<test_method>.hprof` so that sorting by name
sorts them chronologically:

| Scenario | Directory |
| --- | --- |
| `repeatingAndroidInProcessScenario()` | `heap_dumps_object_growth` in the `filesDir` of the app under test |
| `repeatingUiAutomatorScenario()` | `/data/local/tmp/heap_dumps_object_growth_<app package name>` |
| `repeatingJvmInProcessScenario()` | `heap_dumps_object_growth` in the repository root |

`adb pull` can't reach the private files of an app, so for the in process Android scenario pull the
dumps through `run-as`, which works on debuggable apps:

```bash
$ adb shell run-as com.example.app.debug ls files/heap_dumps_object_growth
2026-07-29_21-31-33_894_CartTest-adding_item_to_cart_does_not_grow_heap.hprof
2026-07-29_21-31-39_046_CartTest-adding_item_to_cart_does_not_grow_heap.hprof
2026-07-29_21-31-45_149_CartTest-adding_item_to_cart_does_not_grow_heap.hprof
2026-07-29_21-31-51_126_CartTest-adding_item_to_cart_does_not_grow_heap.hprof
2026-07-29_21-31-57_063_CartTest-adding_item_to_cart_does_not_grow_heap.hprof
$ adb exec-out run-as com.example.app.debug \
  cat files/heap_dumps_object_growth/2026-07-29_21-31-33_894_CartTest-adding_item_to_cart_does_not_grow_heap.hprof \
  > dump.hprof
```

The UI Automator scenario dumps to `/data/local/tmp`, which plain `adb pull` can read.

## Running out of memory

Traversing a heap dump of a process from within that same process is the memory hungry part of all
this, and on Android the limit it has to fit in is a few hundred MB. When the traversal runs out of
memory, LeakCanary replaces the failure with what that specific process can do about it:

```
Not enough memory to detect heap growth: this process can use up to 192 MB. You can:
- Raise that limit to 512 MB by setting android:largeHeap="true" in the manifest of the app under test (com.example.app.debug). Setting it in src/androidTest/AndroidManifest.xml has no effect: that manifest ends up in the test apk, and an instrumentation process is created from the ApplicationInfo of the app under test. https://developer.android.com/guide/topics/manifest/application-element#largeHeap
- Keep the heap dumps (heapDumpStorageStrategy = HeapDumpStorageStrategy.KeepHeapDumpsOnObjectsGrowing()) then detect heap growth from your computer instead, where memory is cheaper: shark-cli --hprof <heap dump directory> heap-growth --loops <scenarioLoopsPerDump>. https://square.github.io/leakcanary/shark/#shark-cli
```

The message states the limit the process actually had and skips advice that is already taken: if
`android:largeHeap="true"` is already set in the right manifest it says so instead of suggesting it,
and if it is set in the manifest of the test apk, where it does nothing, it says to move it.

## Detecting heap growth from your computer

Keeping the heap dumps and running the detection from your computer trades a slower loop for as much
memory as your machine has. The [Shark CLI](shark.md#shark-cli) `heap-growth` command takes a
directory of heap dumps, in name order:

```
$ shark-cli --hprof heap_dumps_object_growth heap-growth --loops 2
Detecting heap growth by going analyzing the following heap dumps in this order:
2026-07-29_21-31-33_894_CartTest-adding_item_to_cart_does_not_grow_heap.hprof
2026-07-29_21-31-39_046_CartTest-adding_item_to_cart_does_not_grow_heap.hprof
2026-07-29_21-31-45_149_CartTest-adding_item_to_cart_does_not_grow_heap.hprof
2026-07-29_21-31-51_126_CartTest-adding_item_to_cart_does_not_grow_heap.hprof
2026-07-29_21-31-57_063_CartTest-adding_item_to_cart_does_not_grow_heap.hprof
Results: HeapGrowthTraversal(traversal=5, isGrowing=true, scenarioLoopsPerGraph=2, growingNodes=

┬───
│ GcRoot(ThreadObject) (14 objects)
│ 
├─LOCAL Instrumentation$InstrumentationThread. -> instance of com.example.cart.CartTest (1 objects)
│ 
├─INSTANCE_FIELD CartTest.cart -> instance of com.example.cart.Cart (1 objects)
│ 
╰→INSTANCE_FIELD Cart.itemsById -> instance of java.util.LinkedHashMap (1 objects)
    Retained size: 777 B (+ 132 B)
    Retained objects: 32 (+ 6)
    Children:
    10 objects (2 new): ARRAY_ENTRY LinkedHashMap.[x] -> instance of java.lang.String
    10 objects (2 new): ARRAY_ENTRY LinkedHashMap.[x] -> instance of com.example.cart.Item

)
Found 1 growing objects
```

* Pass the same `--loops` as the `scenarioLoopsPerDump` the heap dumps were recorded with, otherwise
  the growth threshold won't be the one the test used. `--loops` defaults to 1.
* It only reads **Android** heap dumps, and only files ending in `.hprof` — unzip first if you kept
  them zipped.

`heap-growth` can also drive a live app instead of reading files: `shark-cli --process
com.example.app.debug heap-growth` dumps the heap of a debuggable app over adb, then asks you to go
through the scenario by hand and press enter for each new heap dump, reporting what grew each time.

## JVM unit tests

```groovy
dependencies {
  testImplementation 'com.squareup.leakcanary:leakcanary-jvm-test:{{ leak_canary.latest_alpha }}'
}
```

```kotlin
class CartTest {

  private val cart = Cart()

  private val detector = HeapDiff.repeatingJvmInProcessScenario()

  @Test fun adding_item_to_cart_does_not_grow_heap() {
    val heapDiff = detector.findRepeatedlyGrowingObjects {
      cart.addItem(Item("Cheeseburger"))
      cart.clear()
    }

    assertThat(heapDiff.growingObjects).isEmpty()
  }
}
```

The heap is dumped through the HotSpot APIs, so there is no manifest involved here: when the
traversal runs out of memory, raise the `-Xmx` of the JVM running the tests.

## UI Automator tests

```groovy
dependencies {
  androidTestImplementation 'com.squareup.leakcanary:leakcanary-android-uiautomator:{{ leak_canary.latest_alpha }}'
}
```

```kotlin
class WelcomeTest {

  private val detector = HeapDiff.repeatingUiAutomatorScenario()

  @Test fun clicking_welcome_does_not_grow_heap() {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    val heapDiff = detector.findRepeatedlyGrowingObjects {
      device.findObject(By.text("Welcome!")).click()
    }

    assertThat(heapDiff.growingObjects).isEmpty()
  }
}
```

Here the heap dump is triggered from the shell (`am dumpheap` against the pid of the app under test)
rather than from inside the app, which is what makes it possible to detect growth in an app that
doesn't have LeakCanary in it. The traversal still runs in the instrumentation process, and an
instrumentation process is created from the `ApplicationInfo` of the app under test, so
`android:largeHeap="true"` in the manifest of the app under test still decides how much memory the
traversal gets.
