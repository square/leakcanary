# The treemap as a document, and the MCP app that plays it

`draw_treemap` answers with a picture somebody can press into, inside whatever they are talking to their
agent in. Three pieces make that work, and each has things about it that reading the code won't tell you:
[Remote Compose](https://developer.android.com/jetpack/androidx/releases/compose-remote) as the format, a
TypeScript player vendored into this repository, and
[MCP Apps](https://modelcontextprotocol.io) as how a host opens a page beside a tool's answer.

The reason it is a *document* and not a PNG is the whole point: the rectangles carry click areas, so a press
is a `resources/read` of the treemap under that address and the next document arrives. Nobody's model is in
that loop.

## Why the writing half is in `shark-dive-app`

`androidx.compose.remote:remote-creation-jvm` is **Java 11 bytecode**, and `shark-dive-agent` is a Java 8
target. So the agent module cannot link against it, and `AgentHeapDump.drawTreemap` is a call across that
line: the agent side asks for a drawing and gets bytes, and `shark.dive.app.treemapDocument` is the one
place that knows Remote Compose exists. The colours are the second reason and would be enough on their own —
they are Compose colours in `CellColors`, and a drawing coloured by a copy of that scheme would drift from
the window within a release.

The players are Android artifacts. Nothing on the JVM side of this repository plays a document; what plays
these is the TypeScript port below, on a canvas in the client.

## What the writer will and won't do

- **`encodeToByteArray()`, never `buffer()`.** The writer allocates a megabyte up front and `buffer()` hands
  back the whole backing array, trailing zeroes and all. `encodeToByteArray()` trims to what was written.
- **It cannot measure text.** `RemoteComposeWriter.textLength` is a value the *player* computes as it draws,
  not something readable while writing, and there is no font on hand anyway. So labels are cut by arithmetic
  — `LABEL_GLYPH_RATIO` in `TreemapDocument.kt` — and the estimate is deliberately generous, because a name
  cut a character early still reads and one cut a character late overhangs onto the sibling rectangle.
- **A paint is document state, not an argument to a draw.** Every draw here sets its own colour and style;
  the alternative is an operation whose colour depends on which operation ran before it, which in a treemap
  of a thousand rectangles is a bug nobody can see.
- **No brushes and no path effects.** The window textures a pile of leftover objects with a repeated dot
  tile through a `Brush`, and dashes the border of a group — neither exists here. So a pile is hatched with
  bounded diagonal lines instead (a dotted pile filling the view would be tens of thousands of operations on
  the wire), and a group's border is told from an object's by weight.

**Reading a document back is how it is tested**, since the player is in somebody else's process:

```kotlin
val buffer = RemoteComposeBuffer()
ByteArrayInputStream(document).use { RemoteComposeBuffer.read(it, buffer) }
val operations = ArrayList<Operation>().also { buffer.inflateFromBuffer(it) }
```

`TextData.mText`/`mTextId` are public fields and `ClickArea` has `getId()`/`getContentDescriptionId()`, but
**`ClickArea.mMetadata` is package private** — and it is the field worth asserting on, being where a press
ends up. `TreemapDocumentTest` reads it by reflection with a comment saying why.

## The player is vendored, and reproducibly

`shark-dive-agent/src/main/resources/shark/dive/agent/remote-compose-player.js`, 422,192 bytes, built from
[camaelon/remotecompose-experiments](https://github.com/camaelon/remotecompose-experiments) at commit
`2168eb72a5f2de29029e9e3d88daf9c3abcf5a2a`, Apache 2.0. The provenance and the licence are in
`remote-compose-player.LICENSE.txt` beside it.

```bash
cd players/typescript
npx esbuild@0.27.7 src/web/main.ts --bundle --format=iife --target=es2020 --global-name=RC --minify
```

Byte identical on a rebuild, which is what makes the vendored copy checkable. **Vendored because it is not
published**: the upstream package is `private: true`, so there is no npm artifact to depend on, and a page
that fetched a player over the network would be an MCP app that needs a CSP allowing a domain — which is the
one thing this app doesn't ask a host for.

Three things about the player that cost an afternoon each:

- **The canvas is 256×256 until told otherwise**, and it does not fit itself to the document. A larger
  drawing is silently clipped to a corner of itself, which reads exactly like a broken renderer. Call
  `handle.resize(doc.getWidth(), doc.getHeight())` — from a `setTimeout(…, 0)` inside `onLoad`, because the
  handle isn't assigned yet when `onLoad` fires.
- **There are two dispatch channels and they are not interchangeable.** `addClickArea` hits arrive at
  `doc.addIdActionListener({ onAction(id, metadata) })`. `doc.addActionCallback` is for named component
  actions and never sees a click area, so a page wired to it gets a map that draws and does nothing.
- `RC.createPlayer(container, {data | src | buffer, width, height, theme, background, onLoad})` is the whole
  API surface used here, and `data` is base64.

## Why the drawing is a resource and never a tool result

`McpSession.toolResult` pretty prints an answer into `content[0].text`, which is the model's context.
Measured on this branch, one whole-heap-dump drawing:

| Heap dump | Canvas | Document | As base64 | Read |
| --- | --- | --- | --- | --- |
| `leak_asynctask_o.hprof`, 7.8 MB | 900×560 | 178 KB | 237 KB | 482 ms, then 17 ms |
| `leak_asynctask_o.hprof` | 1600×1000 | 383 KB | 511 KB | 32 ms |
| `large-dump.hprof`, 39 MB | 900×560 | 456 KB | 608 KB | 1,666 ms, then 63 ms |

So a tool result would put a **quarter to two thirds of a megabyte of base64** into the context of a model
that cannot see it — 60 k to 150 k tokens for one picture. As a resource it goes from the server to the
page and the model is told a URI. The first read of a dump pays for the leaks and the referrer index and is
seconds; every read after it is tens of milliseconds, so walking the map is as cheap as it looks.

**And the URI says everything about the drawing**, `shark-dive://treemap/{heapDump}/{object}?width&height`,
which is what keeps `McpSession` stateless: the page fetches at the size of its own canvas, refetches rooted
somewhere else when somebody presses a rectangle, and lays out again rather than scaling when the panel is
dragged. A model never knows the size of the client's canvas, so a tool that drew at a size it chose would
be drawing for nobody. Which is why `draw_treemap` draws nothing — it checks the object is a node of the
tree, and hands back a URI.

## What a host has to support, and what it is told

`_meta.ui.resourceUri` on the tool and on its answer, plus the deprecated `_meta["ui/resourceUri"]` beside
it for older hosts. The page asks for `serverResources` and says so on screen when a host hasn't got it,
that being the one capability it cannot do without. `updateModelContext` is optional and is how the model
finds out where somebody navigated to — deferred by the host until the next user message, which is exactly
when it needs to know.

The page declares a CSP of four empty domain lists. It fetches nothing and loads nothing: the player is
spliced into the HTML at read time, the drawing arrives over the MCP channel it is already on.

## Where up leads, which the window never had to answer

A player has no history and no chrome of ours to put a button in, so the title strip carries a click area
and the title reads `↑ <where you are>`. That is the whole navigation back out, and it made
`HeapDominatorTreemap.parentOf` necessary: the root's children are gathered into piles by class, so an
object nothing in particular holds is *drawn* inside a pile while its dominator is the whole heap dump.
Going up by the dominator lands a level past the rectangle somebody pressed from, and a pile has no
dominator at all — which was a drawing with no way out of it, found by pressing one in a browser rather than
by reading the code.

## Trying the whole loop

There is no test that spans it — the server is a JVM, the page is a browser and the host is neither — so it
is driven by hand: run the app with `--no-ui --mcp-stdio <dump>`, put a host page around the HTML that
`resources/read ui://shark-dive/treemap` answers with, forward the page's `resources/read` back to the
server, and press rectangles in headless Chrome over CDP. What that check has to end with is a second
`resources/read` at a different address and a `ui/update-model-context` naming it, because a drawing that
renders and doesn't navigate looks perfect in a screenshot.
