## Shading what is stuck

Shades the objects that are stuck in memory and everything they hold with them, greying the rest of the
map.

Anything a stuck object dominates is only still there because it is, so shading the stuck objects without
what they retain would colour the smallest part of the problem.

**There is no colour for the objects that are expected.** A treemap draws what retains what, and most of a
heap dump is objects nothing knows either way about — a colour for the expected ones would be a colour
claiming a verdict nobody has given. The chain beside the map says which is which, object by object.

Ticking this is also what sends Shark Dive looking for the leaks, which is a pass over the whole heap
dump, so the row says so while that runs.
