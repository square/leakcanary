# Getting started

Add LeakCanary to `build.gradle`:

```groovy
dependencies {
  // debugImplementation because LeakCanary should only run in debug builds.
  debugImplementation 'com.squareup.leakcanary:leakcanary-android:2.0-alpha-3'
}
```

**That's it, there is no code change needed!** LeakCanary will automatically show a notification when a memory leak is detected in debug builds.

What's next?

* Learn the [Fundamentals](fundamentals.md)
* Try the [code recipes](recipes.md)
* Read the [FAQ](faq.md)
