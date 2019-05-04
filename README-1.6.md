
# LeakCanary

A memory leak detection library for Android and Java.

*“A small leak will sink a great ship.”* - Benjamin Franklin

<p align="center">
<img src="https://github.com/square/leakcanary/wiki/assets/screenshot.png"/>
</p>

## Getting started

In your `build.gradle`:

```groovy
dependencies {
  debugImplementation 'com.squareup.leakcanary:leakcanary-android:1.6.3'
  releaseImplementation 'com.squareup.leakcanary:leakcanary-android-no-op:1.6.3'
  // Optional, if you use support library fragments:
  debugImplementation 'com.squareup.leakcanary:leakcanary-support-fragment:1.6.3'
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

**You're good to go!** LeakCanary will automatically show a notification when an activity or support fragment memory leak is detected in your debug build.

**What's next?** You could watch a [live investigation](https://www.youtube.com/watch?v=KwArTJHLq5g) then [customize LeakCanary](https://github.com/square/leakcanary/wiki/Customizing-LeakCanary) to your needs.

## FAQ

* [Why should I use LeakCanary?](https://github.com/square/leakcanary/wiki/FAQ#why-should-i-use-leakcanary)
* [How does it work?](https://github.com/square/leakcanary/wiki/FAQ#how-does-it-work)
* [How do I fix a memory leak?](https://github.com/square/leakcanary/wiki/FAQ#how-do-i-fix-a-memory-leak)
* [How do I customize LeakCanary to my needs?](https://github.com/square/leakcanary/wiki/FAQ#how-do-i-customize-leakcanary-to-my-needs)
* [Where can I learn more?](https://github.com/square/leakcanary/wiki/FAQ#where-can-i-learn-more)
* [How do I copy the leak trace?](https://github.com/square/leakcanary/wiki/FAQ#how-do-i-copy-the-leak-trace)
* [Can a leak be caused by the Android SDK?](https://github.com/square/leakcanary/wiki/FAQ#can-a-leak-be-caused-by-the-android-sdk)
* [How can I dig beyond the leak trace?](https://github.com/square/leakcanary/wiki/FAQ#how-can-i-dig-beyond-the-leak-trace)
* [How do disable I LeakCanary in tests?](https://github.com/square/leakcanary/wiki/FAQ#how-do-i-disable-leakcanary-in-tests)
* [How do I fix build errors?](https://github.com/square/leakcanary/wiki/FAQ#how-do-i-fix-build-errors)
* [How many methods does LeakCanary add?](https://github.com/square/leakcanary/wiki/FAQ#how-many-methods-does-leakcanary-add)
* [How do I use the SNAPSHOT version?](https://github.com/square/leakcanary/wiki/FAQ#how-do-i-use-the-snapshot-version)
* [How can I be notified of new releases?](https://github.com/square/leakcanary/wiki/FAQ#how-can-i-be-notified-of-new-releases)
* [Who's behind LeakCanary?](https://github.com/square/leakcanary/wiki/FAQ#whos-behind-leakcanary)
* [Why is it called LeakCanary?](https://github.com/square/leakcanary/wiki/FAQ#why-is-it-called-leakcanary)
* [Who made the logo?](https://github.com/square/leakcanary/wiki/FAQ#who-made-the-logo)
* [Instant Run can trigger invalid leaks](https://github.com/square/leakcanary/wiki/FAQ#instant-run-can-trigger-invalid-leaks)
* [I know I have a leak. Why doesn't the notification show?](https://github.com/square/leakcanary/wiki/FAQ#i-know-i-have-a-leak-why-doesnt-the-notification-show)

<p align="center">
<img src="https://github.com/square/leakcanary/wiki/assets/icon_512.png" width="250"/>
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
