# Leaks through a binder stub

A binder stub is an object that another process calls into: an AIDL `Stub`, a `ResultReceiver`, or a
callback registered with a system service. Every subclass of `android.os.Binder` is a binder stub.

The other process holds a proxy to the stub, and the stub stays in memory until that process garbage
collects the proxy. Your app has no say in when that happens, and a stub can stay in memory forever. When a
leak trace goes through a binder stub, LeakCanary gives the stub the verdict `Expected`.

Developers often don't know that a stub can outlive its use, and forget to clear the fields of a stub they no
longer need. A field like that is a common cause of leaks.

In this leak trace, `ProgressListener` is an inner class, and its `this$0` field holds the destroyed
activity:

```
┬───
│ GC Root: Global variable in native code
│
├─ com.example.UploadActivity$ProgressListener instance 0x23
│    Verdict: Expected (UploadActivity$ProgressListener is a binder stub)
│    Look for the leak in what UploadActivity$ProgressListener holds, see https://square.github.io/leakcanary/binder-stubs/
│    ↓ UploadActivity$ProgressListener.this$0
│                                      ~~~~~~
╰→ com.example.UploadActivity instance 0x14
​     Verdict: Stuck (ObjectWatcher was watching this because its lifecycle has ended and Activity#mDestroyed is true)
​     KeyedWeakReference.key = "39efcc1a-67bf-2040-e7ab-3fc9f94731dc"
​     KeyedWeakReference.watchDurationMillis = 25000
​     KeyedWeakReference.retainedDurationMillis = 10000
```

Make the stub a static class and clear its references when the work is done. In Kotlin, a static class is
a nested class without `inner`:

```kotlin
class UploadActivity : Activity() {

  private val progressListener = ProgressListener(this)

  override fun onDestroy() {
    super.onDestroy()
    progressListener.activity = null
  }

  private class ProgressListener(
    @Volatile var activity: UploadActivity?
  ) : IUploadProgress.Stub() {
    override fun onProgress(percent: Int) {
      activity?.runOnUiThread { /* … */ }
    }
  }
}
```

If the stub is Android framework code, [file a bug](https://issuetracker.google.com/issues/new?component=192705).
If it's library code, file a bug with the library. Either way, clear the reference you own further down the
trace. If the stub holds your callback and your callback holds an activity, set the callback's activity
field to null in `onDestroy()`.
