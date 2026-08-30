## Library leaks

A leak in code the app doesn't own, which Shark recognizes by the reference holding it.

That is the only thing separating these from the app's own leaks. It is not a judgement about whether they
matter: a library leak retains the same bytes an app leak does, and the fact that somebody else wrote the
line is no comfort to the device it is running on.

**So there is something to do about every one of them.** What, depends on whose code it is.

### If it's a library

File an issue with the fingerprint from this screen and the chain the window drew — that is enough for a
maintainer to find it without your heap dump. Better still, send the fix; a leak recognized here is usually
a field that outlives what it points at, and the change is small. Then push for a release, and **update
once it lands** — an entry that stays on this screen after the fix shipped is a dependency nobody bumped.

### If it's the Android framework

You can't fix it, so look for the way round it. Read the description on the row: many of them link to the
AOSP change that introduced the leak or to the file it is in, which is where the workaround usually shows
itself — a field to null out, a listener to unregister, a singleton to pre-warm with the application
context instead of an activity.

**Some of them already have a workaround written.** `AndroidLeakFixes` in `plumber-android` applies ten of
them at process start, and LeakCanary installs plumber for you, so a framework leak on this screen is
sometimes one nobody has added a fix for yet rather than one nobody can. Its source is the reference for
what a workaround for this kind of leak looks like:
[AndroidLeakFixes.kt](https://github.com/square/leakcanary/blob/main/plumber/plumber-android-core/src/main/java/leakcanary/AndroidLeakFixes.kt).

And report it upstream anyway — to the [Android issue tracker](https://issuetracker.google.com/issues), or
to the manufacturer when it is one of theirs. A framework leak that nobody reports is a framework leak that
ships in the next version too.

### If it's neither

A pattern can match a reference it wasn't written for, and then the row is telling you about the wrong
thing. Shark's own list of patterns is
[AndroidReferenceMatchers](https://github.com/square/leakcanary/blob/main/shark/shark-android/src/main/java/shark/AndroidReferenceMatchers.kt);
a pattern that matches too much is worth an issue on LeakCanary.
