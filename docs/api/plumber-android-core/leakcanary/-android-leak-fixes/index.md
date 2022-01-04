//[plumber-android-core](../../../index.md)/[leakcanary](../index.md)/[AndroidLeakFixes](index.md)

# AndroidLeakFixes

[androidJvm]\
enum [AndroidLeakFixes](index.md) : [Enum](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-enum/index.html)&lt;[AndroidLeakFixes](index.md)&gt; 

A collection of hacks to fix leaks in the Android Framework and other Google Android libraries.

## Entries

| | |
|---|---|
| [SPELL_CHECKER](-s-p-e-l-l_-c-h-e-c-k-e-r/index.md) | [androidJvm]<br>[SPELL_CHECKER](-s-p-e-l-l_-c-h-e-c-k-e-r/index.md)()<br>Every editable TextView has an Editor instance which has a SpellChecker instance. SpellChecker is in charge of displaying the little squiggle spans that show typos. SpellChecker starts a SpellCheckerSession as needed and then closes it when the TextView is detached from the window. A SpellCheckerSession is in charge of communicating with the spell checker service (which lives in another process) through TextServicesManager. |
| [IMM_CUR_ROOT_VIEW](-i-m-m_-c-u-r_-r-o-o-t_-v-i-e-w/index.md) | [androidJvm]<br>[IMM_CUR_ROOT_VIEW](-i-m-m_-c-u-r_-r-o-o-t_-v-i-e-w/index.md)()<br>When an activity is destroyed, the corresponding ViewRootImpl instance is released and ready to be garbage collected. Some time after that, ViewRootImpl#W receives a windowfocusChanged() callback, which it normally delegates to ViewRootImpl which in turn calls InputMethodManager#onPreWindowFocus which clears InputMethodManager#mCurRootView. |
| [IMM_FOCUSED_VIEW](-i-m-m_-f-o-c-u-s-e-d_-v-i-e-w/index.md) | [androidJvm]<br>[IMM_FOCUSED_VIEW](-i-m-m_-f-o-c-u-s-e-d_-v-i-e-w/index.md)()<br>Fix for https://code.google.com/p/android/issues/detail?id=171190 . |
| [VIEW_LOCATION_HOLDER](-v-i-e-w_-l-o-c-a-t-i-o-n_-h-o-l-d-e-r/index.md) | [androidJvm]<br>[VIEW_LOCATION_HOLDER](-v-i-e-w_-l-o-c-a-t-i-o-n_-h-o-l-d-e-r/index.md)()<br>In Android P, ViewLocationHolder has an mRoot field that is not cleared in its clear() method. Introduced in https://github.com/aosp-mirror/platform_frameworks_base/commit /86b326012813f09d8f1de7d6d26c986a909d |
| [ACTIVITY_MANAGER](-a-c-t-i-v-i-t-y_-m-a-n-a-g-e-r/index.md) | [androidJvm]<br>[ACTIVITY_MANAGER](-a-c-t-i-v-i-t-y_-m-a-n-a-g-e-r/index.md)()<br>Samsung added a static mContext field to ActivityManager, holding a reference to the activity. |
| [LAST_HOVERED_VIEW](-l-a-s-t_-h-o-v-e-r-e-d_-v-i-e-w/index.md) | [androidJvm]<br>[LAST_HOVERED_VIEW](-l-a-s-t_-h-o-v-e-r-e-d_-v-i-e-w/index.md)()<br>mLastHoveredView is a static field in TextView that leaks the last hovered view. |
| [BUBBLE_POPUP](-b-u-b-b-l-e_-p-o-p-u-p/index.md) | [androidJvm]<br>[BUBBLE_POPUP](-b-u-b-b-l-e_-p-o-p-u-p/index.md)()<br>A static helper for EditText bubble popups leaks a reference to the latest focused view. |
| [SAMSUNG_CLIPBOARD_MANAGER](-s-a-m-s-u-n-g_-c-l-i-p-b-o-a-r-d_-m-a-n-a-g-e-r/index.md) | [androidJvm]<br>[SAMSUNG_CLIPBOARD_MANAGER](-s-a-m-s-u-n-g_-c-l-i-p-b-o-a-r-d_-m-a-n-a-g-e-r/index.md)()<br>ClipboardUIManager is a static singleton that leaks an activity context. This fix makes sure the manager is called with an application context. |
| [CONNECTIVITY_MANAGER](-c-o-n-n-e-c-t-i-v-i-t-y_-m-a-n-a-g-e-r/index.md) | [androidJvm]<br>[CONNECTIVITY_MANAGER](-c-o-n-n-e-c-t-i-v-i-t-y_-m-a-n-a-g-e-r/index.md)()<br>ConnectivityManager has a sInstance field that is set when the first ConnectivityManager instance is created. ConnectivityManager has a mContext field. When calling activity.getSystemService(Context.CONNECTIVITY_SERVICE) , the first ConnectivityManager instance is created with the activity context and stored in sInstance. That activity context then leaks forever. |
| [ACCESSIBILITY_NODE_INFO](-a-c-c-e-s-s-i-b-i-l-i-t-y_-n-o-d-e_-i-n-f-o/index.md) | [androidJvm]<br>[ACCESSIBILITY_NODE_INFO](-a-c-c-e-s-s-i-b-i-l-i-t-y_-n-o-d-e_-i-n-f-o/index.md)()<br>Until API 28, AccessibilityNodeInfo has a mOriginalText field that was not properly cleared when instance were put back in the pool. Leak introduced here: https://android.googlesource.com/platform/frameworks/base/+ /193520e3dff5248ddcf8435203bf99d2ba667219%5E%21/core/java/android/view/accessibility /AccessibilityNodeInfo.java |
| [FLUSH_HANDLER_THREADS](-f-l-u-s-h_-h-a-n-d-l-e-r_-t-h-r-e-a-d-s/index.md) | [androidJvm]<br>[FLUSH_HANDLER_THREADS](-f-l-u-s-h_-h-a-n-d-l-e-r_-t-h-r-e-a-d-s/index.md)()<br>HandlerThread instances keep local reference to their last handled message after recycling it. That message is obtained by a dialog which sets on an OnClickListener on it and then never recycles it, expecting it to be garbage collected but it ends up being held by the HandlerThread. |
| [USER_MANAGER](-u-s-e-r_-m-a-n-a-g-e-r/index.md) | [androidJvm]<br>[USER_MANAGER](-u-s-e-r_-m-a-n-a-g-e-r/index.md)()<br>Obtaining the UserManager service ends up calling the hidden UserManager.get() method which stores the context in a singleton UserManager instance and then stores that instance in a static field. |
| [TEXT_LINE_POOL](-t-e-x-t_-l-i-n-e_-p-o-o-l/index.md) | [androidJvm]<br>[TEXT_LINE_POOL](-t-e-x-t_-l-i-n-e_-p-o-o-l/index.md)()<br>This flushes the TextLine pool when an activity is destroyed, to prevent memory leaks. |
| [MEDIA_SESSION_LEGACY_HELPER](-m-e-d-i-a_-s-e-s-s-i-o-n_-l-e-g-a-c-y_-h-e-l-p-e-r/index.md) | [androidJvm]<br>[MEDIA_SESSION_LEGACY_HELPER](-m-e-d-i-a_-s-e-s-s-i-o-n_-l-e-g-a-c-y_-h-e-l-p-e-r/index.md)()<br>MediaSessionLegacyHelper is a static singleton and did not use the application context. Introduced in android-5.0.1_r1, fixed in Android 5.1.0_r1. https://github.com/android/platform_frameworks_base/commit/ 9b5257c9c99c4cb541d8e8e78fb04f008b1a9091 |

## Types

| Name | Summary |
|---|---|
| [Companion](-companion/index.md) | [androidJvm]<br>object [Companion](-companion/index.md) |

## Properties

| Name | Summary |
|---|---|
| [name](-m-e-d-i-a_-s-e-s-s-i-o-n_-l-e-g-a-c-y_-h-e-l-p-e-r/index.md#-372974862%2FProperties%2F-1073788996) | [androidJvm]<br>val [name](-m-e-d-i-a_-s-e-s-s-i-o-n_-l-e-g-a-c-y_-h-e-l-p-e-r/index.md#-372974862%2FProperties%2F-1073788996): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [ordinal](-m-e-d-i-a_-s-e-s-s-i-o-n_-l-e-g-a-c-y_-h-e-l-p-e-r/index.md#-739389684%2FProperties%2F-1073788996) | [androidJvm]<br>val [ordinal](-m-e-d-i-a_-s-e-s-s-i-o-n_-l-e-g-a-c-y_-h-e-l-p-e-r/index.md#-739389684%2FProperties%2F-1073788996): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
