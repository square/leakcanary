## Verdicts that disagree

Everything a stuck object holds is stuck too, so a verdict set by hand can contradict one set earlier.

It runs both ways. Anything a stuck object dominates is only still in memory because that object is, so it
is stuck as well; and anything holding an object that belongs in memory belongs there too, since something
has to be keeping it. `Stuck` above and `Expected` below is a pair of verdicts that cannot both be right,
so before writing anything Shark Dive walks the references and works out which of the verdicts already set
by hand the new one runs into.

**Every one of them is listed rather than counted**, with the reason it was given, because that reason is
the case for the other reading. Whoever is about to overrule it is the only person who can weigh the two,
and they can only do that if they can read what they are overruling. Each is a link to the object it is
about, and going to look at it opens a tab of its own: the verdict you were setting stays where it was,
half set, in the tab you left.

Keeping this one flips the verdicts that disagreed to the opposite verdict, with what they said kept as
part of the new reason — flipped rather than deleted, so the sentence somebody wrote is still in the file
after somebody else has disagreed with it.

Undoing writes nothing at all: nothing reaches the disk until the last question is answered.
