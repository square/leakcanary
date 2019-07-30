[leakcanary-android-core](../../../index.md) / [leakcanary](../../index.md) / [LeakCanary](../index.md) / [Config](index.md) / [requestWriteExternalStoragePermission](./request-write-external-storage-permission.md)

# requestWriteExternalStoragePermission

`val requestWriteExternalStoragePermission: `[`Boolean`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-boolean/index.html)

LeakCanary always attempts to store heap dumps on the external storage if the
WRITE_EXTERNAL_STORAGE is already granted, and otherwise uses the app storage.
If the WRITE_EXTERNAL_STORAGE permission is not granted and
[requestWriteExternalStoragePermission](./request-write-external-storage-permission.md) is true, then LeakCanary will display a notification
to ask for that permission.

Defaults to false because that permission notification can be annoying.

