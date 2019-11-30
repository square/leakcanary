# Getting started

Add LeakCanary to `build.gradle`:

```groovy
dependencies {
  // debugImplementation because LeakCanary should only run in debug builds.
  debugImplementation 'com.squareup.leakcanary:leakcanary-android:{{ leak_canary.release }}'
}
```

**That's it, there is no code change needed!** You can confirm that LeakCanary is running on startup by filtering on the LeakCanary tag in Logcat:

```
D LeakCanary: Installing AppWatcher
```

!!! info
    To upgrade from LeakCanary *1.6*, follow the [upgrade guide](upgrading-to-leakcanary-2.0.md).

What's next?

* Learn the [Fundamentals](fundamentals.md)
* Try the [code recipes](recipes.md)
* Read the [FAQ](faq.md), e.g. [How does LeakCanary get installed by only adding a dependency?](faq.md#how-does-leakcanary-get-installed-by-only-adding-a-dependency)
