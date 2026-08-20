# Semantic Bindings — Remaining UI and Model Phases

## Purpose

This document captures the remaining design and implementation phases for the semantic binding migration in the DS3 node editor.

The semantic binding work changes the editor from a misleading “everything is an ordered child” model to a clearer model where:

- structural parse/output relationships are ordered children,
- expression capture storage is represented as semantic bindings,
- Var nodes are store/value nodes,
- Var reads are data-flow relationships,
- the engine still receives lowered executable `GraphNode.children`.

The core schema and lowering work is now complete. This document focuses on the remaining UI and editor-model phases needed to make the semantic model visible, editable, and eventually native in editor state.

---

## Current State

The system currently supports the following:

### Project schema

`ProjectData` now includes semantic bindings:

```text
ProjectData
  nodes: Vec<ProjectNode>
  child_sets: Vec<ProjectChildSet>
  bindings: Vec<ProjectBinding>
```

`ProjectBinding` represents relationships such as:

```text
Regex capture 1 -> Var eventID
```

### Saved format

The node editor now saves semantic v2 projects:

```text
version: 2
child_sets: structural children only
bindings: semantic capture/store bindings
nodes: includes Var nodes
```

### Engine lowering

`ProjectData::to_graph_state()` lowers v2 projects into executable engine children:

```text
GraphNode.children =
  binding target nodes first
  then structural childset children
  deduplicated
```

So this saved v2 model:

```text
Regex
  ChildSet: [Group]
  Binding: Regex -> Var
```

lowers to:

```text
Regex.children = [Var, Group]
```

### Editor load model

The editor currently hydrates v2 bindings back into in-memory childsets:

```text
Saved v2:
  ChildSet: [Group]
  Binding: Regex -> Var

Editor memory:
  ChildSet: [Var, Group]
```

This is a compatibility bridge. It lets existing selection, layout, capture display, and lowering code continue to work while the project file format is semantic.

### UI rendering

ChildSet UI now partitions hydrated children into:

```text
Output bindings
  Var eventID

Structural children
  Group Event
```

### Validation

The following have been validated:

```text
cargo test --workspace --quiet
cargo check --target=wasm32-unknown-unknown --manifest-path node-editor/Cargo.toml
```

Fixture projects have been migrated to semantic v2 and runtime output equivalence has been proven.

---

## Design Principles

### 1. Semantic project model, lowered engine model

The editor and project files should model user intent:

```text
capture -> Var
Var -> consumer
parent -> structural child
```

The engine should continue to receive executable children:

```text
expression.children = [binding actions, structural children]
```

### 2. Vars are store nodes, not normal ordered children

A capture-bound Var is an action attached to a producer expression. It is not an ordinary user-ordered child in the same way as a Group, Data, Regex, or Choose node.

### 3. Structural child ordering remains explicit

Order is still important for structural/output nodes, especially:

- Group output order,
- Data-with-children,
- Choose/Switch/If branches,
- ForEach output,
- combinators.

### 4. UI should reveal data dependencies

The editor should make the following visible:

```text
Regex capture -> Var store
Var store -> Group/Data/Choose/ForEach read
```

These are distinct from structural execution relationships.

### 5. Migrate incrementally

The current hydration bridge should remain until the UI can work directly with `bindings` as first-class editor state.

---

# Remaining Phases

---

## Phase 1 — Binding Var Styling

### Goal

Make capture-bound Var nodes visually distinct from ordinary nodes.

Currently, binding Vars are listed under “Output bindings” in ChildSets, but the Var node itself still looks like a normal node on the canvas.

### Desired behaviour

If a Var node is the target of a semantic binding or is classified as a capture-bound store child, render it with a binding-specific visual treatment.

Example:

```text
┌────────────────────┐
│ Var: eventID       │
│ binding            │
│ from Regex capture │
└────────────────────┘
```

### UI changes

Add a CSS class to binding Var nodes, for example:

```text
.binding-var-node
```

Potential styling:

- subtle purple/amber border,
- small “binding” badge in header,
- reduced visual weight compared with structural nodes,
- optional source hint in node body.

### Detection

During node rendering, determine whether a node is a binding target.

Initial implementation can derive this from hydrated in-memory childsets:

```text
For each parent expression:
  partition childset children
  binding children -> binding target set
```

Later implementation should use first-class editor `bindings`.

### Data changes

None.

### Tests

Add unit tests for a helper such as:

```text
derive_binding_target_node_ids(nodes, child_sets) -> HashSet<NodeId>
```

Expected cases:

```text
Regex -> Var(capture_ref) -> binding target
Regex -> Group -> not binding target
Group -> Var(capture_ref) -> not binding target unless classified as expression-source binding
```

### Acceptance criteria

- Capture-bound Vars have a distinct visual class.
- Non-binding Vars remain unchanged.
- No engine or saved project changes.

---

## Phase 2 — Binding Wires Layer

### Goal

Draw explicit capture/store binding wires from producer expressions to Var nodes.

This is separate from:

- structural wires: parent -> ChildSet,
- data-flow wires: Var -> consumer.

### Desired visual model

```text
Regex capture/output ──▶ Var eventID
```

Canvas example:

```text
┌──────────── Regex ────────────┐
│ captures                     │
│ 1 Event ID ──────────────┐    │
└──────────────────────────┼────┘
                           ▼
                    ┌─────────────┐
                    │ Var eventID │
                    └─────────────┘
```

### New component

Add a new layer:

```text
BindingWiresLayer
```

Suggested render order:

```text
NodesLayer
ConnectionsLayer / existing empty layer
ChildSetsLayer
StructuralWiresLayer
BindingWiresLayer
DataFlowWiresLayer
```

Or, preferably:

```text
StructuralWiresLayer
BindingWiresLayer
DataFlowWiresLayer
```

with all SVG layers under nodes if wires should sit behind nodes.

### Wire derivation

Initial implementation:

```text
derive_binding_edges(nodes, child_sets) -> Vec<BindingEdge>
```

Where:

```text
BindingEdge {
  source_node_id: NodeId,
  target_node_id: NodeId,
  source_label: Option<String>
}
```

Use existing semantic partitioning:

```text
for each parent with childset:
  bindings, _ = partition_semantic_children(parent, childset.children, nodes)
  for binding target:
    edge source = parent.id
    edge target = binding target
```

Later implementation should derive from editor `bindings`.

### Anchor points

Initial anchors can be approximate:

```text
source:
  right edge of expression node, vertical centre

target:
  left edge of Var node, vertical centre
```

Better anchors later:

```text
source:
  capture row location within capture panel

target:
  Var node left-side input anchor
```

### Styling

Suggested style:

```text
.binding-wire {
  stroke: rgba(160, 120, 240, 0.5);
  stroke-width: 1.5px;
  stroke-dasharray: 4 3;
}
```

Hover:

- highlight source expression,
- highlight target Var,
- show capture/var label.

### Tests

Unit test `derive_binding_edges()`.

### Acceptance criteria

- Binding wires render from expression nodes to Var nodes.
- Binding wires are visually distinct from structural and data-flow wires.
- No project schema changes.

---

## Phase 3 — Capture Panel Binding Targets

### Goal

Make capture-to-Var bindings visible in the producing expression’s capture panel.

Currently, captures can show referenced/unreferenced state, but the UI should show which Var each capture is stored into.

### Desired UI

For Regex:

```text
Captures
  0 Entire Match
  1 Event ID        -> Var eventID
  2 Provider Name   -> Var providerName
  3 Level           unused
```

### Required helper

Add a capture binding lookup:

```text
derive_capture_bindings_for_node(parent, nodes, child_sets)
  -> Vec<CaptureBindingInfo>
```

Example:

```text
CaptureBindingInfo {
  capture_ref: String,
  target_node_id: NodeId,
  target_name: String,
}
```

### Display behaviour

For each capture row:

- if bound to Var, show target Var name,
- if multiple bindings, show all target Vars,
- if unused, show existing unused marker.

### Interactions

Initial:

- click target Var name -> select and pan to Var node.

Later:

- add “Store as Var” button,
- remove binding,
- rename binding target.

### Acceptance criteria

- Capture panel shows the specific Var targets for capture-bound Vars.
- Clicking a Var target focuses/selects that Var.
- Existing referenced/unreferenced display remains.

---

## Phase 4 — Group Phase Sections

### Goal

Make Group execution phases visible.

Groups are special because they can run expression/extraction children first and output children second.

### Desired UI

```text
Group Event

Input
  value: Var eventContent

Extraction phase
  1 Regex Provider
  2 Regex EventID
  3 Regex Level

Output phase
  1 Data EventOpen
  2 Data Provider
  3 Data EventID
```

### Classification

Add a child phase classifier:

```text
GroupChildPhase:
  Binding
  Extraction
  Output
```

Rules:

```text
Capture-bound Var -> Binding
Expression node -> Extraction
Data/control/output node -> Output
```

Expression node means:

- Regex
- Split
- All
- matcher atoms
- combinators

Output/control node means:

- Data
- Group
- ForEach
- If
- Choose
- Switch
- ValueMap
- structure nodes

Need careful handling:

- A nested Group under a Group may be structural/output depending on context.
- A Group used as extraction-only could be rare but possible.
- Conservative default: non-expression = Output.

### UI changes

In `ChildSetsLayer`, if parent node is `group`, use section labels:

```text
Bindings
Extraction phase
Output phase
```

For non-Group expression parents, keep:

```text
Output bindings
Structural children
```

### Acceptance criteria

- Group childsets are split into extraction/output phases.
- Existing ordering is preserved within each phase.
- Capture-bound Vars are still shown as bindings.
- No engine behaviour changes.

---

## Phase 5 — Group Input Display

### Goal

Show which Vars or captures feed a Group’s `value_mode`.

### Current issue

Groups consume values, often from Vars, but this is not obvious in the UI.

Example backend/project meaning:

```text
Group value = Var eventContent
```

Desired UI:

```text
Group Event
  Input: eventContent
```

### Implementation

Use existing `NodeSettings::Group { value_mode, ... }`.

For `ValueMode::CaptureRef`:

```text
Input: capture <label>
```

For `ValueMode::Composite` containing var parts:

```text
Input: Var eventContent
```

For `ValueMode::Text`:

```text
Input: literal/ref text
```

For `ValueMode::None`:

```text
Input: parent match
```

### Data-flow integration

Existing `DataFlowWiresLayer` already derives some Var reads from `parts`. Ensure Group value refs are included.

If Group references a Var, draw:

```text
Var eventContent ──▶ Group Event
```

### Acceptance criteria

- Group nodes visibly show their input source.
- Var-to-Group wires are drawn for Var-based group values.
- Capture-based values show capture labels.

---

## Phase 6 — First-Class Editor Bindings State

### Goal

Remove the need to hydrate bindings into childsets internally.

The editor should store semantic bindings natively.

Current in-memory editor state:

```text
nodes
child_sets hydrated with binding Vars
```

Desired in-memory editor state:

```text
nodes
child_sets structural-only
bindings
```

### State changes

Add:

```text
let bindings = RwSignal<Vec<ProjectBinding>>::new(...)
```

Update `from_project_data()` or introduce a new struct:

```text
EditorProject {
  nodes
  groups
  child_sets
  bindings
  camera
  sample_data
}
```

This may be preferable to continuously expanding tuple return values.

### Loading

For v2:

```text
nodes = data.nodes
child_sets = data.child_sets
bindings = data.bindings
```

For legacy v1/v1.5:

```text
bindings = extract_semantic_project_bindings(nodes, child_sets)
child_sets = migrate_childsets_to_structural_only(nodes, child_sets)
```

### Saving

`to_project_data_v2()` becomes straightforward:

```text
ProjectData {
  nodes
  child_sets
  bindings
}
```

No extraction/hydration required.

### Lowering

Update editor lowering:

```text
lower_to_graph_state(nodes, child_sets, bindings, options)
```

instead of deriving bindings from hydrated childsets.

### UI components

Update props:

```text
ChildSetsLayer:
  child_sets
  bindings
  nodes

BindingWiresLayer:
  bindings
  nodes

DataFlowWiresLayer:
  nodes
```

### Acceptance criteria

- Editor state no longer inserts binding Vars into ChildSet children.
- ChildSet editing only edits structural children.
- Bindings are independently editable.
- Save/load round-trips v2 without hydration.

---

## Phase 7 — Binding Editing Interactions

### Goal

Make semantic bindings editable in the UI.

### Required interactions

#### Add binding

From capture panel:

```text
Capture row -> “Store as Var”
```

Behaviour:

1. create Var node,
2. create ProjectBinding,
3. position Var near source expression,
4. select Var for editing.

#### Remove binding

From capture panel or Var node:

```text
Remove binding
```

Options:

- remove only binding,
- remove binding and Var if unused.

Need confirmation if Var has readers.

#### Reassign binding source

Allow changing:

```text
Var eventID from capture 1 -> capture 2
```

#### Rename Var

Rename Var ID/display name.

This must update:

- Var node settings,
- display name,
- downstream refs should remain UUID-based and therefore safe.

### Acceptance criteria

- Users can create a Var from a capture without editing raw child lists.
- Users can remove bindings safely.
- Removing a binding does not accidentally remove structural children.
- Var read refs remain stable.

---

## Phase 8 — Data-Flow Improvements

### Goal

Improve Var read visibility.

Current data-flow wires exist but should become more integrated with semantic bindings.

### Improvements

#### Hover behaviour

Hover Var:

- highlight producer binding wire,
- highlight all consumer data-flow wires,
- highlight consuming nodes.

Hover consumer:

- highlight inbound Vars.

#### Labels

Data-flow wire hover label:

```text
eventID
```

Binding wire hover label:

```text
Regex Group 1 -> eventID
```

#### Click behaviour

Click wire:

- select source and target,
- show details in side panel or inline tooltip.

### Acceptance criteria

- It is easy to trace capture -> Var -> output.
- Users can inspect dependencies visually.

---

## Phase 9 — Layout Updates

### Goal

Auto-layout should account for semantic bindings.

Current layout uses children relationships. Hydrated bindings may currently influence layout; once first-class bindings state is used, layout needs explicit awareness.

### Desired layout

For expression with bindings:

```text
Regex
  Var bindings close to the right
  structural children further right/down
```

Possible layout:

```text
Regex ---- Binding Vars column ---- Structural children column
```

Example:

```text
Regex at x=0
Vars at x=260
Groups/Data at x=420
```

### Rules

- Binding Vars should not push structural tree depth as strongly as structural children.
- Binding Vars can stack vertically beside the producer.
- Structural children keep existing tree layout.
- Data-flow wires may cross, but should be readable.

### Implementation stages

#### Stage 1

Post-process binding Var positions after existing auto-layout:

```text
for each binding:
  target_var.x = source.x + NODE_WIDTH + 40
  target_var.y = source.y + binding_index * small_gap
```

#### Stage 2

Integrate binding columns into layout algorithm.

### Acceptance criteria

- Binding Vars visually sit near producing expressions.
- Structural children remain readable.
- Large fixtures do not become more cluttered.

---

## Phase 10 — Remove Legacy ChildSet Binding Compatibility

### Goal

Once first-class binding state is complete and fixtures are v2, remove legacy assumptions.

### Remove or simplify

- Hydration of bindings into childsets.
- Derivation of bindings from childsets in normal save path.
- Legacy `to_project_data()` serializer.
- Migration-only helpers if no longer needed.
- Any UI path that allows reordering capture-bound Vars as structural child rows.

### Keep if useful

- One ignored migration test for old external projects.
- `ProjectData::to_graph_state()` deduplication for robustness.

### Acceptance criteria

- Editor internal model matches project model.
- ChildSets are structural-only everywhere.
- Bindings are first-class everywhere.
- Engine lowering remains stable.

---

# Suggested Implementation Order

## Near-term sequence

```text
1. Binding Var styling
2. BindingWiresLayer
3. Capture panel target display
4. Group phase sections
5. Group input display
```

These give immediate UI clarity without major state refactors.

## Medium-term sequence

```text
6. First-class editor bindings state
7. Binding editing interactions
8. Data-flow hover/click improvements
```

This completes the semantic editing model.

## Long-term sequence

```text
9. Layout updates
10. Remove legacy compatibility/hydration
```

This simplifies the model after the UI is stable.

---

# Testing Strategy

## Unit tests

Add or keep tests for:

```text
semantic child classification
binding extraction
binding hydration
structural-only migration
ProjectData::to_graph_state lowering
binding edge derivation
binding target derivation
group phase classification
```

## Fixture tests

Continue to run:

```text
cargo test --package datasplitter-rs --test project_tests
```

Key tests:

```text
test_all_fixture_projects
fixture_semantic_v2_migration_preserves_runtime_output
```

## Workspace validation

Before merging significant changes:

```text
cargo test --workspace --quiet
cargo check --target=wasm32-unknown-unknown --manifest-path node-editor/Cargo.toml
```

## Visual verification

Manual checks should cover:

- `win_app_xml`
- `apache_httpd`
- `ausearch`
- `json_to_xml`
- `xml_to_json`

Things to verify:

```text
binding Vars are visually distinct
binding wires connect producer -> Var
data-flow wires connect Var -> consumer
Group childsets show extraction/output phases
saved project JSON remains v2
preview output remains unchanged
```

---

# Open Questions

## Should binding Vars be draggable?

Likely yes, but with layout assist.

Options:

```text
A. User can freely place Var boxes.
B. Var boxes auto-stick near producer unless manually pinned.
C. Var boxes live inside producer node visually, not as canvas nodes.
```

Recommended initial approach: allow dragging and persist position, but auto-layout places them near producer.

## Should bindings support transforms directly?

Currently transforms are separate nodes. We should avoid embedding transforms in bindings until the transform model is clearer.

Recommended:

```text
Capture -> Var
Var -> Transform
Transform -> Var/Data
```

or:

```text
Capture -> TransformOutput(target_var)
```

Only classify transform output bindings later.

## Should unused bindings be omitted in normal preview?

Eventually yes.

Current default includes unused bindings to preserve inspection/debug behaviour.

Future:

```text
Preview/debug: include all bindings
Run/export: include used bindings only
```

Need careful treatment of dynamic refs.

## Should old v1 projects remain loadable?

Yes. `serde(default)` and migration helpers should remain for a while.

---

# Final Target

The final editor model should feel like this:

```text
Source
  structural child -> Split

Split
  structural child -> Group

Regex
  captures:
    Group 1 -> Var eventID
    Group 2 -> Var provider

Var eventID
  produced by Regex capture
  consumed by Data EventID and Choose condition

Group
  input: Var eventContent
  extraction phase:
    Regex Provider
    Regex EventID
  output phase:
    Data Event
    Data Provider
    Data EventID
```

While the engine still receives:

```text
children = binding actions + structural children
```

This preserves engine simplicity and makes the editor reflect the real user-facing semantics.