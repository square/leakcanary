# Getting started

Add LeakCanary to `build.gradle`:

```groovy
dependencies {
  // debugImplementation because LeakCanary should only run in debug builds.
  debugImplementation 'com.squareup.leakcanary:leakcanary-android:2.0-beta-4'
}
```

**That's it, there is no code change needed!** LeakCanary will automatically show a notification when a memory leak is detected in debug builds.

What's next?

* Learn the [Fundamentals](fundamentals.md)
* Try the [code recipes](recipes.md)
* Read the [FAQ](faq.md), e.g. [How does LeakCanary get installed by only adding a dependency?](faq.md#how-does-leakcanary-get-installed-by-only-adding-a-dependency)
