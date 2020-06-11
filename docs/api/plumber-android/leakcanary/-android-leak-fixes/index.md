[plumber-android](../../index.md) / [leakcanary](../index.md) / [AndroidLeakFixes](./index.md)

# AndroidLeakFixes

`enum class AndroidLeakFixes`

A collection of hacks to fix leaks in the Android Framework and other Google Android libraries.

### Enum Values

| Name | Summary |
|---|---|
| [MEDIA_SESSION_LEGACY_HELPER](-m-e-d-i-a_-s-e-s-s-i-o-n_-l-e-g-a-c-y_-h-e-l-p-e-r/index.md) | MediaSessionLegacyHelper is a static singleton and did not use the application context. Introduced in android-5.0.1_r1, fixed in Android 5.1.0_r1. https://github.com/android/platform_frameworks_base/commit/ 9b5257c9c99c4cb541d8e8e78fb04f008b1a9091 |
| [TEXT_LINE_POOL](-t-e-x-t_-l-i-n-e_-p-o-o-l/index.md) | This flushes the TextLine pool when an activity is destroyed, to prevent memory leaks. |
| [USER_MANAGER](-u-s-e-r_-m-a-n-a-g-e-r/index.md) | Obtaining the UserManager service ends up calling the hidden UserManager.get() method which stores the context in a singleton UserManager instance and then stores that instance in a static field. |
| [FLUSH_HANDLER_THREADS](-f-l-u-s-h_-h-a-n-d-l-e-r_-t-h-r-e-a-d-s/index.md) | HandlerThread instances keep local reference to their last handled message after recycling it. That message is obtained by a dialog which sets on an OnClickListener on it and then never recycles it, expecting it to be garbage collected but it ends up being held by the HandlerThread. |
| [ACCESSIBILITY_NODE_INFO](-a-c-c-e-s-s-i-b-i-l-i-t-y_-n-o-d-e_-i-n-f-o/index.md) | Until API 28, AccessibilityNodeInfo has a mOriginalText field that was not properly cleared when instance were put back in the pool. Leak introduced here: https://android.googlesource.com/platform/frameworks/base/+ /193520e3dff5248ddcf8435203bf99d2ba667219%5E%21/core/java/android/view/accessibility /AccessibilityNodeInfo.java |
| [CONNECTIVITY_MANAGER](-c-o-n-n-e-c-t-i-v-i-t-y_-m-a-n-a-g-e-r/index.md) | ConnectivityManager has a sInstance field that is set when the first ConnectivityManager instance is created. ConnectivityManager has a mContext field. When calling activity.getSystemService(Context.CONNECTIVITY_SERVICE) , the first ConnectivityManager instance is created with the activity context and stored in sInstance. That activity context then leaks forever. |
| [SAMSUNG_CLIPBOARD_MANAGER](-s-a-m-s-u-n-g_-c-l-i-p-b-o-a-r-d_-m-a-n-a-g-e-r/index.md) | ClipboardUIManager is a static singleton that leaks an activity context. This fix makes sure the manager is called with an application context. |
| [BUBBLE_POPUP](-b-u-b-b-l-e_-p-o-p-u-p/index.md) | A static helper for EditText bubble popups leaks a reference to the latest focused view. |
| [LAST_HOVERED_VIEW](-l-a-s-t_-h-o-v-e-r-e-d_-v-i-e-w/index.md) | mLastHoveredView is a static field in TextView that leaks the last hovered view. |
| [ACTIVITY_MANAGER](-a-c-t-i-v-i-t-y_-m-a-n-a-g-e-r/index.md) | Samsung added a static mContext field to ActivityManager, holding a reference to the activity. |
| [VIEW_LOCATION_HOLDER](-v-i-e-w_-l-o-c-a-t-i-o-n_-h-o-l-d-e-r/index.md) | In Android P, ViewLocationHolder has an mRoot field that is not cleared in its clear() method. Introduced in https://github.com/aosp-mirror/platform_frameworks_base/commit /86b326012813f09d8f1de7d6d26c986a909d |

### Functions

| Name | Summary |
|---|---|
| [apply](apply.md) | `abstract fun apply(application: Application): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |

### Companion Object Functions

| Name | Summary |
|---|---|
| [applyFixes](apply-fixes.md) | `fun applyFixes(application: Application, fixes: `[`Set`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)`<`[`AndroidLeakFixes`](./index.md)`> = EnumSet.allOf(AndroidLeakFixes::class.java)): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html) |
