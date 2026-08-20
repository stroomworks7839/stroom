# Data-Centric Focused-Node UI Redesign

## Problem Statement

The current canvas-based node editor becomes unmanageable at scale. Real-world Data Splitter configurations are deep trees with wide branches:

| Project | Nodes | Max Depth | Max Breadth | Notes |
|---------|-------|-----------|-------------|-------|
| win_sec | 173 | 5 | 89 nodes at depth 4 | Windows Security events |
| win_app_xml | 95 | 5 | 44 nodes at depth 3 | Windows App Event XML |
| win_app | 120 | 5 | — | Windows App events |
| ausearch | 25 | 12 | 4 | Deep nesting, narrow |
| apache_httpd | 27 | 5 | 14 | HTTP access logs |

Even with auto-layout, 173 nodes on a single canvas requires constant panning and zooming. The user cannot see the data and the graph simultaneously. Expression editing requires scrolling to find the right node, editing it, then scrolling back to the preview to see the effect.

### Core Issues

1. **Cognitive overload** — all nodes visible simultaneously, most irrelevant to the current editing task
2. **Data is secondary** — sample data is hidden in a bottom panel, physically distant from the expressions that consume it
3. **No positional context** — at 89 nodes wide, the user loses track of where they are in the tree
4. **Expression→data feedback loop is broken** — edit a regex, scroll to preview, re-run, scroll back to regex. This is the most frequent workflow and it's the most painful.

---

## Design Philosophy

> **The data is the primary object. The graph serves the data.**

The user's mental model is: "I have this data, and I want to extract structure from it." The UI should mirror that mental model — data at the top, extraction rules below, real-time feedback showing what each rule captures.

### Key Principles

1. **Focus on one node at a time** — show the current expression/group with its full settings, not the entire graph
2. **Data always visible** — sample data stays at the top, with live highlights showing what the focused node matches
3. **Navigate, don't scroll** — breadcrumbs and a tree sidebar replace infinite canvas panning
4. **Click data to navigate** — clicking on highlighted regions in the sample data jumps to the expression that matched them
5. **Progressive disclosure** — start with the root, drill into children on demand

---

## Proposed Layout

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  Stroom · Shapeshifter          [Project ▾]  [⚡Run]  [💾Save]  [🤖AI]     │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─── Sample Data ──────────────────────────────────────────────────────┐   │
│  │ 2019-01-01 00:00:00.000 4 Information Microsoft-Windows-Security-   │   │
│  │ ████████████████████ ██████████ ███████████ ███████████ ████████████ │   │
│  │ Auditing 4616:  The system time was changed.  Subject:  Security    │   │
│  │ ██████████████████████████ ████████████████ ███████ ████████████████ │   │
│  │ ID: S-1-5-19  Account Name: LOCAL SERVICE  Account Domain: NT AUTH  │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                     ↑ highlighted regions are clickable                      │
│                                                                              │
│  🏠 Root › Split (\\n) › Regex (event) › Group ($1)                          │
│                                                                              │
│  ┌─── Tree ────────┐  ┌─── Focused Node ─────────────────────────────┐     │
│  │ ▾ Source         │  │                                               │     │
│  │   ▾ Split (\n)   │  │  Regex: Event Line Parser                     │     │
│  │     ▾ Regex ◀──  │  │  ─────────────────────────────                │     │
│  │       Group      │  │  Pattern: ^(\d{4}-\d{2}-\d{2})               │     │
│  │         Data:host │  │  Dot-all: ☐   Case-insensitive: ☐           │     │
│  │         If(...)   │  │  Min match:     Max match:                   │     │
│  │         Data:msg  │  │                                               │     │
│  │       Data:Event  │  │  ─── Captures / Bindings ──────────────────  │     │
│  │     Split (tab)   │  │  ● 0: Entire match         (no binding)     │     │
│  │       ...         │  │  ● 1: Date  → Var:eventDate [unbind]        │     │
│  │                   │  │  ● 2: Time  → Var:eventTime [unbind]        │     │
│  │                   │  │  ○ 3: Level                 [+ bind]        │     │
│  │                   │  │                                               │     │
│  │                   │  │  ─── Children (3) ──────────────────────    │     │
│  │                   │  │  1. Data: host        [Edit] [×]            │     │
│  │                   │  │  2. If (hasReferer) ▸ [Edit] [×]            │     │
│  │                   │  │  3. Data: msg         [Edit] [×]            │     │
│  │                   │  │  [+ Add Child]                               │     │
│  │                   │  │                                               │     │
│  └──────────────────┘  └───────────────────────────────────────────────┘     │
│                                                                              │
│  ┌─── Output Preview ──────────────────────────────────────────────────┐    │
│  │ <record><data name="Date" value="2019-01-01"/>                      │    │
│  │ <data name="Time" value="00:00:00.000"/><data name="Level"...       │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## Component Design

### 1. Sample Data Panel (Top)

The sample data panel moves from the bottom preview area to the **top of the viewport**, where it is always visible.

#### Features

| Feature | Description |
|---------|-------------|
| **Persistent visibility** | Always visible, never hidden behind tabs or panels |
| **Live highlights** | Background-coloured spans showing what the focused node's regex/split matches |
| **Clickable regions** | Clicking a highlighted region navigates to the expression node that produced it |
| **Depth-aware colouring** | Each tree depth gets a colour band; nested matches show stacked highlights |
| **Match annotation** | Hover a highlight to see: node name, match index, capture groups, offset/length |
| **Editable** | User can type/paste sample data directly; changes trigger auto-preview |
| **Multi-line support** | Wraps long lines; scroll vertically for large samples |
| **Context markers** | Vertical markers show record/line boundaries detected by splits |

#### Bidirectional Navigation: Data ↔ Node

Highlighting works in **both directions**:

**Data → Node** (click data to navigate):

```
Click on "2019-01-01" highlight
    ↓
Lookup: CaptureInfo { node_id: "abc...", offset: 0, length: 10 }
    ↓
Navigate: set focused_node = "abc..."
    ↓
Breadcrumb updates: Root › Split (\n) › Regex (event)
    ↓
Tree sidebar: scrolls to and highlights "abc..."
    ↓
Focused node panel: shows Regex settings for "abc..."
    ↓
Highlights update: only show matches for "abc..." and its ancestors
```

**Node → Data** (select node to highlight data):

```
Click Regex node in tree sidebar (or navigate via breadcrumb)
    ↓
focused_node = "abc..."
    ↓
Sample data panel: brightens highlights for this node's matches
    ↓
Ancestor matches shown dimmed, non-ancestor matches hidden
    ↓
First match scrolled into view in sample data panel
```

This creates a **fully bidirectional** interaction model: see data → click region → edit expression, OR select expression → see what it matches → refine pattern.

#### Implementation

The preview API already returns `CaptureInfo` with `node_id`, `offset`, `length`, and `depth`. This is exactly what's needed:

```rust
pub struct CaptureInfo {
    pub node_id: String,     // → navigate to this node on click
    pub node_type: String,
    pub offset: usize,       // → highlight start
    pub length: usize,       // → highlight end
    pub output: Option<String>,
    pub depth: usize,        // → colour band
}
```

---

### 2. Breadcrumb Navigation Bar

Replaces the infinite canvas as the primary navigation mechanism.

```
🏠 Root › Split (\n) › Regex (event parser) › Group ($1)
```

#### Behaviour

| Action | Result |
|--------|--------|
| Click "Root" | Navigate to root scope, show Source node |
| Click any ancestor | Navigate to that node, show its settings + children |
| Current node | Shown as non-clickable, bold text |
| Keyboard: `Backspace`/`Alt+←` | Navigate to parent |
| Keyboard: `Alt+→` | Navigate to first child (if any) |

#### Breadcrumb Entry Format

Each entry shows the node type icon and a short label:

```
🔤 Source › ✂️ Split (\n) › 📐 Regex (^(\S+)...) › 📦 Group ($1)
```

For expression nodes, the label includes a truncated version of the pattern/delimiter.

---

### 3. Tree Sidebar

A collapsible tree view on the left, replacing the node palette and canvas overview.

#### Features

| Feature | Description |
|---------|-------------|
| **Indented hierarchy** | Shows the full node tree with disclosure triangles |
| **Current node marker** | `◀` or highlighted background on the focused node |
| **Type icons** | Small colour-coded dots matching node header colours |
| **Inline status** | Shows match count per node (from last preview run) |
| **Click to navigate** | Click any node → set as focused node |
| **Right-click context menu** | Add child, delete, move up/down, duplicate |
| **Drag to reorder** | Drag nodes within a parent's children to reorder |
| **Drag to reparent** | Drag a node onto a different parent |
| **Search/filter** | Type to filter the tree by node name or type |
| **Collapse depth** | Button to collapse all below depth N |

#### Tree Item Format

```
▾ ✂️ Split (\n)                          42 matches
   ▾ 📐 Regex (^(\d{4})...)              42 matches
      📊 Var: eventDate                   42 matches
      📊 Var: eventTime                   42 matches
      📊 Var: level                       42 matches
      📄 Data: host                       42 matches
      ▸ 🔀 If (hasReferer)                3 matches
      📄 Data: msg                        42 matches
   ▸ ✂️ Split (\t)                        0 matches
```

The match counts come from the `NodeTiming.match_count` field already available from the timing API.

---

### 4. Focused Node Panel

The right/centre panel shows **one node at a time** with full editing controls.

#### For Expression Nodes (Regex, Split)

Captures/bindings and structural children are shown as **separate sections**. Var nodes that are bound to captures appear only in the captures section, not duplicated in the children list. This keeps the two concerns visually distinct: "what does this expression capture?" vs "what child expressions run on its output?"

```
┌─── Regex: Event Line Parser ────────────────────────────────┐
│                                                               │
│  Pattern: [                                              ]    │
│  ☐ Dot-all    ☐ Case-insensitive    Advance: [        ]      │
│  Min match: [    ]  Max match: [    ]  Only match: [    ]    │
│                                                               │
│  ─── Captures / Bindings ──────────────────────────────────  │
│  ● 0: Entire match         (no binding)                      │
│  ● 1: Date                 → Var: eventDate  [unbind]        │
│  ● 2: Time                 → Var: eventTime  [unbind]        │
│  ○ 3: Level                [+ bind to Var]                   │
│                                                               │
│  ─── Match Preview ────────────────────────────────────────  │
│  Match 1 of 42:                                              │
│    $0: "2019-01-01 00:00:00.000 4 Information..."            │
│    $1: "2019-01-01"                                          │
│    $2: "00:00:00.000"                                        │
│    $3: "4"                                                   │
│  [◀ Prev]  [Next ▶]                                         │
│                                                               │
│  ─── Children (3) ─────────────────────────────────────────  │
│  1. 📄 Data: host            [Edit ▸]  [↕]  [×]             │
│  2. 🔀 If (hasReferer)  ▸    [Edit ▸]  [↕]  [×]             │
│  3. 📄 Data: msg             [Edit ▸]  [↕]  [×]             │
│  [+ Add Child]                                               │
└───────────────────────────────────────────────────────────────┘
```

> [!NOTE]
> Bound Var nodes (e.g. `Var: eventDate` bound to capture $1) appear **only** in the Captures/Bindings section. They are not duplicated in the Children list. This reflects the semantic distinction: bindings capture data from the parent expression, while children are expressions that run on the parent's matched content.

#### For Group Nodes

```
┌─── Group: Event Output ──────────────────────────────────────┐
│                                                               │
│  Value source: [▾ Capture $1 (from parent Regex)]             │
│  ☐ Reverse    Match order: [▾ sequence]   ☐ Ignore errors    │
│  Record header: [        ]   Record footer: [        ]       │
│                                                               │
│  ─── Children (6) ─────────────────────────────────────────  │
│  ...                                                         │
└───────────────────────────────────────────────────────────────┘
```

#### For Leaf Nodes (Data, Var)

Data and Var nodes are simple enough to edit **inline** in the children list of their parent, without needing full navigation. Clicking "Edit ▸" on a child row either:
- **Expands** an inline editor if the node is a leaf (Data, Var — no children)
- **Navigates** to the node if it has children (Group, Regex, Split, etc.)

```
│  ─── Children (8) ─────────────────────────────────────────  │
│  1. 📊 Var: eventDate        [Edit ▸]  [↕]  [×]             │
│     ┌──────────────────────────────────────────────────────┐ │
│     │ ID: eventDate                                        │ │
│     │ Value: ● Capture $1 (Date) from parent               │ │
│     │ Transforms: [none]                                   │ │
│     └──────────────────────────────────────────────────────┘ │
│  2. 📊 Var: eventTime        [Edit ▸]  [↕]  [×]             │
```

#### Match Preview Section

The focused node panel includes a **match preview** section showing what the current expression matches against the sample data. This is computed per-node from the existing preview API response:

- Matches are numbered (1 of N)
- Each capture group value is shown
- User can step through matches with prev/next buttons
- Highlighted match region in the sample data panel scrolls into view

This creates a tight **edit→see→refine** loop entirely within one screen.

---

### 5. Output Preview Panel (Bottom)

Remains at the bottom but becomes more focused:

| Feature | Description |
|---------|-------------|
| **Auto-run** | Optionally auto-runs preview on any settings change (debounced 500ms) |
| **Focused output** | Can filter to show output only from the focused node's subtree |
| **Diff mode** | Shows what changed vs. previous run (green/red highlighting) |
| **Stats bar** | Record count, capture count, errors, warnings — always visible |

---

### 6. Add Child Popup (Replaces Palette)

There is no separate node type palette. Instead, the "[+ Add Child]" button at the bottom of each children list opens a **contextual popup** showing only node types valid as children of the focused node:

| Parent Type | Valid Children |
|-------------|---------------|
| Source | Split, Regex, All |
| Split | Group, Data, Var, Split, Regex, All |
| Regex | Group, Data, Var, Split, Regex, All |
| Group | Data, Var, Split, Regex, All, If, Choose |
| Sequence | Any combinator or atom |

The popup is a simple searchable list grouped by category (Expressions, Output, Combinators, etc.). Selecting a type creates the node as a child of the focused node and opens it for editing.

This eliminates the "add node then connect" two-step workflow — every node is created in context. No palette sidebar is needed.

---

## Navigation Model

### State

```rust
/// The currently focused node (shown in the right panel).
/// None = root overview (showing Source + top-level tree).
let focused_node: RwSignal<Option<NodeId>> = RwSignal::new(None);
```

### Navigation Actions

| Action | Effect |
|--------|--------|
| Click tree node | `focused_node = Some(clicked_id)` |
| Click sample data highlight | `focused_node = Some(capture_info.node_id)` |
| Click child "[Edit ▸]" | `focused_node = Some(child_id)` |
| Click breadcrumb ancestor | `focused_node = Some(ancestor_id)` or `None` for root |
| `Backspace` / `Alt+←` | Navigate to parent of focused node |
| `Alt+→` | Navigate to first child of focused node |
| `Alt+↑` / `Alt+↓` | Navigate to previous/next sibling |

### Derived State

From `focused_node`, everything else is derived:

```rust
// Breadcrumb: walk parent chain from focused_node to root
let breadcrumb_chain = move || -> Vec<(NodeId, String)> {
    let mut chain = Vec::new();
    let mut current = focused_node.get();
    while let Some(nid) = current {
        chain.push((nid, node_name(nid)));
        current = parent_of(nid);
    }
    chain.reverse();
    chain
};

// Children: the ordered children of the focused node
let focused_children = move || -> Vec<NodeId> {
    match focused_node.get() {
        Some(nid) => children_of(nid),
        None => vec![root_node_id],
    }
};

// Highlights: only show matches for focused node and its ancestors
let focused_highlights = move || -> Vec<Highlight> {
    let focused = focused_node.get();
    preview_captures.get().iter()
        .filter(|c| is_ancestor_or_self(c.node_id, focused))
        .collect()
};
```

---

## What Focused Mode Doesn't Use

| Not Used in Focused Mode | Notes |
|--------------------------|-------|
| Infinite canvas pan/zoom | Replaced by focused-node + tree navigation |
| Camera state (x, y, zoom) | Not needed — no spatial canvas |
| Node (x, y) positions | Not needed — tree structure provides layout |
| ChildSet containers | Replaced by inline children lists in the focused panel |
| Structural wires | No canvas = no wires |
| Data-flow wires | Var references shown as text in captures/bindings section |
| Minimap | Tree sidebar serves the same purpose |
| Drag-to-reposition nodes | Replaced by drag-to-reorder in children list |
| Visual groups (bounding boxes) | Not needed — tree structure provides grouping |
| Node type palette sidebar | Replaced by contextual "[+ Add Child]" popup |

> [!NOTE]
> All of the above remain fully functional in canvas mode. Nothing is removed.

## What This Retains (Both Modes)

| Retained | Rationale |
|----------|-----------|
| Node data model | `Node`, `NodeSettings`, `ChildSet` structs unchanged |
| Project persistence | `ProjectData` format unchanged |
| Engine/compiler | Zero changes — UI-only refactor |
| Preview API | Already returns all needed data (`CaptureInfo`, `Highlight`) |
| Timing API | Already returns per-node match counts |
| AI Chat | Can work with focused-node context |
| Bindings model | Var bindings shown as text in focused mode, wires in canvas mode |
| Canvas mode | Fully retained as a switchable view — evaluated for retirement later |
| Auto-layout algorithm | Retained for canvas view mode |

---

## Dual-Mode: Focused + Canvas

Both modes coexist. The canvas is **not** being retired — it remains fully functional alongside the new focused mode. A toggle in the toolbar switches between them:

| Mode | Icon | Best For |
|------|------|----------|
| **Focused** (default) | 📋 | Editing, building, debugging |
| **Canvas** | 🗺️ | Overview, presentation, complex data-flow visualization |

In focused mode, the canvas code is not rendered — no SVG, no pointer events, no layout calculations. This is a performance win for large graphs.

The canvas mode retains all existing functionality: pan, zoom, drag, wires, minimap, ChildSet containers, etc. We evaluate whether the canvas is still needed after the focused mode has been used in practice.

---

## Data→Node Click Flow (Detailed)

This is the killer feature of the redesign. Here's the full flow:

### Step 1: User pastes sample data

```
2019-01-01 00:00:00.000 4 Information Microsoft-Windows-Security...
```

### Step 2: User clicks "Run Preview" (or auto-run)

The engine returns `PreviewResponse` with:
- `highlights`: character ranges + colour indices (for the underlay)
- `captures`: per-node capture info with `node_id`, `offset`, `length`

### Step 3: Sample data panel renders highlights

Each highlight region gets a `data-node-id` attribute:

```html
<span class="hl-depth-3" data-node-id="abc-123"
      style="cursor:pointer" title="Regex: Event Parser">
  2019-01-01
</span>
```

### Step 4: User clicks "2019-01-01"

```rust
on_click = move |ev| {
    let node_id = ev.target.dataset().get("node_id");
    focused_node.set(Some(parse_uuid(node_id)));
};
```

### Step 5: UI updates

1. **Breadcrumb**: `🏠 Root › ✂️ Split (\n) › 📐 Regex (event parser)`
2. **Tree sidebar**: scrolls to and highlights the Regex node
3. **Focused panel**: shows Regex settings, captures, children
4. **Match preview**: shows `$1 = "2019-01-01"` highlighted
5. **Sample data**: re-colours to emphasize this node's matches (ancestors dimmed)

### Step 6: User edits the regex pattern

Changes `^(\d{4}-\d{2}-\d{2})` to `^(\d{4})-(\d{2})-(\d{2})` (separate year/month/day captures).

### Step 7: Auto-run triggers

- Highlights update in sample data
- Match preview updates to show 3 captures instead of 1
- Capture panel updates to show new groups
- User can immediately bind new captures to Vars

**Total interaction distance: zero scrolling, zero panning.** Everything happens within one viewport.

---

## Comparison With Current UI

| Workflow | Current (Canvas) | Proposed (Focused) |
|----------|------------------|---------------------|
| Find a specific node | Pan/zoom around canvas, scan visually | Type in tree search, or click data highlight |
| Edit a regex | Click node on canvas → edit in settings panel → pan to preview → run → pan back | Edit in focused panel → auto-run → see highlights update above |
| See what a regex matches | Run preview → scroll to bottom → read sample input highlights | Matches are right there in the focused panel + sample data at top |
| Add a child to a node | Drag from palette → connect → position | Click "[+ Add Child]" → select type → done |
| Reorder children | Drag ChildSet items or canvas elements | Drag rows in children list |
| Understand tree structure | Zoom out → scan canvas → follow wires | Glance at tree sidebar |
| Navigate deep tree (depth 12) | Pan through 12 levels of nested nodes | Click 12 times in tree, or click data highlight |

---

## Migration Strategy

### Phase 1: Focused Mode Shell

Add the new layout alongside the existing canvas:
- Sample data panel at top
- Tree sidebar on left
- Focused node panel in centre
- Toggle between focused/canvas modes

The canvas remains the default. Focused mode is opt-in.

### Phase 2: Tree Sidebar

- Render the full node tree from existing `nodes` + `child_sets`
- Click to navigate (set `focused_node`)
- Breadcrumb bar driven by `focused_node`

### Phase 3: Focused Node Panel

- Render node settings from existing `NodeSettings` infrastructure
- Children list from `child_sets`
- Inline editing for leaf nodes

### Phase 4: Data→Node Navigation

- Enhance `CaptureInfo` with `data-node-id` attributes on highlights
- Click handler maps highlight click → `focused_node` update
- Per-node match preview section

### Phase 5: Auto-Run and Live Feedback

- Debounced auto-run on settings change
- Focused highlights (dim ancestors, bright focused node)
- Match stepper (prev/next through matches)

### Phase 6: Polish

- Keyboard navigation (Alt+arrows, Backspace)
- Tree drag-to-reorder
- Contextual "Add Child" dropdown
- Collapse the canvas mode behind a toggle (focused becomes default)

---

## Resolved Questions

| Question | Decision |
|----------|----------|
| Canvas retirement or coexistence? | **Coexist.** Keep both modes. Evaluate canvas retirement after focused mode is proven in practice. |
| Leaf node editing inline or navigated? | **Inline to start.** All leaf editing is inline in the children list. Can change later if complex nodes (MapTransform, Choose) feel too cramped. |
| Captures vs children separation? | **Separate.** Bound Var nodes appear only in Captures/Bindings section, not duplicated in Children list. |
| Node type palette? | **Removed.** Replaced by contextual "[+ Add Child]" popup. No palette sidebar. |
| Tree sidebar depth limit? | **No limit for now.** Let it get deep. Refine later if needed. |

## Match Preview Granularity

The "Match 1 of 42 [◀ Prev] [Next ▶]" feature requires per-node individual match positions. This is confirmed as **preview-only** — the capture infrastructure is compiled out in production:

```rust
// engine.rs — normal parse() passes None for captures:
pub fn parse<R: Read, W: Write>(config, input, output) {
    parse_with_captures(config, input, output, None, None)  // no capture overhead
}

// Only preview mode passes a CaptureCollector:
pub fn run_preview(config, sample) {
    parse_with_captures(config, sample, output, Some(&collector), None)
}
```

The `CaptureCollector` is an `Option<&CaptureCollector>` parameter. When `None` (production), the recording code at each match site is a dead branch. The `CaptureRecord` struct already has a `match_index` field that tracks per-node match instance counts — the data is already being collected, it just needs to be surfaced through the API response.

The engine also skips dead-var elimination when in capture mode (`captures.is_some()` guard at engine.rs:213), ensuring all nodes are visible for preview even if they'd be optimised away in production.

> [!NOTE]
> **Auto-run performance**: For large sample data, auto-running on every keystroke could be expensive. A debounce of 500ms + a "manual run only" toggle for large datasets should suffice.

---

## Technical Notes

### Minimal Engine Changes

The focused UI is almost purely a rendering concern. The engine, compiler, decompiler, and project format are **unchanged**. The only enhancement is surfacing per-match-instance positions in the preview API response (the engine already collects this data via `CaptureRecord.match_index`).

All the data needed for the focused UI already exists:

- `CaptureRecord` → data-to-node navigation + per-match-instance stepping
- `Highlight` → sample data colouring
- `NodeTiming` → per-node match counts
- `ProjectData` → tree structure
- `NodeSettings` → node editing panels

### Estimated Effort

| Phase | Scope | Estimated Lines |
|-------|-------|----------------|
| 1. Shell + toggle | Layout, routing | ~300 |
| 2. Tree sidebar | Tree component, navigation signals | ~400 |
| 3. Focused node panel | Settings forms (reuse existing) | ~600 |
| 4. Data→node navigation | Click handlers, CaptureInfo wiring | ~200 |
| 5. Auto-run + match preview | Debounce, per-node match display | ~300 |
| 6. Polish | Keyboard nav, drag reorder, contextual add | ~400 |
| **Total new code** | | **~2,200** |

The existing canvas code (4,489 lines in main.rs) remains untouched until/unless it's deprecated.
