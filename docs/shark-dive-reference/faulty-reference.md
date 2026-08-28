## The faulty reference

The one reference that should have been cleared: everything under it is still in memory only because of
it.

What it is read on is expected to be in memory. What it points at should have been gone. So this is the
one reference to clear, and clearing it is what would let everything under it go.

That is what makes it the leak rather than a symptom of one. Every object below it on the chain is
retained by it, so a fix here is a fix for all of them — and a fix anywhere below it is a fix for none.

Shark Dive marks it on the chain where it sits, and names it above the chain as well, in the same words
the leaks screen names the leak with. A real chain is tens of steps and the pane is scrolled to the
bottom of it, so the answer is said where the eye starts too.
