[shark-graph](../../index.md) / [shark](../index.md) / [HprofHeapGraph](index.md) / [INTERNAL_LRU_CACHE_SIZE](./-i-n-t-e-r-n-a-l_-l-r-u_-c-a-c-h-e_-s-i-z-e.md)

# INTERNAL_LRU_CACHE_SIZE

`var INTERNAL_LRU_CACHE_SIZE: `[`Int`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-int/index.html)

This is not a public API, it's only public so that we can evaluate the effectiveness of
different cache size in tests in a different module.

LRU cache size of 3000 is a sweet spot to balance hits vs memory usage.
This is based on running InstrumentationLeakDetectorTest a bunch of time on a
Pixel 2 XL API 28. Hit count was ~120K, miss count ~290K

