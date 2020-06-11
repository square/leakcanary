[leakcanary-android-core](../../index.md) / [leakcanary](../index.md) / [LeakCanary](index.md) / [showLeakDisplayActivityLauncherIcon](./show-leak-display-activity-launcher-icon.md)

# showLeakDisplayActivityLauncherIcon

`fun showLeakDisplayActivityLauncherIcon(showLauncherIcon: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)`): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)

Dynamically shows / hides the launcher icon for the leak display activity.
Note: you can change the default value by overriding the `leak_canary_add_launcher_icon`
boolean resource:

```
<?xml version="1.0" encoding="utf-8"?>
<resources>
  <bool name="leak_canary_add_launcher_icon">false</bool>
</resources>
```

