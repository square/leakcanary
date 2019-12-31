[shark-graph](../../index.md) / [shark](../index.md) / [HprofHeapGraph](index.md) / [indexHprof](./index-hprof.md)

# indexHprof

`fun indexHprof(hprof: Hprof, proguardMapping: `[`ProguardMapping`](../-proguard-mapping/index.md)`? = null, indexedGcRootTypes: `[`Set`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)`<`[`KClass`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.reflect/-k-class/index.html)`<out GcRoot>> = setOf(
          JniGlobal::class,
          JavaFrame::class,
          JniLocal::class,
          MonitorUsed::class,
          NativeStack::class,
          StickyClass::class,
          ThreadBlock::class,
          // ThreadObject points to threads, which we need to find the thread that a JavaLocalPattern
          // belongs to
          ThreadObject::class,
          JniMonitor::class
          /*
          Not included here:

          VmInternal: Ignoring because we've got 150K of it, but is this the right thing
          to do? What's VmInternal exactly? History does not go further than
          https://android.googlesource.com/platform/dalvik2/+/refs/heads/master/hit/src/com/android/hit/HprofParser.java#77
          We should log to figure out what objects VmInternal points to.

          ReferenceCleanup: We used to keep it, but the name doesn't seem like it should create a leak.

          Unknown: it's unknown, should we care?

          We definitely don't care about those for leak finding: InternedString, Finalizing, Debugger, Unreachable
           */
      )): `[`HeapGraph`](../-heap-graph/index.md)