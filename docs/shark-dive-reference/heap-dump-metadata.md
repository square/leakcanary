## Metadata

What the heap dump says about itself, in LeakCanary's words: the device, the app's process, what the heap
holds, its bitmaps and its open databases.

This is LeakCanary's own `AndroidMetadataExtractor`, the one that fills the block printed above a leak
trace, run here rather than written again. So **the keys and the values are its**, spelled and counted
the way a `leaks.txt` spells and counts them — `Build.VERSION.SDK_INT`, `Heap total bytes`, `Db 1` — and
a figure read here is the same figure as the one in the report somebody was sent.

Which is why the numbers are raw. `Heap total bytes` is bytes, not megabytes, because the moment it is
rounded for the screen it stops matching the line it is supposed to match.

A few of them are worth knowing the shape of:

- **`Heap total bytes` is the sum of every object's own size**, so it is what the objects in the dump
  weigh rather than how much memory the process had. A class counts as the size of its record.
- **`Bitmap total bytes` is native memory**, counted off the registry that frees it, so it is outside the
  heap the line above measures and the two don't add up to anything.
- **Large bitmaps are the ones bigger than the screen**, by 10% or more of the largest
  `DisplayMetrics` in the dump. A dump with no `DisplayMetrics` has none by that test, whatever its
  bitmaps are.
- **`LeakCanary version` is `Unknown` for a dump LeakCanary didn't write**, which includes every dump
  taken with `am dumpheap` or from this app's **Take heap dump…**.
- **`Db 1`, `Db 2` are the SQLite databases the app has open**, in the order they were found, each
  labelled `open` or `closed`.

**The app's own version number is in no heap dump.** Nothing on this screen says which build of the app
this is, so a chain read against the wrong source is a mistake no part of the dump will catch — ask
whoever gave you the file.

**And none of this is here for a dump that isn't Android's.** Every line of it is read off the Android
framework, starting with `android.os.Build`, so a JVM heap dump has an empty screen rather than a short
one.
