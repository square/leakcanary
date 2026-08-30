## The leak fingerprint

A hash of how a leak is held, the same for the same leak in the next heap dump of this app.

Unlike the addresses under it, which are of this dump and of nothing else: two dumps of one app taken a
minute apart name the same leaking screen by two different addresses. The fingerprint does not move.

So it is what to write in a bug report, and what to compare two dumps by. A leak that is still there
after a fix has the same fingerprint; a leak that is gone takes its fingerprint with it.

It is also the leak fingerprint LeakCanary prints under a leak when it reports one, so a report and this
list can be lined up hash by hash.
