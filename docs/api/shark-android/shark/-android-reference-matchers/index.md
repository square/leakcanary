[shark-android](../../index.md) / [shark](../index.md) / [AndroidReferenceMatchers](./index.md)

# AndroidReferenceMatchers

`enum class AndroidReferenceMatchers`

[AndroidReferenceMatchers](./index.md) values add [ReferenceMatcher](#) instances to a global list via their
[add](#) method. A [ReferenceMatcher](#) is either a [IgnoredReferenceMatcher](#) or
a [LibraryLeakReferenceMatcher](#).

[AndroidReferenceMatchers](./index.md) is used to build the list of known references that cannot ever create
leaks (via [IgnoredReferenceMatcher](#)) as well as the list of known leaks in the Android Framework
and in manufacturer specific Android implementations.

This class is a work in progress. You can help by reporting leak traces that seem to be caused
by the Android SDK, here: https://github.com/square/leakcanary/issues/new

We filter on SDK versions and Manufacturers because many of those leaks are specific to a given
manufacturer implementation, they usually share their builds across multiple models, and the
leaks eventually get fixed in newer versions.

Most app developers should use [appDefaults](app-defaults.md). However, you can also use a subset of
[AndroidReferenceMatchers](./index.md) by creating an [EnumSet](https://docs.oracle.com/javase/6/docs/api/java/util/EnumSet.html) that matches your needs and calling
[buildKnownReferences](build-known-references.md).

### Enum Values

| Name | Summary |
|---|---|
| [IREQUEST_FINISH_CALLBACK](-i-r-e-q-u-e-s-t_-f-i-n-i-s-h_-c-a-l-l-b-a-c-k.md) |  |
| [ACTIVITY_CLIENT_RECORD__NEXT_IDLE](-a-c-t-i-v-i-t-y_-c-l-i-e-n-t_-r-e-c-o-r-d__-n-e-x-t_-i-d-l-e.md) |  |
| [SPAN_CONTROLLER](-s-p-a-n_-c-o-n-t-r-o-l-l-e-r.md) |  |
| [MEDIA_SESSION_LEGACY_HELPER__SINSTANCE](-m-e-d-i-a_-s-e-s-s-i-o-n_-l-e-g-a-c-y_-h-e-l-p-e-r__-s-i-n-s-t-a-n-c-e.md) |  |
| [TEXT_LINE__SCACHED](-t-e-x-t_-l-i-n-e__-s-c-a-c-h-e-d.md) |  |
| [BLOCKING_QUEUE](-b-l-o-c-k-i-n-g_-q-u-e-u-e.md) |  |
| [INPUT_METHOD_MANAGER_IS_TERRIBLE](-i-n-p-u-t_-m-e-t-h-o-d_-m-a-n-a-g-e-r_-i-s_-t-e-r-r-i-b-l-e.md) |  |
| [LAYOUT_TRANSITION](-l-a-y-o-u-t_-t-r-a-n-s-i-t-i-o-n.md) |  |
| [SPELL_CHECKER_SESSION](-s-p-e-l-l_-c-h-e-c-k-e-r_-s-e-s-s-i-o-n.md) |  |
| [SPELL_CHECKER](-s-p-e-l-l_-c-h-e-c-k-e-r.md) |  |
| [ACTIVITY_CHOOSE_MODEL](-a-c-t-i-v-i-t-y_-c-h-o-o-s-e_-m-o-d-e-l.md) |  |
| [MEDIA_PROJECTION_CALLBACK](-m-e-d-i-a_-p-r-o-j-e-c-t-i-o-n_-c-a-l-l-b-a-c-k.md) |  |
| [SPEECH_RECOGNIZER](-s-p-e-e-c-h_-r-e-c-o-g-n-i-z-e-r.md) |  |
| [ACCOUNT_MANAGER](-a-c-c-o-u-n-t_-m-a-n-a-g-e-r.md) |  |
| [MEDIA_SCANNER_CONNECTION](-m-e-d-i-a_-s-c-a-n-n-e-r_-c-o-n-n-e-c-t-i-o-n.md) |  |
| [USER_MANAGER__SINSTANCE](-u-s-e-r_-m-a-n-a-g-e-r__-s-i-n-s-t-a-n-c-e.md) |  |
| [APP_WIDGET_HOST_CALLBACKS](-a-p-p_-w-i-d-g-e-t_-h-o-s-t_-c-a-l-l-b-a-c-k-s.md) |  |
| [AUDIO_MANAGER](-a-u-d-i-o_-m-a-n-a-g-e-r.md) |  |
| [EDITTEXT_BLINK_MESSAGEQUEUE](-e-d-i-t-t-e-x-t_-b-l-i-n-k_-m-e-s-s-a-g-e-q-u-e-u-e.md) |  |
| [CONNECTIVITY_MANAGER__SINSTANCE](-c-o-n-n-e-c-t-i-v-i-t-y_-m-a-n-a-g-e-r__-s-i-n-s-t-a-n-c-e.md) |  |
| [ACCESSIBILITY_NODE_INFO__MORIGINALTEXT](-a-c-c-e-s-s-i-b-i-l-i-t-y_-n-o-d-e_-i-n-f-o__-m-o-r-i-g-i-n-a-l-t-e-x-t.md) |  |
| [ASSIST_STRUCTURE](-a-s-s-i-s-t_-s-t-r-u-c-t-u-r-e.md) |  |
| [ACCESSIBILITY_ITERATORS](-a-c-c-e-s-s-i-b-i-l-i-t-y_-i-t-e-r-a-t-o-r-s.md) |  |
| [BIOMETRIC_PROMPT](-b-i-o-m-e-t-r-i-c_-p-r-o-m-p-t.md) |  |
| [MAGNIFIER](-m-a-g-n-i-f-i-e-r.md) |  |
| [BACKDROP_FRAME_RENDERER__MDECORVIEW](-b-a-c-k-d-r-o-p_-f-r-a-m-e_-r-e-n-d-e-r-e-r__-m-d-e-c-o-r-v-i-e-w.md) |  |
| [VIEWLOCATIONHOLDER_ROOT](-v-i-e-w-l-o-c-a-t-i-o-n-h-o-l-d-e-r_-r-o-o-t.md) |  |
| [ACCESSIBILITY_NODE_ID_MANAGER](-a-c-c-e-s-s-i-b-i-l-i-t-y_-n-o-d-e_-i-d_-m-a-n-a-g-e-r.md) |  |
| [TEXT_TO_SPEECH](-t-e-x-t_-t-o_-s-p-e-e-c-h.md) |  |
| [WINDOW_MANAGER_GLOBAL](-w-i-n-d-o-w_-m-a-n-a-g-e-r_-g-l-o-b-a-l.md) |  |
| [CONTROLLED_INPUT_CONNECTION_WRAPPER](-c-o-n-t-r-o-l-l-e-d_-i-n-p-u-t_-c-o-n-n-e-c-t-i-o-n_-w-r-a-p-p-e-r.md) |  |
| [TOAST_TN](-t-o-a-s-t_-t-n.md) |  |
| [SPEN_GESTURE_MANAGER](-s-p-e-n_-g-e-s-t-u-r-e_-m-a-n-a-g-e-r.md) |  |
| [CLIPBOARD_UI_MANAGER__SINSTANCE](-c-l-i-p-b-o-a-r-d_-u-i_-m-a-n-a-g-e-r__-s-i-n-s-t-a-n-c-e.md) |  |
| [SEM_CLIPBOARD_MANAGER__MCONTEXT](-s-e-m_-c-l-i-p-b-o-a-r-d_-m-a-n-a-g-e-r__-m-c-o-n-t-e-x-t.md) |  |
| [CLIPBOARD_EX_MANAGER](-c-l-i-p-b-o-a-r-d_-e-x_-m-a-n-a-g-e-r.md) |  |
| [SEM_EMERGENCY_MANAGER__MCONTEXT](-s-e-m_-e-m-e-r-g-e-n-c-y_-m-a-n-a-g-e-r__-m-c-o-n-t-e-x-t.md) |  |
| [SEM_PERSONA_MANAGER](-s-e-m_-p-e-r-s-o-n-a_-m-a-n-a-g-e-r.md) |  |
| [SEM_APP_ICON_SOLUTION](-s-e-m_-a-p-p_-i-c-o-n_-s-o-l-u-t-i-o-n.md) |  |
| [AW_RESOURCE__SRESOURCES](-a-w_-r-e-s-o-u-r-c-e__-s-r-e-s-o-u-r-c-e-s.md) |  |
| [TEXT_VIEW__MLAST_HOVERED_VIEW](-t-e-x-t_-v-i-e-w__-m-l-a-s-t_-h-o-v-e-r-e-d_-v-i-e-w.md) |  |
| [PERSONA_MANAGER](-p-e-r-s-o-n-a_-m-a-n-a-g-e-r.md) |  |
| [RESOURCES__MCONTEXT](-r-e-s-o-u-r-c-e-s__-m-c-o-n-t-e-x-t.md) |  |
| [VIEW_CONFIGURATION__MCONTEXT](-v-i-e-w_-c-o-n-f-i-g-u-r-a-t-i-o-n__-m-c-o-n-t-e-x-t.md) |  |
| [AUDIO_MANAGER__MCONTEXT_STATIC](-a-u-d-i-o_-m-a-n-a-g-e-r__-m-c-o-n-t-e-x-t_-s-t-a-t-i-c.md) |  |
| [ACTIVITY_MANAGER_MCONTEXT](-a-c-t-i-v-i-t-y_-m-a-n-a-g-e-r_-m-c-o-n-t-e-x-t.md) |  |
| [STATIC_MTARGET_VIEW](-s-t-a-t-i-c_-m-t-a-r-g-e-t_-v-i-e-w.md) |  |
| [MULTI_WINDOW_DECOR_SUPPORT__MWINDOW](-m-u-l-t-i_-w-i-n-d-o-w_-d-e-c-o-r_-s-u-p-p-o-r-t__-m-w-i-n-d-o-w.md) |  |
| [GESTURE_BOOST_MANAGER](-g-e-s-t-u-r-e_-b-o-o-s-t_-m-a-n-a-g-e-r.md) |  |
| [BUBBLE_POPUP_HELPER__SHELPER](-b-u-b-b-l-e_-p-o-p-u-p_-h-e-l-p-e-r__-s-h-e-l-p-e-r.md) |  |
| [LGCONTEXT__MCONTEXT](-l-g-c-o-n-t-e-x-t__-m-c-o-n-t-e-x-t.md) |  |
| [SMART_COVER_MANAGER](-s-m-a-r-t_-c-o-v-e-r_-m-a-n-a-g-e-r.md) |  |
| [MAPPER_CLIENT](-m-a-p-p-e-r_-c-l-i-e-n-t.md) |  |
| [SYSTEM_SENSOR_MANAGER__MAPPCONTEXTIMPL](-s-y-s-t-e-m_-s-e-n-s-o-r_-m-a-n-a-g-e-r__-m-a-p-p-c-o-n-t-e-x-t-i-m-p-l.md) |  |
| [INSTRUMENTATION_RECOMMEND_ACTIVITY](-i-n-s-t-r-u-m-e-n-t-a-t-i-o-n_-r-e-c-o-m-m-e-n-d_-a-c-t-i-v-i-t-y.md) |  |
| [DEVICE_POLICY_MANAGER__SETTINGS_OBSERVER](-d-e-v-i-c-e_-p-o-l-i-c-y_-m-a-n-a-g-e-r__-s-e-t-t-i-n-g-s_-o-b-s-e-r-v-e-r.md) |  |
| [EXTENDED_STATUS_BAR_MANAGER](-e-x-t-e-n-d-e-d_-s-t-a-t-u-s_-b-a-r_-m-a-n-a-g-e-r.md) |  |
| [OEM_SCENE_CALL_BLOCKER](-o-e-m_-s-c-e-n-e_-c-a-l-l_-b-l-o-c-k-e-r.md) |  |
| [RAZER_TEXT_KEY_LISTENER__MCONTEXT](-r-a-z-e-r_-t-e-x-t_-k-e-y_-l-i-s-t-e-n-e-r__-m-c-o-n-t-e-x-t.md) |  |
| [REFERENCES](-r-e-f-e-r-e-n-c-e-s.md) |  |
| [FINALIZER_WATCHDOG_DAEMON](-f-i-n-a-l-i-z-e-r_-w-a-t-c-h-d-o-g_-d-a-e-m-o-n.md) |  |
| [MAIN](-m-a-i-n.md) |  |
| [LEAK_CANARY_THREAD](-l-e-a-k_-c-a-n-a-r-y_-t-h-r-e-a-d.md) |  |
| [LEAK_CANARY_HEAP_DUMPER](-l-e-a-k_-c-a-n-a-r-y_-h-e-a-p_-d-u-m-p-e-r.md) |  |
| [LEAK_CANARY_INTERNAL](-l-e-a-k_-c-a-n-a-r-y_-i-n-t-e-r-n-a-l.md) |  |
| [EVENT_RECEIVER__MMESSAGE_QUEUE](-e-v-e-n-t_-r-e-c-e-i-v-e-r__-m-m-e-s-s-a-g-e_-q-u-e-u-e.md) |  |

### Companion Object Properties

| Name | Summary |
|---|---|
| [appDefaults](app-defaults.md) | `val appDefaults: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<ReferenceMatcher>` |
| [HUAWEI](-h-u-a-w-e-i.md) | `const val HUAWEI: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [ignoredReferencesOnly](ignored-references-only.md) | `val ignoredReferencesOnly: `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<ReferenceMatcher>`<br>Returns a list of [ReferenceMatcher](#) that only contains [IgnoredReferenceMatcher](#) and no [LibraryLeakReferenceMatcher](#). |
| [LENOVO](-l-e-n-o-v-o.md) | `const val LENOVO: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [LG](-l-g.md) | `const val LG: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [MEIZU](-m-e-i-z-u.md) | `const val MEIZU: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [MOTOROLA](-m-o-t-o-r-o-l-a.md) | `const val MOTOROLA: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [NVIDIA](-n-v-i-d-i-a.md) | `const val NVIDIA: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [ONE_PLUS](-o-n-e_-p-l-u-s.md) | `const val ONE_PLUS: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [RAZER](-r-a-z-e-r.md) | `const val RAZER: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [SAMSUNG](-s-a-m-s-u-n-g.md) | `const val SAMSUNG: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [SHARP](-s-h-a-r-p.md) | `const val SHARP: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [VIVO](-v-i-v-o.md) | `const val VIVO: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |

### Companion Object Functions

| Name | Summary |
|---|---|
| [buildKnownReferences](build-known-references.md) | `fun buildKnownReferences(referenceMatchers: `[`Set`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-set/index.html)`<`[`AndroidReferenceMatchers`](./index.md)`>): `[`List`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-list/index.html)`<ReferenceMatcher>`<br>Builds a list of [ReferenceMatcher](#) from the [referenceMatchers](build-known-references.md#shark.AndroidReferenceMatchers.Companion$buildKnownReferences(kotlin.collections.Set((shark.AndroidReferenceMatchers)))/referenceMatchers) set of [AndroidReferenceMatchers](./index.md). |
| [ignoredInstanceField](ignored-instance-field.md) | `fun ignoredInstanceField(className: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`, fieldName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`): IgnoredReferenceMatcher`<br>Creates a [IgnoredReferenceMatcher](#) that matches a [InstanceFieldPattern](#). |
| [ignoredJavaLocal](ignored-java-local.md) | `fun ignoredJavaLocal(threadName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`): IgnoredReferenceMatcher`<br>Creates a [IgnoredReferenceMatcher](#) that matches a [JavaLocalPattern](#). |
| [instanceFieldLeak](instance-field-leak.md) | `fun instanceFieldLeak(className: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`, fieldName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`, description: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)` = "", patternApplies: `[`AndroidBuildMirror`](../-android-build-mirror/index.md)`.() -> `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = ALWAYS): LibraryLeakReferenceMatcher`<br>Creates a [LibraryLeakReferenceMatcher](#) that matches a [InstanceFieldPattern](#). [description](instance-field-leak.md#shark.AndroidReferenceMatchers.Companion$instanceFieldLeak(kotlin.String, kotlin.String, kotlin.String, kotlin.Function1((shark.AndroidBuildMirror, kotlin.Boolean)))/description) should convey what we know about this library leak. |
| [nativeGlobalVariableLeak](native-global-variable-leak.md) | `fun nativeGlobalVariableLeak(className: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`, description: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)` = "", patternApplies: `[`AndroidBuildMirror`](../-android-build-mirror/index.md)`.() -> `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = ALWAYS): LibraryLeakReferenceMatcher` |
| [staticFieldLeak](static-field-leak.md) | `fun staticFieldLeak(className: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`, fieldName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)`, description: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)` = "", patternApplies: `[`AndroidBuildMirror`](../-android-build-mirror/index.md)`.() -> `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)` = ALWAYS): LibraryLeakReferenceMatcher`<br>Creates a [LibraryLeakReferenceMatcher](#) that matches a [StaticFieldPattern](#). [description](static-field-leak.md#shark.AndroidReferenceMatchers.Companion$staticFieldLeak(kotlin.String, kotlin.String, kotlin.String, kotlin.Function1((shark.AndroidBuildMirror, kotlin.Boolean)))/description) should convey what we know about this library leak. |
