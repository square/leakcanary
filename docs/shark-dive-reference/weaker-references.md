## Reachable only through a reference

A referent nothing else holds is usually collected before a heap dump is written, so nothing softly,
weakly or phantom reachable is normal.

The garbage collection that runs before a dump clears the references whose referent nothing else was
holding, which is most of them. That is why those rows of the legend read 0 B in most heap dumps, and it
is not a bug.

It is not a given either. A referent a thread got out of a reference and has since let go of is weakly
reachable again until the next collection, and a soft reference is only cleared under memory pressure. So
zero is common rather than certain, and a dump with something at those strengths is not odd.

**Unreachable is a different thing again**: objects nothing points at at all, which that collection did
not get to. They are in the dump because a heap dump is what was in memory, not what was live.
