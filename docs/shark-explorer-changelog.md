# Shark Explorer Change Log

[Shark Explorer](shark-explorer.md) is a desktop app released on its own schedule, so it has its own
change log. The [LeakCanary change log](changelog.md) covers the libraries and never mentions this app.
See [Releasing Shark Explorer](releasing-shark-explorer.md) for how a version gets cut.

Each entry starts with a marker for the kind of change it is, the same markers the LeakCanary change log
uses, without the one for a newly recognized library leak:

| | Means |
| --- | --- |
| ⚠️ | **Breaking change**: something you relied on works differently or is gone. |
| 🔀 | **Behavior change**: the app now does something different. |
| 💥 | **Crash fix**: something used to crash, and no longer does. |
| 🐛 | **Bug fix**: the app did the wrong thing, without crashing. |
| ✨ | **New**: a capability that didn't exist before. |
| 🔨 | **Improvement**: something that already worked, now works better. |

## Unreleased

* ✨ Initial release.
* ✨ Right click anything the window can take you to — a tab, a rectangle, a row, a field — and copy a
  `shark://` link to it, beside opening it in a new tab. Clicking one brings the app to the front and
  opens that place in a new tab: an object, a filtered object list, the leaks with the same groups
  unfolded. See [Link to a tab](shark-explorer.md#link-to-a-tab).
* ✨ **Notes**: every location takes a markdown note, kept between runs, and the tab strip marks the tabs
  whose location has one. A note belongs to the location rather than to the tab, so two tabs on one
  location are one note. Class names, addresses and `shark://` links written in a note become links back
  into the window, shortened to read as prose, and GitHub URLs are shortened the way GitHub shortens them.
  See [Take notes](shark-explorer.md#take-notes).
* ✨ Right click ← or → for the list of everywhere that arrow leads, so going back four moves is one
  click rather than four.
* ✨ **Leaking status**: whether the object a tab is on should still be in memory is said under its name, in
  the same colours a chain uses, and you can overrule it — a reason is required, kept with it, and what you
  overruled is recorded beside it. Where a status you set contradicts another one, every status it disagrees
  with is listed with the reason it was given, and keeping yours flips them. Kept between runs in
  `~/.shark-explorer/leak-statuses`, one file per heap dump.
  See [Say what is leaking](shark-explorer.md#say-what-is-leaking).
