# Change Log

## Version 1.3.2-SNAPSHOT

* Added ProGuard configuration for debug library [132](https://github.com/square/leakcanary/issues/132).
* 2 new ignored Android SDK leaks: [#26](https://github.com/square/leakcanary/issues/26) [#62](https://github.com/square/leakcanary/issues/62).
* Added excluded leaks to text report [#119](https://github.com/square/leakcanary/issues/119).
* Added LeakCanary SHA to text report [#120](https://github.com/square/leakcanary/issues/120).

### Dependencies

```gradle
 dependencies {
   debugCompile 'com.squareup.leakcanary:leakcanary-android:1.3.2-SNAPSHOT'
   releaseCompile 'com.squareup.leakcanary:leakcanary-android-no-op:1.3.2-SNAPSHOT'
 }
```

Snapshots are available in Sonatype's `snapshots` repository:

```
  repositories {
    mavenCentral()
    maven {
      url 'https://oss.sonatype.org/content/repositories/snapshots/'
    }
  }
```

[![Build Status](https://travis-ci.org/square/leakcanary.svg?branch=master)](https://travis-ci.org/square/leakcanary)

## Version 1.3.1 *(2015-05-16)*

* Heap dumps and analysis results are now saved on the sd card: [#21](https://github.com/square/leakcanary/issues/21).
* `ExcludedRef` and `AndroidExcludedRefs` are customizable: [#12](https://github.com/square/leakcanary/issues/12) [#73](https://github.com/square/leakcanary/issues/73).
* 7 new ignored Android SDK leaks: [#1](https://github.com/square/leakcanary/issues/1) [#4](https://github.com/square/leakcanary/issues/4) [#32](https://github.com/square/leakcanary/issues/32) [#89](https://github.com/square/leakcanary/pull/89) [#82](https://github.com/square/leakcanary/pull/82) [#97](https://github.com/square/leakcanary/pull/97).
* Fixed 3 crashes in LeakCanary: [#37](https://github.com/square/leakcanary/issues/37) [#46](https://github.com/square/leakcanary/issues/46) [#66](https://github.com/square/leakcanary/issues/66).
* Fixed StrictMode thread policy violations: [#15](https://github.com/square/leakcanary/issues/15).
* Updated `minSdkVersion` from `9` to `8`: [#57](https://github.com/square/leakcanary/issues/57).
* Added LeakCanary version name to `LeakCanary.leakInfo()`: [#49](https://github.com/square/leakcanary/issues/49).
* `leakcanary-android-no-op` is lighter, it does not depend on `leakcanary-watcher` anymore, only 2 classes now: [#74](https://github.com/square/leakcanary/issues/74).
* Adding field state details to the text leak trace.
* A Toast is displayed while the heap dump is in progress to warn that the UI will freeze: [#20](https://github.com/square/leakcanary/issues/49). You can customize the toast by providing your own layout named `__leak_canary_heap_dump_toast.xml` (e.g. you could make it an empty layout).
* If the analysis fails, the result and heap dump are kept so that it can be reported to LeakCanary: [#102](https://github.com/square/leakcanary/issues/102).
* Update to HAHA 1.3 to fix a 2 crashes [#3](https://github.com/square/leakcanary/issues/3) [46](https://github.com/square/leakcanary/issues/46)

### Public API changes

* When upgrading from 1.3 to 1.3.1, previously saved heap dumps will not be readable any more, but they won't be removed from the app directory. You should probably uninstall your app.
* Added `android.permission.WRITE_EXTERNAL_STORAGE` to `leakcanary-android` artifact.
* `LeakCanary.androidWatcher()` parameter types have changed (+ExcludedRefs).
* `LeakCanary.leakInfo()` parameter types have changed (+boolean)
* `ExcludedRef` is now serializable and immutable, instances can be created using `ExcludedRef.Builder`.
* `ExcludedRef` is available in `HeapDump`
* `AndroidExcludedRefs` is an enum, you can now pick the leaks you want to ignore in `AndroidExcludedRefs` by creating an `EnumSet` and calling `AndroidExcludedRefs.createBuilder()`.
* `AndroidExcludedRefs.createAppDefaults()` & `AndroidExcludedRefs.createAndroidDefaults()` return a `ExcludedRef.Builder`.
* `ExcludedRef` moved from `leakcanary-analyzer` to `leakcanary-watcher`

### Dependencies

```gradle
 dependencies {
   debugCompile 'com.squareup.leakcanary:leakcanary-android:1.3.1'
   releaseCompile 'com.squareup.leakcanary:leakcanary-android-no-op:1.3.1'
 }
```

### Statistics

* 33 commits to the LeakCanary library code and 11 commits to [HAHA](https://github.com/square/haha).
* 6 contributors: [Pierre-Yves Ricau](https://github.com/square/leakcanary/commits?author=pyricau), [Sergey Shulepov](https://github.com/square/leakcanary/commits?author=pepyakin), [Romain Guy](https://github.com/square/leakcanary/commits?author=romainguy), [liaohuqiu](https://github.com/square/leakcanary/commits?author=liaohuqiu), [Dario Marcato](https://github.com/square/leakcanary/commits?author=dmarcato), [Anders Aagaard](https://github.com/square/leakcanary/commits?author=andaag).

## Version 1.3 *(2015-05-08)*

Initial release.

### Dependencies

```gradle
 dependencies {
   debugCompile 'com.squareup.leakcanary:leakcanary-android:1.3'
   releaseCompile 'com.squareup.leakcanary:leakcanary-android-no-op:1.3'
 }
```