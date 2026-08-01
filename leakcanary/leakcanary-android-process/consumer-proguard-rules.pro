# This module is what puts WorkManager on the app's classpath: it depends on
# androidx.work:work-multiprocess, and an app that doesn't already use WorkManager gets it only
# because LeakCanary asked for it. So the keep rules that makes necessary are LeakCanary's to ship,
# not something the app should have to find out about from a crash.
#
# Both classes below are instantiated reflectively through their no argument constructor, and the
# rules that are supposed to protect them keep the class without keeping that constructor. R8 used
# to treat those as the same thing and stopped in full mode, the default since AGP 8. So in a
# minified build the constructor is gone and:
#
#   WorkDatabase_Impl        The Room implementation of WorkManager's database, generated at build
#                            time from androidx.work.impl.WorkDatabase. Room keeps it with
#                            `-keep class * extends androidx.room.RoomDatabase`, and without the
#                            constructor Room.getGeneratedImplementation() throws a
#                            NoSuchMethodException the moment androidx.startup initializes
#                            WorkManager, which happens while the app is starting: the app dies on
#                            launch. Room still ships that rule as of 2.6.1.
#
#   OverwritingInputMerger   WorkManager's default input merger, kept with
#                            `-keep class * extends androidx.work.InputMerger`. Without the
#                            constructor every worker fails to start with an InstantiationException,
#                            so the heap analysis never runs. WorkManager fixed its own rule in
#                            2.10.0, but this module depends on the oldest WorkManager LeakCanary
#                            supports, so that an app resolves to whichever version it already uses.
#
# These name the two classes rather than every subclass of RoomDatabase and InputMerger, so that an
# app's own Room databases and input mergers are left to whatever the app already does about them.
-keep class androidx.work.impl.WorkDatabase_Impl { <init>(); }
-keep class androidx.work.OverwritingInputMerger { <init>(); }
