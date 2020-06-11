[leakcanary-object-watcher-android](../../index.md) / [leakcanary](../index.md) / [AppWatcher](index.md) / [manualInstall](./manual-install.md)

# manualInstall

`fun manualInstall(application: Application): `[`Unit`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-unit/index.html)

[AppWatcher](index.md) is automatically installed in the main process on startup. You can
disable this behavior by overriding the `leak_canary_watcher_auto_install` boolean resource:

```
<?xml version="1.0" encoding="utf-8"?>
<resources>
  <bool name="leak_canary_watcher_auto_install">false</bool>
</resources>
```

If you disabled automatic install then you can call this method to install [AppWatcher](index.md).

