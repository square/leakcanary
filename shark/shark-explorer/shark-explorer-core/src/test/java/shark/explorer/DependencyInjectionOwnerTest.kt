package shark.explorer

import com.sun.management.HotSpotDiagnosticMXBean
import dagger.internal.DoubleCheck as DaggerDoubleCheck
import dev.zacsweers.metro.internal.DoubleCheck as MetroDoubleCheck
import java.io.File
import java.lang.management.ManagementFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import shark.explorer.ReachabilityStrength.UNREACHABLE
import dagger.internal.Provider as DaggerProvider
import dev.zacsweers.metro.Provider as MetroProvider

/**
 * What the explorer makes of a dependency injection singleton, in a heap dump of a real JVM holding real
 * Dagger and real Metro providers — the class names, the field names and the sentinel a provider holds
 * before anything has asked it are each framework's own rather than this repo's idea of them.
 *
 * **The providers are built the way generated code builds them**, through `DoubleCheck.provider`, but the
 * components holding them are written here rather than generated. That is the point rather than a
 * shortcut: the rule in [OwnerReferences] never names a component, because nothing in a heap dump says
 * which object is one — Dagger's generated component is a `DaggerAppComponent$AppComponentImpl`, Metro's
 * is an `AppGraph$Impl`, an `Impl` nested in the app's own interface with no marker of any kind. What the
 * rule does depend on is the provider, and these are the frameworks' own.
 *
 * Neither framework's code generation runs in this build. Dagger's could: it is an annotation processor
 * and this repo already runs KSP. **Metro's cannot** — its compiler plugin is Java 21 bytecode and this
 * repo builds with Java 17, so the Kotlin compiler here can't load it. `notes/dependency-injection.md`
 * records a dump of really generated code from both frameworks, and how to take another one.
 *
 * The dump is written without collecting first and left in `build/heap-dumps`, for the reasons
 * [JvmReferenceStrengthTest] gives. To open the explorer on the same heap dump these assertions ran
 * against:
 *
 * ```
 * ./gradlew :shark:shark-explorer:shark-explorer-app:runNamed --args="--title=\"DI singletons\" \
 *   shark/shark-explorer/shark-explorer-core/build/heap-dumps/dependency-injection.hprof"
 * ```
 */
class DependencyInjectionOwnerTest {

  @Test fun `a Dagger singleton is held by the provider its component caches it in`() {
    val singleton = explorer.tree.onlyInstanceOf("DaggerSingleton")

    // Two other objects point at it, both closer to a GC root than the component is, which is the shape of
    // an injected singleton: whatever was injected with one usually hangs off something nearer a root than
    // the component, and none of those collaborators is where the singleton's bytes belong.
    assertThat(explorer.tree.dominatorOf(singleton.objectId)!!.label).isEqualTo("DoubleCheck")
    assertThat(explorer.tree.rootPathTo(singleton.objectId).stepLabels().takeLast(2)).containsExactly(
      "singletonProvider → DoubleCheck",
      "instance → DaggerSingleton"
    )
  }

  @Test fun `a Metro singleton is held by the provider its graph caches it in`() {
    val singleton = explorer.tree.onlyInstanceOf("MetroSingleton")

    assertThat(explorer.tree.dominatorOf(singleton.objectId)!!.label).isEqualTo("DoubleCheck")
    // `_value`, not `instance`: Metro declares the field on `BaseDoubleCheck`, which is why the rule names
    // a class and a field per framework rather than assuming one shape covers both.
    assertThat(explorer.tree.rootPathTo(singleton.objectId).stepLabels().takeLast(2)).containsExactly(
      "singletonProvider → DoubleCheck",
      "_value → MetroSingleton"
    )
  }

  @Test fun `a component collects the bytes of every singleton it caches`() {
    // Which is what the rule is for. Without it each of these is dominated by the whole heap dump, because
    // the injection sites are spread over everything the component built.
    listOf(
      "DaggerComponent" to "DaggerSingleton",
      "MetroGraph" to "MetroSingleton"
    ).forEach { (componentName, singletonName) ->
      val component = explorer.tree.onlyInstanceOf(componentName)
      val singleton = explorer.tree.onlyInstanceOf(singletonName)

      // The provider is a node between the two, holding its own handful of bytes, so the component
      // retains the singleton one step further down rather than immediately.
      assertThat(explorer.tree.dominatorLabelsOf(singleton.objectId))
        .startsWith("DoubleCheck", componentName)
      assertThat(explorer.tree.weight(component.objectId))
        .isGreaterThan(explorer.tree.weight(singleton.objectId))
    }
  }

  @Test fun `a singleton whose provider something else holds too is held by neither`() {
    val shared = explorer.tree.onlyInstanceOf("SharedProviderSingleton")

    // An `@Inject Provider<Foo>` field is handed the component's own provider, so two objects hold it and
    // the tree says what is true: neither of them is where the singleton's bytes belong. The rule stops
    // the sites holding the *instance* from scattering it and claims nothing beyond that.
    assertThat(explorer.tree.dominatorOf(shared.objectId)!!.label).isEqualTo("DoubleCheck")
    assertThat(explorer.tree.dominatorLabelsOf(shared.objectId)).doesNotContain("DaggerComponent")
  }

  @Test fun `a provider nothing has asked yet holds no singleton of its own`() {
    // Until something asks, both frameworks leave one sentinel object in the field, shared by every
    // provider in the process. One provider per framework here was never asked, so both sentinels are in
    // the dump with a provider pointing at them.
    val sentinelIds = explorer.tree.instancesOf("DoubleCheck")
      .mapNotNull { doubleCheck ->
        explorer.tree.summarize(doubleCheck.objectId)
          .fields
          .single { it.name == "instance" || it.name == "_value" }
          .inspectableObjectId
      }
      .filter { explorer.tree.label(it) == "Object" }

    assertThat(sentinelIds).hasSize(2)
    sentinelIds.forEach { sentinelId ->
      // Counting a sentinel as a singleton would hand a bare Object to whichever provider reached it
      // first, and take the static field that really holds it out of the tree.
      assertThat(explorer.tree.dominatorOf(sentinelId)!!.label).isNotEqualTo("DoubleCheck")
      assertThat(explorer.tree.independentPathsFromRoots(sentinelId).paths.map { it.stepLabels().last() })
        .contains("UNINITIALIZED → Object")
    }
  }

  @Test fun `a singleton whose component is gone is held by what points at it after all`() {
    // The dump is written without collecting, so the dropped component is still in it, and the explorer
    // draws it as what it is: nothing reaches it from a GC root, so neither its bytes nor its provider's
    // are attributed to anything.
    assertThat(explorer.tree.onlyInstanceOf("DroppedComponent").strength).isEqualTo(UNREACHABLE)
    val dropped = explorer.tree.onlyInstanceOf("DroppedSingleton")

    // Which is the whole of "unless the component is destroyed", and is why the rule needs no state
    // check: a provider never lets go of its instance, so the way a component stops owning its
    // singletons is by being collected, provider and all. The owner reference then never turns up
    // during the walk, and what is still pointing at the singleton is both how it's held and what's
    // leaking it — which is exactly what there is to be shown.
    assertThat(explorer.tree.dominatorOf(dropped.objectId)!!.label).isEqualTo("DroppedInjectionSite")
    assertThat(explorer.tree.rootPathTo(dropped.objectId).stepLabels().takeLast(2)).containsExactly(
      "droppedSite → DroppedInjectionSite",
      "singleton → DroppedSingleton"
    )
  }

  companion object {

    private val HEAP_DUMP_FILE = File("build/heap-dumps", "dependency-injection.hprof")

    /** One heap dump for the whole class, as in [JvmReferenceStrengthTest]. */
    private lateinit var explorer: HeapExplorer

    @BeforeClass @JvmStatic fun openHeapDumpOfThisJvm() {
      Components.build()
      // Everything the tests that ran before left behind goes now, so that the dump is the live set and
      // these components rather than a hundred megabytes of somebody else's garbage.
      System.gc()
      // After the collection rather than before it, so that one component and its provider are in the
      // dump as garbage, the way a destroyed component is in a heap dump taken from an app. Fragile in
      // the way [JvmReferenceStrengthTest] describes: a collection between here and the dump takes it.
      Components.dropped = null

      HEAP_DUMP_FILE.parentFile.mkdirs()
      // Left behind by the last run, and the MBean refuses to overwrite a file.
      HEAP_DUMP_FILE.delete()
      val mBean = ManagementFactory.newPlatformMXBeanProxy(
        ManagementFactory.getPlatformMBeanServer(),
        "com.sun.management:type=HotSpotDiagnostic",
        HotSpotDiagnosticMXBean::class.java
      )
      mBean.dumpHeap(HEAP_DUMP_FILE.absolutePath, false)
      explorer = HeapExplorer.open(HEAP_DUMP_FILE)
    }

    @AfterClass @JvmStatic fun closeHeapDump() {
      explorer.close()
    }
  }
}

/*
 * What the heap dump holds, one top level class per thing so that each is found by its own name and drawn
 * as its own rectangle, the way [JvmReferenceStrengthTest] does it.
 */

/**
 * Where all of it is held from: a Kotlin object, so a field here is a field of a singleton its own class's
 * static points at, which is a GC root.
 *
 * The injection sites hang off here too, and are therefore each closer to a GC root than the component is.
 * That is the difficulty the owner rule exists for, and it is how a real app looks.
 */
private object Components {
  @JvmField var dagger: DaggerComponent? = null
  @JvmField var metro: MetroGraph? = null
  @JvmField var daggerSite: DaggerInjectionSite? = null
  @JvmField var metroSite: MetroInjectionSite? = null
  @JvmField var providerSite: ProviderInjectionSite? = null

  /** Set to null once the heap is collected, so that the dump holds a component nothing reaches. */
  @JvmField var dropped: DroppedComponent? = null
  @JvmField var droppedSite: DroppedInjectionSite? = null

  fun build() {
    val daggerComponent = DaggerComponent()
    val metroGraph = MetroGraph()
    val droppedComponent = DroppedComponent()
    dagger = daggerComponent
    metro = metroGraph
    dropped = droppedComponent
    // Asking a provider is what makes it cache an instance, which is the state the rule is about. The two
    // deliberately left unasked are `neverAskedProvider` on each component.
    val daggerSingleton = daggerComponent.singletonProvider.get()
    val metroSingleton = metroGraph.singletonProvider()
    val sharedSingleton = daggerComponent.sharedProvider.get()
    // Two sites per singleton rather than one, so that no singleton has a single rival that could dominate
    // it on its own — what the tree has to fall back on is a lowest common dominator of several.
    daggerSite = DaggerInjectionSite(daggerSingleton, DaggerInjectionSite(daggerSingleton, null))
    metroSite = MetroInjectionSite(metroSingleton, MetroInjectionSite(metroSingleton, null))
    providerSite = ProviderInjectionSite(daggerComponent.sharedProvider, sharedSingleton)
    droppedSite = DroppedInjectionSite(droppedComponent.singletonProvider.get())
  }
}

private const val KB = 1024

/** A singleton, which in a heap dump is any object at all: what makes it one is a provider caching it. */
private class DaggerSingleton {
  @JvmField val bytes = ByteArray(64 * KB)
}

private class MetroSingleton {
  @JvmField val bytes = ByteArray(48 * KB)
}

/** The one whose provider an injection site holds as well, the way an `@Inject Provider<Foo>` field does. */
private class SharedProviderSingleton {
  @JvmField val bytes = ByteArray(32 * KB)
}

private class NeverAskedSingleton

/** The one whose component is collected before the dump, leaving the injection site holding it alone. */
private class DroppedSingleton {
  @JvmField val bytes = ByteArray(16 * KB)
}

/**
 * Stands in for the class Dagger generates: one `DoubleCheck` field per scoped binding, built with the
 * call the generated code makes.
 */
private class DaggerComponent {
  @JvmField val singletonProvider: DaggerProvider<DaggerSingleton> =
    DaggerDoubleCheck.provider<DaggerSingleton>(newDaggerProvider { DaggerSingleton() })

  @JvmField val sharedProvider: DaggerProvider<SharedProviderSingleton> =
    DaggerDoubleCheck.provider<SharedProviderSingleton>(newDaggerProvider { SharedProviderSingleton() })

  @JvmField val neverAskedProvider: DaggerProvider<NeverAskedSingleton> =
    DaggerDoubleCheck.provider<NeverAskedSingleton>(newDaggerProvider { NeverAskedSingleton() })
}

/** A component of its own class, so that collecting it doesn't take a second [DaggerComponent] with it. */
private class DroppedComponent {
  @JvmField val singletonProvider: DaggerProvider<DroppedSingleton> =
    DaggerDoubleCheck.provider<DroppedSingleton>(newDaggerProvider { DroppedSingleton() })
}

/** The same for Metro, whose generated graph is an `Impl` nested in the app's own graph interface. */
private class MetroGraph {
  @JvmField val singletonProvider: MetroProvider<MetroSingleton> =
    MetroDoubleCheck.provider(newMetroProvider { MetroSingleton() })

  @JvmField val neverAskedProvider: MetroProvider<NeverAskedSingleton> =
    MetroDoubleCheck.provider(newMetroProvider { NeverAskedSingleton() })
}

/**
 * The unscoped provider a `DoubleCheck` wraps, written out rather than built with either framework's own
 * helper: Dagger's `Provider` is a Java interface reached through two overloads Kotlin can't tell apart,
 * and Metro's `provider { }` is an inline function compiled for Java 11, which won't inline into the Java
 * 8 bytecode every module here but the desktop app is built as.
 */
private fun <T : Any> newDaggerProvider(create: () -> T) = object : DaggerProvider<T> {
  override fun get(): T = create()
}

private fun <T : Any> newMetroProvider(create: () -> T) = object : MetroProvider<T> {
  override fun invoke(): T = create()
}

private class DaggerInjectionSite(
  @JvmField val singleton: DaggerSingleton,
  @JvmField val alsoHere: DaggerInjectionSite?
)

private class MetroInjectionSite(
  @JvmField val singleton: MetroSingleton,
  @JvmField val alsoHere: MetroInjectionSite?
)

/** One rival and no other, so that what holds the singleton once its component is gone is unambiguous. */
private class DroppedInjectionSite(@JvmField val singleton: DroppedSingleton)

/** Holds the component's own provider rather than the instance, the way `@Inject Provider<Foo>` does. */
private class ProviderInjectionSite(
  @JvmField val sharedProvider: DaggerProvider<SharedProviderSingleton>,
  @JvmField val singleton: SharedProviderSingleton
)
