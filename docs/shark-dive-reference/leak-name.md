## What names a leak

The references a leak *is*: the first should have been cleared, and the last points straight at what is
stuck.

They are one reference for most leaks, and the row on the leaks screen is that reference's name.

**The first is the faulty reference** — the one that should have been cleared, and what LeakCanary calls
the leak. Everything above it on the chain is the app working as intended: a screen held by the thing
that is supposed to hold it is not a leak, however much it retains.

**The last points at what is stuck**, which is where to look on the chain to see it. Everything below it
is what the leak is *holding*, and none of that is part of what makes this leak this leak — it is what
the leak costs.

So a name of two references is a leak whose faulty reference is not the one pointing at the stuck object.
There are steps in between, and every one of them is stuck because the first was not cleared.

For an object on its way out, the name is the one reference the collector has not cleared yet, which is
the whole of why it is still here.

Where a leak is a single reference, the chain drawn for an object under it marks that step, so the row
and the step are one thing said twice.

See also [How LeakCanary works](https://square.github.io/leakcanary/fundamentals-how-leakcanary-works/)
and [Fixing a memory leak](https://square.github.io/leakcanary/fundamentals-fixing-a-memory-leak/).
