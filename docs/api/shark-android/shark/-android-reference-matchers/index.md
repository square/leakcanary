//[shark-android](../../../index.md)/[shark](../index.md)/[AndroidReferenceMatchers](index.md)

# AndroidReferenceMatchers

[jvm]\
enum [AndroidReferenceMatchers](index.md) : [Enum](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-enum/index.html)&lt;[AndroidReferenceMatchers](index.md)&gt; 

[AndroidReferenceMatchers](index.md) values add ReferenceMatcher instances to a global list via their add method. A ReferenceMatcher is either a IgnoredReferenceMatcher or a LibraryLeakReferenceMatcher.

[AndroidReferenceMatchers](index.md) is used to build the list of known references that cannot ever create leaks (via IgnoredReferenceMatcher) as well as the list of known leaks in the Android Framework and in manufacturer specific Android implementations.

This class is a work in progress. You can help by reporting leak traces that seem to be caused by the Android SDK, here: https://github.com/square/leakcanary/issues/new

We filter on SDK versions and Manufacturers because many of those leaks are specific to a given manufacturer implementation, they usually share their builds across multiple models, and the leaks eventually get fixed in newer versions.

Most app developers should use [appDefaults](-companion/app-defaults.md). However, you can also use a subset of [AndroidReferenceMatchers](index.md) by creating an [EnumSet](https://docs.oracle.com/javase/8/docs/api/java/util/EnumSet.html) that matches your needs and calling [buildKnownReferences](-companion/build-known-references.md).

## Entries

| | |
|---|---|
| [EVENT_RECEIVER__MMESSAGE_QUEUE](-e-v-e-n-t_-r-e-c-e-i-v-e-r__-m-m-e-s-s-a-g-e_-q-u-e-u-e/index.md) | [jvm]<br>[EVENT_RECEIVER__MMESSAGE_QUEUE](-e-v-e-n-t_-r-e-c-e-i-v-e-r__-m-m-e-s-s-a-g-e_-q-u-e-u-e/index.md)() |
| [LEAK_CANARY_INTERNAL](-l-e-a-k_-c-a-n-a-r-y_-i-n-t-e-r-n-a-l/index.md) | [jvm]<br>[LEAK_CANARY_INTERNAL](-l-e-a-k_-c-a-n-a-r-y_-i-n-t-e-r-n-a-l/index.md)() |
| [LEAK_CANARY_HEAP_DUMPER](-l-e-a-k_-c-a-n-a-r-y_-h-e-a-p_-d-u-m-p-e-r/index.md) | [jvm]<br>[LEAK_CANARY_HEAP_DUMPER](-l-e-a-k_-c-a-n-a-r-y_-h-e-a-p_-d-u-m-p-e-r/index.md)() |
| [LEAK_CANARY_THREAD](-l-e-a-k_-c-a-n-a-r-y_-t-h-r-e-a-d/index.md) | [jvm]<br>[LEAK_CANARY_THREAD](-l-e-a-k_-c-a-n-a-r-y_-t-h-r-e-a-d/index.md)() |
| [MAIN](-m-a-i-n/index.md) | [jvm]<br>[MAIN](-m-a-i-n/index.md)() |
| [FINALIZER_WATCHDOG_DAEMON](-f-i-n-a-l-i-z-e-r_-w-a-t-c-h-d-o-g_-d-a-e-m-o-n/index.md) | [jvm]<br>[FINALIZER_WATCHDOG_DAEMON](-f-i-n-a-l-i-z-e-r_-w-a-t-c-h-d-o-g_-d-a-e-m-o-n/index.md)() |
| [REFERENCES](-r-e-f-e-r-e-n-c-e-s/index.md) | [jvm]<br>[REFERENCES](-r-e-f-e-r-e-n-c-e-s/index.md)() |
| [RAZER_TEXT_KEY_LISTENER__MCONTEXT](-r-a-z-e-r_-t-e-x-t_-k-e-y_-l-i-s-t-e-n-e-r__-m-c-o-n-t-e-x-t/index.md) | [jvm]<br>[RAZER_TEXT_KEY_LISTENER__MCONTEXT](-r-a-z-e-r_-t-e-x-t_-k-e-y_-l-i-s-t-e-n-e-r__-m-c-o-n-t-e-x-t/index.md)() |
| [OEM_SCENE_CALL_BLOCKER](-o-e-m_-s-c-e-n-e_-c-a-l-l_-b-l-o-c-k-e-r/index.md) | [jvm]<br>[OEM_SCENE_CALL_BLOCKER](-o-e-m_-s-c-e-n-e_-c-a-l-l_-b-l-o-c-k-e-r/index.md)() |
| [EXTENDED_STATUS_BAR_MANAGER](-e-x-t-e-n-d-e-d_-s-t-a-t-u-s_-b-a-r_-m-a-n-a-g-e-r/index.md) | [jvm]<br>[EXTENDED_STATUS_BAR_MANAGER](-e-x-t-e-n-d-e-d_-s-t-a-t-u-s_-b-a-r_-m-a-n-a-g-e-r/index.md)() |
| [DEVICE_POLICY_MANAGER__SETTINGS_OBSERVER](-d-e-v-i-c-e_-p-o-l-i-c-y_-m-a-n-a-g-e-r__-s-e-t-t-i-n-g-s_-o-b-s-e-r-v-e-r/index.md) | [jvm]<br>[DEVICE_POLICY_MANAGER__SETTINGS_OBSERVER](-d-e-v-i-c-e_-p-o-l-i-c-y_-m-a-n-a-g-e-r__-s-e-t-t-i-n-g-s_-o-b-s-e-r-v-e-r/index.md)() |
| [INSTRUMENTATION_RECOMMEND_ACTIVITY](-i-n-s-t-r-u-m-e-n-t-a-t-i-o-n_-r-e-c-o-m-m-e-n-d_-a-c-t-i-v-i-t-y/index.md) | [jvm]<br>[INSTRUMENTATION_RECOMMEND_ACTIVITY](-i-n-s-t-r-u-m-e-n-t-a-t-i-o-n_-r-e-c-o-m-m-e-n-d_-a-c-t-i-v-i-t-y/index.md)() |
| [SYSTEM_SENSOR_MANAGER__MAPPCONTEXTIMPL](-s-y-s-t-e-m_-s-e-n-s-o-r_-m-a-n-a-g-e-r__-m-a-p-p-c-o-n-t-e-x-t-i-m-p-l/index.md) | [jvm]<br>[SYSTEM_SENSOR_MANAGER__MAPPCONTEXTIMPL](-s-y-s-t-e-m_-s-e-n-s-o-r_-m-a-n-a-g-e-r__-m-a-p-p-c-o-n-t-e-x-t-i-m-p-l/index.md)() |
| [MAPPER_CLIENT](-m-a-p-p-e-r_-c-l-i-e-n-t/index.md) | [jvm]<br>[MAPPER_CLIENT](-m-a-p-p-e-r_-c-l-i-e-n-t/index.md)() |
| [SMART_COVER_MANAGER](-s-m-a-r-t_-c-o-v-e-r_-m-a-n-a-g-e-r/index.md) | [jvm]<br>[SMART_COVER_MANAGER](-s-m-a-r-t_-c-o-v-e-r_-m-a-n-a-g-e-r/index.md)() |
| [LGCONTEXT__MCONTEXT](-l-g-c-o-n-t-e-x-t__-m-c-o-n-t-e-x-t/index.md) | [jvm]<br>[LGCONTEXT__MCONTEXT](-l-g-c-o-n-t-e-x-t__-m-c-o-n-t-e-x-t/index.md)() |
| [BUBBLE_POPUP_HELPER__SHELPER](-b-u-b-b-l-e_-p-o-p-u-p_-h-e-l-p-e-r__-s-h-e-l-p-e-r/index.md) | [jvm]<br>[BUBBLE_POPUP_HELPER__SHELPER](-b-u-b-b-l-e_-p-o-p-u-p_-h-e-l-p-e-r__-s-h-e-l-p-e-r/index.md)() |
| [GESTURE_BOOST_MANAGER](-g-e-s-t-u-r-e_-b-o-o-s-t_-m-a-n-a-g-e-r/index.md) | [jvm]<br>[GESTURE_BOOST_MANAGER](-g-e-s-t-u-r-e_-b-o-o-s-t_-m-a-n-a-g-e-r/index.md)() |
| [MULTI_WINDOW_DECOR_SUPPORT__MWINDOW](-m-u-l-t-i_-w-i-n-d-o-w_-d-e-c-o-r_-s-u-p-p-o-r-t__-m-w-i-n-d-o-w/index.md) | [jvm]<br>[MULTI_WINDOW_DECOR_SUPPORT__MWINDOW](-m-u-l-t-i_-w-i-n-d-o-w_-d-e-c-o-r_-s-u-p-p-o-r-t__-m-w-i-n-d-o-w/index.md)() |
| [STATIC_MTARGET_VIEW](-s-t-a-t-i-c_-m-t-a-r-g-e-t_-v-i-e-w/index.md) | [jvm]<br>[STATIC_MTARGET_VIEW](-s-t-a-t-i-c_-m-t-a-r-g-e-t_-v-i-e-w/index.md)() |
| [ACTIVITY_MANAGER_MCONTEXT](-a-c-t-i-v-i-t-y_-m-a-n-a-g-e-r_-m-c-o-n-t-e-x-t/index.md) | [jvm]<br>[ACTIVITY_MANAGER_MCONTEXT](-a-c-t-i-v-i-t-y_-m-a-n-a-g-e-r_-m-c-o-n-t-e-x-t/index.md)() |
| [AUDIO_MANAGER__MCONTEXT_STATIC](-a-u-d-i-o_-m-a-n-a-g-e-r__-m-c-o-n-t-e-x-t_-s-t-a-t-i-c/index.md) | [jvm]<br>[AUDIO_MANAGER__MCONTEXT_STATIC](-a-u-d-i-o_-m-a-n-a-g-e-r__-m-c-o-n-t-e-x-t_-s-t-a-t-i-c/index.md)() |
| [VIEW_CONFIGURATION__MCONTEXT](-v-i-e-w_-c-o-n-f-i-g-u-r-a-t-i-o-n__-m-c-o-n-t-e-x-t/index.md) | [jvm]<br>[VIEW_CONFIGURATION__MCONTEXT](-v-i-e-w_-c-o-n-f-i-g-u-r-a-t-i-o-n__-m-c-o-n-t-e-x-t/index.md)() |
| [RESOURCES__MCONTEXT](-r-e-s-o-u-r-c-e-s__-m-c-o-n-t-e-x-t/index.md) | [jvm]<br>[RESOURCES__MCONTEXT](-r-e-s-o-u-r-c-e-s__-m-c-o-n-t-e-x-t/index.md)() |
| [PERSONA_MANAGER](-p-e-r-s-o-n-a_-m-a-n-a-g-e-r/index.md) | [jvm]<br>[PERSONA_MANAGER](-p-e-r-s-o-n-a_-m-a-n-a-g-e-r/index.md)() |
| [TEXT_VIEW__MLAST_HOVERED_VIEW](-t-e-x-t_-v-i-e-w__-m-l-a-s-t_-h-o-v-e-r-e-d_-v-i-e-w/index.md) | [jvm]<br>[TEXT_VIEW__MLAST_HOVERED_VIEW](-t-e-x-t_-v-i-e-w__-m-l-a-s-t_-h-o-v-e-r-e-d_-v-i-e-w/index.md)() |
| [AW_RESOURCE__SRESOURCES](-a-w_-r-e-s-o-u-r-c-e__-s-r-e-s-o-u-r-c-e-s/index.md) | [jvm]<br>[AW_RESOURCE__SRESOURCES](-a-w_-r-e-s-o-u-r-c-e__-s-r-e-s-o-u-r-c-e-s/index.md)() |
| [SEM_APP_ICON_SOLUTION](-s-e-m_-a-p-p_-i-c-o-n_-s-o-l-u-t-i-o-n/index.md) | [jvm]<br>[SEM_APP_ICON_SOLUTION](-s-e-m_-a-p-p_-i-c-o-n_-s-o-l-u-t-i-o-n/index.md)() |
| [SEM_PERSONA_MANAGER](-s-e-m_-p-e-r-s-o-n-a_-m-a-n-a-g-e-r/index.md) | [jvm]<br>[SEM_PERSONA_MANAGER](-s-e-m_-p-e-r-s-o-n-a_-m-a-n-a-g-e-r/index.md)() |
| [SEM_EMERGENCY_MANAGER__MCONTEXT](-s-e-m_-e-m-e-r-g-e-n-c-y_-m-a-n-a-g-e-r__-m-c-o-n-t-e-x-t/index.md) | [jvm]<br>[SEM_EMERGENCY_MANAGER__MCONTEXT](-s-e-m_-e-m-e-r-g-e-n-c-y_-m-a-n-a-g-e-r__-m-c-o-n-t-e-x-t/index.md)() |
| [CLIPBOARD_EX_MANAGER](-c-l-i-p-b-o-a-r-d_-e-x_-m-a-n-a-g-e-r/index.md) | [jvm]<br>[CLIPBOARD_EX_MANAGER](-c-l-i-p-b-o-a-r-d_-e-x_-m-a-n-a-g-e-r/index.md)() |
| [SEM_CLIPBOARD_MANAGER__MCONTEXT](-s-e-m_-c-l-i-p-b-o-a-r-d_-m-a-n-a-g-e-r__-m-c-o-n-t-e-x-t/index.md) | [jvm]<br>[SEM_CLIPBOARD_MANAGER__MCONTEXT](-s-e-m_-c-l-i-p-b-o-a-r-d_-m-a-n-a-g-e-r__-m-c-o-n-t-e-x-t/index.md)() |
| [CLIPBOARD_UI_MANAGER__SINSTANCE](-c-l-i-p-b-o-a-r-d_-u-i_-m-a-n-a-g-e-r__-s-i-n-s-t-a-n-c-e/index.md) | [jvm]<br>[CLIPBOARD_UI_MANAGER__SINSTANCE](-c-l-i-p-b-o-a-r-d_-u-i_-m-a-n-a-g-e-r__-s-i-n-s-t-a-n-c-e/index.md)() |
| [SPEN_GESTURE_MANAGER](-s-p-e-n_-g-e-s-t-u-r-e_-m-a-n-a-g-e-r/index.md) | [jvm]<br>[SPEN_GESTURE_MANAGER](-s-p-e-n_-g-e-s-t-u-r-e_-m-a-n-a-g-e-r/index.md)() |
| [COMPANION_DEVICE_SERVICE__STUB](-c-o-m-p-a-n-i-o-n_-d-e-v-i-c-e_-s-e-r-v-i-c-e__-s-t-u-b/index.md) | [jvm]<br>[COMPANION_DEVICE_SERVICE__STUB](-c-o-m-p-a-n-i-o-n_-d-e-v-i-c-e_-s-e-r-v-i-c-e__-s-t-u-b/index.md)() |
| [APPLICATION_PACKAGE_MANAGER__HAS_SYSTEM_FEATURE_QUERY](-a-p-p-l-i-c-a-t-i-o-n_-p-a-c-k-a-g-e_-m-a-n-a-g-e-r__-h-a-s_-s-y-s-t-e-m_-f-e-a-t-u-r-e_-q-u-e-r-y/index.md) | [jvm]<br>[APPLICATION_PACKAGE_MANAGER__HAS_SYSTEM_FEATURE_QUERY](-a-p-p-l-i-c-a-t-i-o-n_-p-a-c-k-a-g-e_-m-a-n-a-g-e-r__-h-a-s_-s-y-s-t-e-m_-f-e-a-t-u-r-e_-q-u-e-r-y/index.md)() |
| [TOAST_TN](-t-o-a-s-t_-t-n/index.md) | [jvm]<br>[TOAST_TN](-t-o-a-s-t_-t-n/index.md)() |
| [CONTROLLED_INPUT_CONNECTION_WRAPPER](-c-o-n-t-r-o-l-l-e-d_-i-n-p-u-t_-c-o-n-n-e-c-t-i-o-n_-w-r-a-p-p-e-r/index.md) | [jvm]<br>[CONTROLLED_INPUT_CONNECTION_WRAPPER](-c-o-n-t-r-o-l-l-e-d_-i-n-p-u-t_-c-o-n-n-e-c-t-i-o-n_-w-r-a-p-p-e-r/index.md)() |
| [TEXT_TO_SPEECH](-t-e-x-t_-t-o_-s-p-e-e-c-h/index.md) | [jvm]<br>[TEXT_TO_SPEECH](-t-e-x-t_-t-o_-s-p-e-e-c-h/index.md)() |
| [ACCESSIBILITY_NODE_ID_MANAGER](-a-c-c-e-s-s-i-b-i-l-i-t-y_-n-o-d-e_-i-d_-m-a-n-a-g-e-r/index.md) | [jvm]<br>[ACCESSIBILITY_NODE_ID_MANAGER](-a-c-c-e-s-s-i-b-i-l-i-t-y_-n-o-d-e_-i-d_-m-a-n-a-g-e-r/index.md)() |
| [VIEWLOCATIONHOLDER_ROOT](-v-i-e-w-l-o-c-a-t-i-o-n-h-o-l-d-e-r_-r-o-o-t/index.md) | [jvm]<br>[VIEWLOCATIONHOLDER_ROOT](-v-i-e-w-l-o-c-a-t-i-o-n-h-o-l-d-e-r_-r-o-o-t/index.md)() |
| [BACKDROP_FRAME_RENDERER__MDECORVIEW](-b-a-c-k-d-r-o-p_-f-r-a-m-e_-r-e-n-d-e-r-e-r__-m-d-e-c-o-r-v-i-e-w/index.md) | [jvm]<br>[BACKDROP_FRAME_RENDERER__MDECORVIEW](-b-a-c-k-d-r-o-p_-f-r-a-m-e_-r-e-n-d-e-r-e-r__-m-d-e-c-o-r-v-i-e-w/index.md)() |
| [MAGNIFIER](-m-a-g-n-i-f-i-e-r/index.md) | [jvm]<br>[MAGNIFIER](-m-a-g-n-i-f-i-e-r/index.md)() |
| [BIOMETRIC_PROMPT](-b-i-o-m-e-t-r-i-c_-p-r-o-m-p-t/index.md) | [jvm]<br>[BIOMETRIC_PROMPT](-b-i-o-m-e-t-r-i-c_-p-r-o-m-p-t/index.md)() |
| [ACCESSIBILITY_ITERATORS](-a-c-c-e-s-s-i-b-i-l-i-t-y_-i-t-e-r-a-t-o-r-s/index.md) | [jvm]<br>[ACCESSIBILITY_ITERATORS](-a-c-c-e-s-s-i-b-i-l-i-t-y_-i-t-e-r-a-t-o-r-s/index.md)() |
| [ASSIST_STRUCTURE](-a-s-s-i-s-t_-s-t-r-u-c-t-u-r-e/index.md) | [jvm]<br>[ASSIST_STRUCTURE](-a-s-s-i-s-t_-s-t-r-u-c-t-u-r-e/index.md)() |
| [ACCESSIBILITY_NODE_INFO__MORIGINALTEXT](-a-c-c-e-s-s-i-b-i-l-i-t-y_-n-o-d-e_-i-n-f-o__-m-o-r-i-g-i-n-a-l-t-e-x-t/index.md) | [jvm]<br>[ACCESSIBILITY_NODE_INFO__MORIGINALTEXT](-a-c-c-e-s-s-i-b-i-l-i-t-y_-n-o-d-e_-i-n-f-o__-m-o-r-i-g-i-n-a-l-t-e-x-t/index.md)() |
| [CONNECTIVITY_MANAGER__SINSTANCE](-c-o-n-n-e-c-t-i-v-i-t-y_-m-a-n-a-g-e-r__-s-i-n-s-t-a-n-c-e/index.md) | [jvm]<br>[CONNECTIVITY_MANAGER__SINSTANCE](-c-o-n-n-e-c-t-i-v-i-t-y_-m-a-n-a-g-e-r__-s-i-n-s-t-a-n-c-e/index.md)() |
| [EDITTEXT_BLINK_MESSAGEQUEUE](-e-d-i-t-t-e-x-t_-b-l-i-n-k_-m-e-s-s-a-g-e-q-u-e-u-e/index.md) | [jvm]<br>[EDITTEXT_BLINK_MESSAGEQUEUE](-e-d-i-t-t-e-x-t_-b-l-i-n-k_-m-e-s-s-a-g-e-q-u-e-u-e/index.md)() |
| [AUDIO_MANAGER](-a-u-d-i-o_-m-a-n-a-g-e-r/index.md) | [jvm]<br>[AUDIO_MANAGER](-a-u-d-i-o_-m-a-n-a-g-e-r/index.md)() |
| [APP_WIDGET_HOST_CALLBACKS](-a-p-p_-w-i-d-g-e-t_-h-o-s-t_-c-a-l-l-b-a-c-k-s/index.md) | [jvm]<br>[APP_WIDGET_HOST_CALLBACKS](-a-p-p_-w-i-d-g-e-t_-h-o-s-t_-c-a-l-l-b-a-c-k-s/index.md)() |
| [USER_MANAGER__SINSTANCE](-u-s-e-r_-m-a-n-a-g-e-r__-s-i-n-s-t-a-n-c-e/index.md) | [jvm]<br>[USER_MANAGER__SINSTANCE](-u-s-e-r_-m-a-n-a-g-e-r__-s-i-n-s-t-a-n-c-e/index.md)() |
| [MEDIA_SCANNER_CONNECTION](-m-e-d-i-a_-s-c-a-n-n-e-r_-c-o-n-n-e-c-t-i-o-n/index.md) | [jvm]<br>[MEDIA_SCANNER_CONNECTION](-m-e-d-i-a_-s-c-a-n-n-e-r_-c-o-n-n-e-c-t-i-o-n/index.md)() |
| [ACCOUNT_MANAGER](-a-c-c-o-u-n-t_-m-a-n-a-g-e-r/index.md) | [jvm]<br>[ACCOUNT_MANAGER](-a-c-c-o-u-n-t_-m-a-n-a-g-e-r/index.md)() |
| [SPEECH_RECOGNIZER](-s-p-e-e-c-h_-r-e-c-o-g-n-i-z-e-r/index.md) | [jvm]<br>[SPEECH_RECOGNIZER](-s-p-e-e-c-h_-r-e-c-o-g-n-i-z-e-r/index.md)() |
| [MEDIA_PROJECTION_CALLBACK](-m-e-d-i-a_-p-r-o-j-e-c-t-i-o-n_-c-a-l-l-b-a-c-k/index.md) | [jvm]<br>[MEDIA_PROJECTION_CALLBACK](-m-e-d-i-a_-p-r-o-j-e-c-t-i-o-n_-c-a-l-l-b-a-c-k/index.md)() |
| [ACTIVITY_CHOOSE_MODEL](-a-c-t-i-v-i-t-y_-c-h-o-o-s-e_-m-o-d-e-l/index.md) | [jvm]<br>[ACTIVITY_CHOOSE_MODEL](-a-c-t-i-v-i-t-y_-c-h-o-o-s-e_-m-o-d-e-l/index.md)() |
| [SPELL_CHECKER](-s-p-e-l-l_-c-h-e-c-k-e-r/index.md) | [jvm]<br>[SPELL_CHECKER](-s-p-e-l-l_-c-h-e-c-k-e-r/index.md)() |
| [SPELL_CHECKER_SESSION](-s-p-e-l-l_-c-h-e-c-k-e-r_-s-e-s-s-i-o-n/index.md) | [jvm]<br>[SPELL_CHECKER_SESSION](-s-p-e-l-l_-c-h-e-c-k-e-r_-s-e-s-s-i-o-n/index.md)() |
| [LAYOUT_TRANSITION](-l-a-y-o-u-t_-t-r-a-n-s-i-t-i-o-n/index.md) | [jvm]<br>[LAYOUT_TRANSITION](-l-a-y-o-u-t_-t-r-a-n-s-i-t-i-o-n/index.md)() |
| [INPUT_METHOD_MANAGER_IS_TERRIBLE](-i-n-p-u-t_-m-e-t-h-o-d_-m-a-n-a-g-e-r_-i-s_-t-e-r-r-i-b-l-e/index.md) | [jvm]<br>[INPUT_METHOD_MANAGER_IS_TERRIBLE](-i-n-p-u-t_-m-e-t-h-o-d_-m-a-n-a-g-e-r_-i-s_-t-e-r-r-i-b-l-e/index.md)() |
| [BLOCKING_QUEUE](-b-l-o-c-k-i-n-g_-q-u-e-u-e/index.md) | [jvm]<br>[BLOCKING_QUEUE](-b-l-o-c-k-i-n-g_-q-u-e-u-e/index.md)() |
| [TEXT_LINE__SCACHED](-t-e-x-t_-l-i-n-e__-s-c-a-c-h-e-d/index.md) | [jvm]<br>[TEXT_LINE__SCACHED](-t-e-x-t_-l-i-n-e__-s-c-a-c-h-e-d/index.md)() |
| [MEDIA_SESSION_LEGACY_HELPER__SINSTANCE](-m-e-d-i-a_-s-e-s-s-i-o-n_-l-e-g-a-c-y_-h-e-l-p-e-r__-s-i-n-s-t-a-n-c-e/index.md) | [jvm]<br>[MEDIA_SESSION_LEGACY_HELPER__SINSTANCE](-m-e-d-i-a_-s-e-s-s-i-o-n_-l-e-g-a-c-y_-h-e-l-p-e-r__-s-i-n-s-t-a-n-c-e/index.md)() |
| [SPAN_CONTROLLER](-s-p-a-n_-c-o-n-t-r-o-l-l-e-r/index.md) | [jvm]<br>[SPAN_CONTROLLER](-s-p-a-n_-c-o-n-t-r-o-l-l-e-r/index.md)() |
| [ACTIVITY_CLIENT_RECORD__NEXT_IDLE](-a-c-t-i-v-i-t-y_-c-l-i-e-n-t_-r-e-c-o-r-d__-n-e-x-t_-i-d-l-e/index.md) | [jvm]<br>[ACTIVITY_CLIENT_RECORD__NEXT_IDLE](-a-c-t-i-v-i-t-y_-c-l-i-e-n-t_-r-e-c-o-r-d__-n-e-x-t_-i-d-l-e/index.md)() |
| [IREQUEST_FINISH_CALLBACK](-i-r-e-q-u-e-s-t_-f-i-n-i-s-h_-c-a-l-l-b-a-c-k/index.md) | [jvm]<br>[IREQUEST_FINISH_CALLBACK](-i-r-e-q-u-e-s-t_-f-i-n-i-s-h_-c-a-l-l-b-a-c-k/index.md)() |

## Types

| Name | Summary |
|---|---|
| [Companion](-companion/index.md) | [jvm]<br>object [Companion](-companion/index.md) |

## Properties

| Name | Summary |
|---|---|
| [name](-i-r-e-q-u-e-s-t_-f-i-n-i-s-h_-c-a-l-l-b-a-c-k/index.md#-372974862%2FProperties%2F980726859) | [jvm]<br>val [name](-i-r-e-q-u-e-s-t_-f-i-n-i-s-h_-c-a-l-l-b-a-c-k/index.md#-372974862%2FProperties%2F980726859): [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html) |
| [ordinal](-i-r-e-q-u-e-s-t_-f-i-n-i-s-h_-c-a-l-l-b-a-c-k/index.md#-739389684%2FProperties%2F980726859) | [jvm]<br>val [ordinal](-i-r-e-q-u-e-s-t_-f-i-n-i-s-h_-c-a-l-l-b-a-c-k/index.md#-739389684%2FProperties%2F980726859): [Int](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html) |
