# Dependency injection singletons

What a heap dump of an app using Dagger or Metro actually contains, measured by generating both with
real code generation and dumping the result. This is what the owner rule in `OwnerReferences` was
written from, and what to re-measure against when either framework moves.

Measured against **Dagger 2.60.1** and **Metro 1.4.0**.

## Nothing in a heap dump says which object is a component

Which is the finding that shaped the rule. The tempting design — a virtual reference from a component
to each of its singletons, the way `ViewChildReferenceReader` gives a `ViewGroup` one per child —
needs to be able to point at the component, and neither framework leaves a mark to point at.

| | Generated component | Generated child |
| --- | --- | --- |
| Dagger | `DaggerProbeDaggerComponent$ProbeDaggerComponentImpl` | `DaggerProbeDaggerComponent$ProbeDaggerSubcomponentImpl` |
| Metro | `ProbeMetroGraph$Impl` | `ProbeMetroGraph$Impl$ProbeMetroGraphExtensionImpl` |

Dagger's is at least guessable from the name, though only by a convention it doesn't promise. Metro's
is an `Impl` nested in the app's own graph interface: no marker interface, no annotation retained at
runtime, no name to match on. `ProbeMetroGraph$Impl` and any other `Foo$Impl` in the app are the same
shape.

So **the rule is about the provider, not the component**. A provider is a class of the framework, by
name, and the component collects the singleton's bytes anyway — one step further down the tree, since
it is what holds every provider.

## The providers

Both frameworks memoize a scoped binding in a `DoubleCheck`, and both leave one shared sentinel object
in the instance field until something first asks. Verified from the jars with `javap -p`:

| | Class holding the instance | Instance field | Sentinel |
| --- | --- | --- | --- |
| Dagger | `dagger.internal.DoubleCheck` | `instance` | `dagger.internal.DoubleCheck.UNINITIALIZED` |
| Metro | `dev.zacsweers.metro.internal.BaseDoubleCheck` | `_value` | `dev.zacsweers.metro.internal.BaseDoubleCheckKt.UNINITIALIZED` |

Three things there are worth knowing before editing the rule:

- **Metro declares the field on the superclass.** `dev.zacsweers.metro.internal.DoubleCheck` is what
  generated code instantiates, `BaseDoubleCheck` is where `_value` lives, and the rule names the
  superclass so that both it and anything else extending it are covered.
- **Metro's sentinel is on a different class from the field.** `BaseDoubleCheckKt` is the file facade
  for `BaseDoubleCheck.kt`, which is where a top level `private val UNINITIALIZED` ends up. Dagger's is
  a static on `DoubleCheck` itself. That's why the rule carries a holder class name per framework
  rather than assuming the sentinel is on the provider.
- **Both null out `provider` once initialised**, so the unscoped factory a provider was built from is
  not in the dump once the singleton exists, and can't be used to identify anything.

## Neither framework's code generation runs in this build

`DependencyInjectionOwnerTest` builds providers through `DoubleCheck.provider` and writes the
components by hand. Dagger's annotation processor could run here — it's KSP, which this repo already
has — but **Metro's cannot**: its Gradle plugin and compiler plugin are Java 21 bytecode, and both
this repo and CI are Java 17, so the Kotlin compiler here can't load it
(`UnsupportedClassVersionError: … class file version 65.0 … only recognizes up to 61.0`). Making it
possible would mean moving the build's Kotlin daemon to JDK 21, which is a repo-wide decision for one
test's sake.

Since the rule names no generated class, hand-writing the components costs the test nothing: what it
has to get right is the provider, and those are the frameworks' own.

## Taking another dump of really generated code

A throwaway Gradle project outside the repo, which is how the tables above were measured. Both
frameworks in one project so that one dump answers for both:

```kotlin
// build.gradle.kts
plugins {
  kotlin("jvm") version "2.4.10"
  id("com.google.devtools.ksp") version "2.3.10"
  id("dev.zacsweers.metro") version "1.4.0"
  application
}
kotlin { jvmToolchain(21) }
application { mainClass.set("probe.MainKt") }
dependencies {
  implementation("com.google.dagger:dagger:2.60.1")
  ksp("com.google.dagger:dagger-compiler:2.60.1")
}
```

```properties
# gradle.properties — jvmToolchain(21) alone isn't enough, the Kotlin daemon still starts on the
# JVM Gradle runs on, and Metro's compiler plugin won't load there.
kotlin.compiler.execution.strategy=in-process
```

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) gradle --no-daemon -q run --args="/tmp/di-probe.hprof"
```

`main` keeps every object it asks for in a list, then dumps with
`HotSpotDiagnosticMXBean.dumpHeap(path, false)`. Two things to get right in the fixtures:

- **`@Singleton` goes on the binding, not only on the component.** A component annotated `@Singleton`
  whose bindings aren't generates no `DoubleCheck` at all, and the dump looks like the framework
  doesn't memoize.
- **Give one scoped binding a dependency on another**, which is what makes Dagger emit a
  `SwitchingProvider` rather than a direct factory, and one injection site an `@Inject Provider<Foo>`
  field, which is the case where something other than the component holds the provider too.

Open the result the usual way:

```bash
./gradlew :shark:shark-explorer:shark-explorer-app:runNamed \
  --args="--title=\"DI probe\" /tmp/di-probe.hprof"
```
