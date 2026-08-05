# Shark Explorer Change Log

Shark Explorer is a desktop app released on its own schedule, so it has its own change log. The
[LeakCanary change log](changelog.md) covers the libraries and never mentions this app. See
[Releasing Shark Explorer](releasing-shark-explorer.md) for how a version gets cut.

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
