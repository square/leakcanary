//[leakcanary-android-core](../../../index.md)/[leakcanary](../index.md)/[LeakCanary](index.md)/[showLeakDisplayActivityLauncherIcon](show-leak-display-activity-launcher-icon.md)

# showLeakDisplayActivityLauncherIcon

[androidJvm]\
fun [showLeakDisplayActivityLauncherIcon](show-leak-display-activity-launcher-icon.md)(showLauncherIcon: [Boolean](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html))

Dynamically shows / hides the launcher icon for the leak display activity. Note: you can change the default value by overriding the leak_canary_add_launcher_icon boolean resource:

&lt;?xml version="1.0" encoding="utf-8"?&gt;\
&lt;resources&gt;\
  &lt;bool name="leak_canary_add_launcher_icon"&gt;false&lt;/bool&gt;\
&lt;/resources&gt;
