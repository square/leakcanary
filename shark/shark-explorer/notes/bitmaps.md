# Bitmaps: where their pixels are, and how to get the ones a heap dump doesn't have

Implemented in `shark-explorer-core`: `HeapBitmaps.kt` (finding bitmaps, decoding whatever pixels the
dump has), `BitmapImage.kt` (what a decode produces), `HeapDumpOrigin.kt` (which device and process wrote
the dump), `Adb.kt` and `DeviceHeapDumps.kt` (taking a dump off a device, and going back to the process
one came from). Drawn by `BitmapImages.kt`, `TreemapView` and `DetailsPanel`, and asked for by
`TakeHeapDumpDialog` and `BitmapsFromDeviceDialog`, in `shark-explorer-app`.

**Taking the dump here is the way to get the pixels.** `DeviceHeapDumps.dumpHeap` passes `-b png`
whenever the device is API 35 or up, so a dump taken through the window arrives with its bitmaps in it
and nothing has to be fetched afterwards. Fetching — the same dump taken again of the process an
already-open dump came from, kept only for its images — is for a dump that came from somewhere else.

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

Every heap dump in this repo is old enough to carry its pixels, so **the code path that matters most is
the one none of the committed dumps exercise.** Write a synthetic dump for it — `BitmapDumps.kt` in
`shark-explorer-core`'s tests builds a `Bitmap` class with or without `dumpData` — or take a fresh dump
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

## API 26 to 34 has no answer, and that isn't for lack of looking

`am dumpheap` on those versions takes no `-b`, and there is no other supported way to ask a process to
compress its bitmaps. Perfetto's Heap Dump Explorer, the only other tool that shows these pixels, needs
the same flag and renders no images without it, so nothing is being missed here.

What would work, neither of it worth building yet:

- **An agent inside the process.** A JVMTI agent, or a debugger invoking `Bitmap.compress` over JDWP,
  can do on API 26 what `dumpAll` does on 35 — but only in a debuggable app, and only through a lot of
  machinery.
- **LeakCanary itself.** It already runs inside the app and already takes the heap dump. It could
  compress the bitmaps it can reach and write them next to the dump, on any API level. That's a
  LeakCanary feature rather than an explorer one, which is why the explorer takes
  `NativeBitmapPixels` from a source it doesn't name: a file LeakCanary wrote would arrive the same way
  a second `dumpheap` does.

Until then, the window says which of the two it is: a dump with no pixels and API 35 or up offers the
fetch, and a fetch against an older device fails with the version in the message rather than with
nothing.

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

## `adb` facts that cost time to find out

Measured on two emulators, an API 36 and an API 29, dumping the heap of `leakcanary-android-sample`: 45 MB
with 4 bitmaps and the pixels of all 4 on API 36, 17 MB with 1 bitmap and no pixels at all on API 29. Both
took under a second to write.

- **Only a debuggable process can be dumped.** `am dumpheap` of anything else answers
  `java.lang.SecurityException: Process not debuggable: <package>` — that's an emulator refusing to dump
  its own launcher, so a release build on a real phone has no chance. It's the first thing that goes wrong
  for anyone using this, which is why the message says so in words.
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
