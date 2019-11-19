

### All Types

| Name | Summary |
|---|---|
| [shark.ApplicationLeak](../shark/-application-leak/index.md) | A leak found by [HeapAnalyzer](../shark/-heap-analyzer/index.md) in your application. |
| [shark.AppSingletonInspector](../shark/-app-singleton-inspector/index.md) | Inspector that automatically marks instances of the provided class names as not leaking because they're app wide singletons. |
| [shark.HeapAnalysis](../shark/-heap-analysis/index.md) | The result of an analysis performed by [HeapAnalyzer](../shark/-heap-analyzer/index.md), either a [HeapAnalysisSuccess](../shark/-heap-analysis-success/index.md) or a [HeapAnalysisFailure](../shark/-heap-analysis-failure/index.md). This class is serializable however there are no guarantees of forward compatibility. |
| [shark.HeapAnalysisException](../shark/-heap-analysis-exception/index.md) |  |
| [shark.HeapAnalysisFailure](../shark/-heap-analysis-failure/index.md) | The analysis performed by [HeapAnalyzer](../shark/-heap-analyzer/index.md) did not complete successfully. |
| [shark.HeapAnalysisSuccess](../shark/-heap-analysis-success/index.md) | The result of a successful heap analysis performed by [HeapAnalyzer](../shark/-heap-analyzer/index.md). |
| [shark.HeapAnalyzer](../shark/-heap-analyzer/index.md) | Analyzes heap dumps to look for leaks. |
| [shark.IgnoredReferenceMatcher](../shark/-ignored-reference-matcher/index.md) | [IgnoredReferenceMatcher](../shark/-ignored-reference-matcher/index.md) should be used to match references that cannot ever create leaks. The shortest path finder will never go through matching references. |
| [shark.Leak](../shark/-leak/index.md) | A leak found by [HeapAnalyzer](../shark/-heap-analyzer/index.md), either an [ApplicationLeak](../shark/-application-leak/index.md) or a [LibraryLeak](../shark/-library-leak/index.md). |
| [shark.LeakNodeStatus](../shark/-leak-node-status/index.md) |  |
| [shark.LeakReference](../shark/-leak-reference/index.md) | A single field in a [LeakTraceElement](../shark/-leak-trace-element/index.md). |
| [shark.LeakTrace](../shark/-leak-trace/index.md) | A chain of references that constitute the shortest strong reference path from a GC root to the leaking object. Fixing the leak usually means breaking one of the references in that chain. |
| [shark.LeakTraceElement](../shark/-leak-trace-element/index.md) |  |
| [shark.LibraryLeak](../shark/-library-leak/index.md) | A leak found by [HeapAnalyzer](../shark/-heap-analyzer/index.md), where the only path to the leaking object required going through a reference matched by [pattern](../shark/-library-leak/pattern.md), as provided to a [LibraryLeakReferenceMatcher](../shark/-library-leak-reference-matcher/index.md) instance. This is a known leak in library code that is beyond your control. |
| [shark.LibraryLeakReferenceMatcher](../shark/-library-leak-reference-matcher/index.md) | [LibraryLeakReferenceMatcher](../shark/-library-leak-reference-matcher/index.md) should be used to match references in library code that are known to create leaks and are beyond your control. The shortest path finder will only go through matching references after it has exhausted references that don't match, prioritizing finding an application leak over a known library leak. Library leaks will be reported as [LibraryLeak](../shark/-library-leak/index.md) instead of [ApplicationLeak](../shark/-application-leak/index.md). |
| [shark.MetadataExtractor](../shark/-metadata-extractor/index.md) | Extracts metadata from a hprof to be reported in [HeapAnalysisSuccess.metadata](../shark/-heap-analysis-success/metadata.md). |
| [shark.ObjectInspector](../shark/-object-inspector/index.md) | Provides LeakCanary with insights about objects (classes, instances and arrays) found in the heap. [inspect](../shark/-object-inspector/inspect.md) will be called for each object that LeakCanary wants to know more about. The implementation can then use the provided [ObjectReporter](../shark/-object-reporter/index.md) to provide insights for that object. |
| [shark.ObjectInspectors](../shark/-object-inspectors/index.md) | A set of default [ObjectInspector](../shark/-object-inspector/index.md)s that knows about common JDK objects. |
| [shark.ObjectReporter](../shark/-object-reporter/index.md) | Enables [ObjectInspector](../shark/-object-inspector/index.md) implementations to provide insights on [heapObject](../shark/-object-reporter/heap-object.md), which is an object (class, instance or array) found in the heap. |
| [shark.OnAnalysisProgressListener](../shark/-on-analysis-progress-listener/index.md) | Reports progress from the [HeapAnalyzer](../shark/-heap-analyzer/index.md) as they occur, as [Step](../shark/-on-analysis-progress-listener/-step/index.md) values. |
| [shark.ReferenceMatcher](../shark/-reference-matcher/index.md) | Used to pattern match known patterns of references in the heap, either to ignore them ([IgnoredReferenceMatcher](../shark/-ignored-reference-matcher/index.md)) or to mark them as library leaks ([LibraryLeakReferenceMatcher](../shark/-library-leak-reference-matcher/index.md)). |
| [shark.ReferencePattern](../shark/-reference-pattern/index.md) | A pattern that will match references for a given [ReferenceMatcher](../shark/-reference-matcher/index.md). |
