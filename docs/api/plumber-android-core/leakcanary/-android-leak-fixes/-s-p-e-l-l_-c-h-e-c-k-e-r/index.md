//[plumber-android-core](../../../../index.md)/[leakcanary](../../index.md)/[AndroidLeakFixes](../index.md)/[SPELL_CHECKER](index.md)

# SPELL_CHECKER

[androidJvm]\
[SPELL_CHECKER](index.md)()

Every editable TextView has an Editor instance which has a SpellChecker instance. SpellChecker is in charge of displaying the little squiggle spans that show typos. SpellChecker starts a SpellCheckerSession as needed and then closes it when the TextView is detached from the window. A SpellCheckerSession is in charge of communicating with the spell checker service (which lives in another process) through TextServicesManager.

The SpellChecker sends the TextView content to the spell checker service every 400ms, ie every time the service calls back with a result the SpellChecker schedules another check for 400ms later.

When the TextView is detached from the window, the spell checker closes the session. In practice, SpellCheckerSessionListenerImpl.mHandler is set to null and when the service calls SpellCheckerSessionListenerImpl.onGetSuggestions or SpellCheckerSessionListenerImpl.onGetSentenceSuggestions back from another process, there's a null check for SpellCheckerSessionListenerImpl.mHandler and the callback is dropped.

Unfortunately, on Android M there's a race condition in how that's done. When the service calls back into our app process, the IPC call is received on a binder thread. That's when the null check happens. If the session is not closed at this point (mHandler not null), the callback is then posted to the main thread. If on the main thread the session is closed after that post but prior to that post being handled, then the post will still be processed, after the session has been closed.

When the post is processed, SpellCheckerSession calls back into SpellChecker which in turns schedules a new spell check to be ran in 400ms. The check is an anonymous inner class (SpellChecker$1) stored as SpellChecker.mSpellRunnable and implementing Runnable. It is scheduled by calling [View.postDelayed](https://developer.android.com/reference/kotlin/android/view/View.html#postdelayed). As we've seen, at this point the session may be closed which means that the view has been detached. [View.postDelayed](https://developer.android.com/reference/kotlin/android/view/View.html#postdelayed) behaves differently when a view is detached: instead of posting to the single [Handler](https://developer.android.com/reference/kotlin/android/os/Handler.html) used by the view hierarchy, it enqueues the Runnable into ViewRootImpl.RunQueue, a static queue that holds on to "actions" to be executed. As soon as a view hierarchy is attached, the ViewRootImpl.RunQueue is processed and emptied.

Unfortunately, that means that as long as no view hierarchy is attached, ie as long as there are no activities alive, the actions stay in ViewRootImpl.RunQueue. That means SpellChecker$1 ends up being kept in memory. It holds on to SpellChecker which in turns holds on to the detached TextView and corresponding destroyed activity & view hierarchy.

We have a fix for this! When the spell check session is closed, we replace SpellCheckerSession.mSpellCheckerSessionListener (which normally is the SpellChecker) with a no-op implementation. So even if callbacks are enqueued to the main thread handler, these callbacks will call the no-op implementation and SpellChecker will not be scheduling a spell check.

Sources to corroborate:

https://android.googlesource.com/platform/frameworks/base/+/marshmallow-release /core/java/android/view/textservice/SpellCheckerSession.java /core/java/android/view/textservice/TextServicesManager.java /core/java/android/widget/SpellChecker.java /core/java/android/view/ViewRootImpl.java

## Properties

| Name | Summary |
|---|---|
| [name](../-m-e-d-i-a_-s-e-s-s-i-o-n_-l-e-g-a-c-y_-h-e-l-p-e-r/index.md#-372974862%2FProperties%2F-1073788996) | [androidJvm]<br>val [name](../-m-e-d-i-a_-s-e-s-s-i-o-n_-l-e-g-a-c-y_-h-e-l-p-e-r/index.md#-372974862%2FProperties%2F-1073788996): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [ordinal](../-m-e-d-i-a_-s-e-s-s-i-o-n_-l-e-g-a-c-y_-h-e-l-p-e-r/index.md#-739389684%2FProperties%2F-1073788996) | [androidJvm]<br>val [ordinal](../-m-e-d-i-a_-s-e-s-s-i-o-n_-l-e-g-a-c-y_-h-e-l-p-e-r/index.md#-739389684%2FProperties%2F-1073788996): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
