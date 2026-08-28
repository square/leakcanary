# Bitmaps: where their pixels are, and how to get the ones a heap dump doesn't have

Implemented in `shark-dive-core`: `HeapBitmaps.kt` (finding bitmaps, decoding whatever pixels the
dump has), `BitmapImage.kt` (what a decode produces), `HeapDumpOrigin.kt` (which device and process wrote
the dump), `Adb.kt` and `DeviceHeapDumps.kt` (taking a dump off a device, and going back to the process
one came from). `JdwpBitmaps.kt` in `shark-dive-jdwp` is the other way back to a process, for the
devices that can't answer through a heap dump. Drawn by `BitmapImages.kt`, `TreemapView` and
`DetailsPanel`, and asked for by `TakeHeapDumpDialog` and `BitmapsFromDeviceDialog`, in
`shark-dive-app`.

**Taking the dump here is the cheapest way to get the pixels.** `DeviceHeapDumps.dumpHeap` passes `-b png`
whenever the device is API 35 or up, so a dump taken through the window arrives with its bitmaps in it and
nothing has to be fetched afterwards. Fetching is for a dump that came from somewhere else, and it is two
things depending on the device: another dump of the same process, kept only for its images, on API 35 and
up; a debugger that makes the process compress them, below that. `DeviceHeapDumps.fetchBitmaps` picks.

Below API 35, `TakeHeapDumpDialog` offers the fetch as a checkbox next to the process, so a dump and the
debugger run in one go — the process is still there and still holds the pixels, which is the one moment
that's guaranteed. It's off by default and says what it costs, since the app is suspended for it. A fetch
that fails then doesn't fail the dump: the file is already pulled, the window still offers the fetch, and
that is where the reason shows up.

## Three eras, and only two of them have pixels in the dump

| Android | Where a bitmap's pixels are | What a heap dump has |
| --- | --- | --- |
| Before API 26 | `Bitmap.mBuffer`, a `byte[]` on the Java heap | every pixel of every bitmap |
| API 26–34 | native memory, `mNativePtr` | the width, the height and an address |
| API 35 and up | native memory | the same, **plus** a PNG per bitmap if dumped with `am dumpheap -b png` |

API 26 is where `Bitmap` moved its pixels to native memory (the "Bitmap is now backed by native memory"
change of Android 8), and `am dumpheap -b <format>` is where Android 15 gave them back: it makes
`ActivityThread.handleDumpHeap` call `Bitmap.dumpAll(format)` before `Debug.dumpHprofData`, which fills
in a static `Bitmap.dumpData` — a `Bitmap$DumpData` with `count`, `format`, `natives: long[]` and
`buffers: byte[][]` — and clears it again afterwards. So the compressed images are in the dump as
ordinary `byte[]`s, and `natives[i]` says which bitmap `buffers[i]` shows.

Measured, to save the next agent the surprise that the middle row is the common case rather than an edge
case:

| Dump | API | Bitmaps | Have pixels |
| --- | --- | --- | --- |
| `compose_leak.hprof` | 23 | 360 | 360, from `mBuffer` |
| `hashmap_api_25.hprof` | 25 | 93 | 93, from `mBuffer` |
| `large-dump.hprof` | 25 | 151 | 151, from `mBuffer` |
| a Pixel 9, `am dumpheap -b png` | 36 | 4 | 4, from `dumpData`, 0 pointer mismatches |
| an emulator, `am dumpheap` then a debugger | 29 | 6 | 0 in the dump, 6 after the fetch, 0 mismatches |

Every heap dump in this repo is old enough to carry its pixels, so **the code path that matters most is
the one none of the committed dumps exercise.** Write a synthetic dump for it — `BitmapDumps.kt` in
`shark-dive-core`'s tests builds a `Bitmap` class with or without `dumpData` — or take a fresh dump
off a device.

## The native pointer is the join key, and it is reusable

Both sources key an image by the native pointer of the bitmap it belongs to, so a dump taken later of the
same process joins back onto the dump being explored by pointer. What makes that safe enough to show:
**an image is only accepted for a bitmap it is the size of.** A pointer is the address of a native
allocation, and once a bitmap is recycled another one can be allocated at the same address — the pixels
would then be of a real bitmap of that process, just not the one on screen. A PNG's `IHDR` gives its
size in 24 bytes, which is why PNG is what `-b` is asked for. Rejections are counted
(`BitmapCounts.mismatchedCount`) and said out loud, because otherwise a fetch that matched nothing looks
exactly like a fetch that silently did nothing.

## API 26 to 34: the app compresses them for a debugger

No heap dump of those versions can carry a pixel, whatever it's asked for — `am dumpheap` takes no `-b`
and Perfetto's Heap Dump Dive, the only other tool that shows these pixels, renders none without it.
But the pixels are in the process, and so is `Bitmap.compress`, so **the process can be made to compress
its own bitmaps**: `JdwpBitmaps` attaches over JDWP, lists every live `Bitmap` with
`ReferenceType.instances`, and invokes `compress(PNG, 100, ByteArrayOutputStream())` on each. Same
pointer-keyed images as `dumpAll` produces, so they join onto a dump exactly the same way.

`com.sun.jdi` is a JDK module, so this needs nothing built for a device: no JVMTI agent, no NDK, no
per-ABI `.so`, nothing pushed. It does need **a debuggable app**, which is what opens a JDWP connection —
the same condition `am dumpheap` already has — and no other debugger attached, so an app being debugged in
Android Studio is taken.

What it cost when measured, on an API 29 emulator dumping `leakcanary-android-sample`:

- 5.4 seconds for the whole fetch of 6 bitmaps, nearly all of it fixed cost: `adb forward`, the attach,
  and the wait for the app to run something. Dumping and fetching in one go costs the two added up and
  nothing more — 2.3 s for a 17 MB dump, then 5.5 s to attach and read the bitmaps of the same process.
- 461 ms to compress a 1080×2400 `Config.HARDWARE` bitmap, 85 ms for a 64×64 one.
- 144 ms to read 2 MB back over JDWP, so the transfer is not what any of this costs.

Things that took a while to find out, and that the code depends on:

- **`Config.HARDWARE` bitmaps come back too**, which matters because that is what modern image loading
  produces and what `getPixels` refuses. `compress` has no HARDWARE guard: it reads them back off the GPU
  through the render thread. Which is why every invoke passes no `INVOKE_SINGLE_THREADED` — freeze the
  render thread and the readback never finishes.
- **Suspending the VM is not enough to invoke anything.** ART answers `IncompatibleThreadStateException`
  for a thread stopped by `VirtualMachine.suspend()`; what it takes is a thread stopped by an *event*. So
  the code asks for one method entry anywhere in the app, with a count filter of 1 so exactly one fires
  and nothing stays instrumented afterwards.
- **An idle or backgrounded app runs nothing**, so there is no event until it is nudged. `dumpsys meminfo
  <pid>` is the nudge: the framework answers it by calling into the app over binder, so the app runs code
  whether or not it's on screen — the safe point lands on a `Binder:<pid>_N` thread — and unlike a
  synthetic keyevent it changes nothing about what the app is showing.
- `Bitmap.CompressFormat` is only loaded by an app that has compressed something, which an app whose
  bitmaps are being fetched hasn't. Most builds have it in the boot image; `loadedClass` invokes
  `Class.forName` for the ones that don't.

What is deliberately not built:

- **`getPixels` instead of `compress`.** It is the faster call inside the app — measured over JDWP on the
  same API 29 emulator, 44 ms against 505 ms for a 1080×2400 `ARGB_8888` bitmap — and it is still the wrong
  one. It **refuses `Config.HARDWARE`** (`IllegalStateException: unable to getPixels(), pixel access is not
  supported on Config#HARDWARE bitmaps`), which is the config that matters most; it moves 10,368,000 bytes
  where `compress` moved 15,584, so the transfer goes from 6 ms to 221 ms and gets worse with every pixel;
  it needs an `int[]` the size of the bitmap allocated *inside the app being debugged*; JDI hands the array
  back as boxed `IntegerValue`s, so Shark Dive needs gigabytes of heap to receive one screen's worth; and
  the raw pixels carry no size, where a PNG's `IHDR` is what the pointer-reuse check reads. The 505 ms is a
  best case for `compress` too — that bitmap was a uniform fill, which deflates unusually fast.
- **A JVMTI/ART TI agent** does the same enumeration, but as an NDK-built `.so` per ABI that has to be
  inside the app's own data directory before `am attach-agent` will load it. Same result, a native build
  and artifacts versioned against ART internals to get there.
- **Reading the pixels out of `/proc/<pid>/mem`** means chasing `mNativePtr` → `BitmapWrapper` →
  `android::Bitmap` → `SkPixelRef::fPixels` through layouts that change between releases and ABIs, and a
  HARDWARE bitmap's pixels sit in a `GraphicBuffer` the CPU may not be able to map at all.
- **LeakCanary itself.** It runs inside the app and already takes the heap dump, so it could compress the
  bitmaps it can reach and write them next to the dump — on any API level, and in an app that isn't
  debuggable, which is the one thing none of the above can do. That's a LeakCanary feature rather than a
  Shark Dive one, which is why Shark Dive takes `NativeBitmapPixels` from a source it doesn't name: a file
  LeakCanary wrote would arrive the same way either of these does.

## Decoding `mBuffer` without knowing the `Bitmap.Config`

The config is native even before API 26, so a pre-26 dump says how many bytes the pixels took and not
what they mean. `mBuffer.byteSize / (mWidth * mHeight)` is the one thing the layout depends on, so that's
what the decode goes by: 1 byte is `ALPHA_8`, 2 is `RGB_565`, 4 is `ARGB_8888`. Three things this gets
wrong or gives up on, in decreasing order of how much they matter:

- A buffer can be **bigger than the bitmap needs**, because `reconfigure()` keeps the allocation of a
  larger bitmap. The division rounds down, which is right — the pixels are at the front — but it means a
  buffer is never evidence of a config on its own.
- `ARGB_8888` is stored **RGBA**, in memory order, and **premultiplied** (`mRequestPremultiplied`, or
  `mIsPremultiplied` on older versions). Undoing the premultiplication is part of reading a pixel: skip
  it and everything translucent comes out too dark.
- `RGBA_F16` and `RGBA_1010102` are 8 and 4 bytes a pixel and aren't decoded. 4 bytes is indistinguishable
  from `ARGB_8888` here, so a `RGBA_1010102` bitmap of a pre-26 dump would decode to wrong colours. No
  device before API 26 produced one — the config didn't exist yet — so this is only a hazard if the
  byte-count inference is ever reused for something newer.

`ALPHA_8` is a mask with no colour at all; it's drawn as the black it stands in for, which is what makes a
nine-patch shadow look like a shadow rather than like nothing.

**There is no config int in the dump to go by instead**, which is worth knowing because it looks like there
should be. `Bitmap.Config` does carry one — `final int nativeInt`, the native colour type: `ALPHA_8` 1,
`RGB_565` 3, `ARGB_4444` 4, `ARGB_8888` 5, `RGBA_F16` 6, `HARDWARE` 7, `RGBA_1010102` 8, so a type id and
not a byte count — and the enum constants sit in every dump with those values readable. What no dump has is
anything pointing from a bitmap to one of them: `getConfig()` is
`Config.nativeToConfig(nativeConfig(mNativePtr))`, answered out of native memory. Checked against five real
dumps, from API 23, 25, 29 and 36: a `Bitmap` instance has `mWidth`, `mHeight`, `mRecycled`, `mDensity`,
`mNativePtr`, `mRequestPremultiplied`, `mNinePatchChunk` and `mNinePatchInsets`, plus `mBuffer` and
`mIsMutable` before API 26, `mColorSpace` from 26, and `mGainmap`, `mHardwareBuffer` and `mId` by 36. No
config in any era, so bytes per pixel is the only evidence there is.

The one collision that leaves is 2 bytes, which is `RGB_565` and also `ARGB_4444` — and `ARGB_4444` has
been unreachable since KitKat, where `Bitmap` started creating an `ARGB_8888` for anything that asks for
it. So a 2-byte buffer is `RGB_565` in any dump from API 19 on, which is every dump this is likely to meet.

## `adb` facts that cost time to find out

Measured on two emulators, an API 36 and an API 29, dumping the heap of `leakcanary-android-sample`: 45 MB
with 4 bitmaps and the pixels of all 4 on API 36, 17 MB with 1 bitmap and no pixels at all on API 29. Both
took under a second to write.

- **Only a debuggable process can be dumped**, or debugged. `am dumpheap` of anything else answers
  `java.lang.SecurityException: Process not debuggable: <package>` — that's an emulator refusing to dump
  its own launcher, so a release build on a real phone has no chance. It's the first thing that goes wrong
  for anyone using this, which is why the message says so in words. Note that `adb forward tcp:0
  jdwp:<pid>` sets up a forward for *any* pid just as happily; nothing says no until something connects
  and finds nobody there, which is why that failure is worded rather than passed on.
- **`adb forward tcp:0 <remote>` prints the port it picked**, which is how a JDWP forward gets a local port
  without picking one and racing whatever else on the machine opens sockets.
- **A refusal comes back in one of two shapes**, and `AdbOutput.orFail` looks for both: `Error: Unknown
  option: -b` (what API 29 says about `-b`), or `Exception occurred while executing 'dumpheap':` followed
  by an exception and twelve framework frames. `adb shell` does propagate the remote exit code (255 for
  both of those), but not always — an `Error:` with an exit code of 0 is a real combination — and the
  stack trace is worth reducing to its first line before it reaches a window.
- **`am dumpheap` sometimes waits for the write and sometimes doesn't.** API 36 prints "Waiting for dump
  to finish" and blocks; older versions return early, and pulling then pulls a partial file. `stat -c %s`
  until the size stops changing covers both, and costs one extra `stat` and one sleep where the wait
  already happened.
- **The dump in `/data/local/tmp` belongs to `shell`, not to the app.** `am` opens the file itself and
  passes the descriptor to the process, so `adb pull` can read it. An app-created file there couldn't be.
- **A process name doesn't say whether it's an app.** `media.extractor` and
  `android.hardware.audio.service` read exactly like packages, and neither has a Java heap. `pm list
  packages` is what separates them — one extra call per device, and worth it: it takes the API 36
  emulator's process list from 44 "apps" to 35 real ones.
- **Don't `adb shell` an unauthorized device**: it blocks until someone taps the dialog. `connectedDevices`
  only asks `getprop` of a device whose state is `device`.
- **A desktop app can't rely on the `PATH`.** Launched from a dock or a launcher it inherits almost none,
  so `CommandLineAdb` looks `adb` up under `ANDROID_HOME`, `ANDROID_SDK_ROOT` and the two places the SDK
  installs itself before falling back to the bare name.
- A heap dump records `Build.FINGERPRINT`, which is one build of one model rather than one device — two
  identical phones on the same build are indistinguishable in a dump. That's why the dialog ranks devices
  and lets someone pick, rather than deciding.
