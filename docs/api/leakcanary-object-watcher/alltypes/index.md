

### All Types

| Name | Summary |
|---|---|
| [leakcanary.Clock](../leakcanary/-clock/index.md) |  |
| [leakcanary.GcTrigger](../leakcanary/-gc-trigger/index.md) | [GcTrigger](../leakcanary/-gc-trigger/index.md) is used to try triggering garbage collection and enqueuing [KeyedWeakReference](../leakcanary/-keyed-weak-reference/index.md) into the associated [java.lang.ref.ReferenceQueue](https://docs.oracle.com/javase/6/docs/api/java/lang/ref/ReferenceQueue.html). The default implementation [Default](../leakcanary/-gc-trigger/-default/index.md) comes from AOSP. |
| [leakcanary.KeyedWeakReference](../leakcanary/-keyed-weak-reference/index.md) | A weak reference used by [ObjectWatcher](../leakcanary/-object-watcher/index.md) to determine which objects become weakly reachable and which don't. [ObjectWatcher](../leakcanary/-object-watcher/index.md) uses [key](../leakcanary/-keyed-weak-reference/key.md) to keep track of [KeyedWeakReference](../leakcanary/-keyed-weak-reference/index.md) instances that haven't made it into the associated [ReferenceQueue](https://docs.oracle.com/javase/6/docs/api/java/lang/ref/ReferenceQueue.html) yet. |
| [leakcanary.ObjectWatcher](../leakcanary/-object-watcher/index.md) | [ObjectWatcher](../leakcanary/-object-watcher/index.md) can be passed objects to [watch](../leakcanary/-object-watcher/watch.md). It will create [KeyedWeakReference](../leakcanary/-keyed-weak-reference/index.md) instances that reference watches objects, and check if those references have been cleared as expected on the [checkRetainedExecutor](#) executor. If not, these objects are considered retained and [ObjectWatcher](../leakcanary/-object-watcher/index.md) will then notify registered [OnObjectRetainedListener](../leakcanary/-on-object-retained-listener/index.md)s on that executor thread. |
| [leakcanary.OnObjectRetainedListener](../leakcanary/-on-object-retained-listener/index.md) |  |
| [leakcanary.ReachabilityWatcher](../leakcanary/-reachability-watcher/index.md) |  |
