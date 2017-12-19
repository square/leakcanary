
# LeakCanary

A memory leak detection library for Android and Java.

*“A small leak will sink a great ship.”* - Benjamin Franklin

<p align="center">
<img src="https://github.com/square/leakcanary/blob/master/assets/screenshot.png"/>
</p>

## Getting started

In your `build.gradle`:

```groovy
dependencies {
  debugCompile 'com.squareup.leakcanary:leakcanary-android:1.5.4'
  releaseCompile 'com.squareup.leakcanary:leakcanary-android-no-op:1.5.4'
}
```

In your `Application` class:

```java
public class ExampleApplication extends Application {

  @Override public void onCreate() {
    super.onCreate();
    if (LeakCanary.isInAnalyzerProcess(this)) {
      // This process is dedicated to LeakCanary for heap analysis.
      // You should not init your app in this process.
      return;
    }
    LeakCanary.install(this);
    // Normal app init code...
  }
}
```

**You're good to go!** LeakCanary will automatically show a notification when an activity memory leak is detected in your debug build.

To disable LeakCanary in unit tests, add the following to your `build.gradle`:

```groovy
// Ensure the no-op dependency is always used in JVM tests.
configurations.all { config ->
  if (config.name.contains('UnitTest')) {
    config.resolutionStrategy.eachDependency { details ->
      if (details.requested.group == 'com.squareup.leakcanary' && details.requested.name == 'leakcanary-android') {
        details.useTarget(group: details.requested.group, name: 'leakcanary-android-no-op', version: details.requested.version)
      }
    }
  }
}
```

If you want to also disable leak detection in instrumentation tests, add `|| config.name.contains('AndroidTest')` to the
`if` check above.

Questions? Check out [the FAQ](https://github.com/square/leakcanary/wiki/FAQ)!

<p align="center">
<img src="https://github.com/square/leakcanary/blob/master/assets/icon_512.png" width="250"/>
</p>

## License

    Copyright 2015 Square, Inc.

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
