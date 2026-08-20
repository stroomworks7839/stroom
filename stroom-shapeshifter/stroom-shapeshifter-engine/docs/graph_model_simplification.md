# Graph Model Simplification: Connections, UUIDs, and Capture Bindings

This plan covers three related simplifications to the project data model, replacing the text-based reference system with fully visual, UUID-based capture bindings.

---

## 1. Remove Connections & Ports — Use Children Only

### Current State

The `project.json` format stores `children`, `connections`, and `ports`. The compiler only reads `children` — connections and ports are 100% redundant.

### Proposed Change

**Remove connections and ports from the data model entirely.**

- Children array is the sole structural mechanism
- UI displays children as an ordered list in each node's panel
- Visual wires inferred from parent→child relationships
- Port types inferred from `(parent_type, child_type)` lookup table

### Files to Modify

#### [MODIFY] [types.rs](file:///home/jon/work/ds-rs/shared/src/types.rs)
- Remove `GraphConnection`, `GraphPort`, `ProjectConnection`, `ProjectPort` structs
- Remove `connections` from `GraphState` and `ProjectData`
- Remove `ports` from `GraphNode` and `ProjectNode`
- Simplify `to_graph_state()`

#### [MODIFY] [compiler.rs](file:///home/jon/work/ds-rs/engine/src/compiler.rs)
- Remove `_connections` parameter from `GraphCompiler::new()`
- Remove `GraphConnection` import
- Update tests

#### [MODIFY] [decompiler.rs](file:///home/jon/work/ds-rs/engine/src/decompiler.rs)
- Remove `connections` field from `DecompileContext`
- Remove connection/port generation in `decompile_children()`
- Update tests

#### [MODIFY] All 11 project.json fixtures
- Strip `connections` arrays
- Strip `ports` arrays from all nodes

---

## 2. UUID Node IDs

### Proposed Change

All node IDs become UUID v4. The `title` field provides human-readable display. Node IDs are purely internal — never user-visible.

### Files to Modify

- [decompiler.rs](file:///home/jon/work/ds-rs/engine/src/decompiler.rs) — `Uuid::new_v4().to_string()`
- [migration.rs](file:///home/jon/work/ds-rs/engine/src/migration.rs) — UUIDs for migrated nodes
- All 11 project.json fixtures — replace all IDs, update children arrays
- Add `uuid` crate to `engine/Cargo.toml`

---

## 3. Structured Capture Bindings

### Design Principle

**Everything is UUID-based. No text-based reference syntax in graph-mode configs.**

The UI resolves UUIDs to human-readable names for display. The `$N` / `$varId$N` / `@var` text syntax is eliminated entirely for graph-mode. It remains only as a serialisation format for legacy DS3 XML migration.

### 3.1 Expression Nodes Expose Named Captures

Every expression node (Regex, Split, All, etc.) has a `captures` array that names its output groups:

```json
{
    "id": "b7f3a1...",
    "node_type": "regex",
    "title": "Log Parser",
    "settings": {
        "pattern": "^(\\S+) (\\S+) \\[(.+)\\]",
        "captures": [
            {"id": "c001-...", "name": "Entire Match", "group": 0},
            {"id": "c002-...", "name": "IP Address", "group": 1},
            {"id": "c003-...", "name": "Ident", "group": 2},
            {"id": "c004-...", "name": "DateTime", "group": 3}
        ]
    },
    "children": ["uuid-var-ip", "uuid-data-out"]
}
```

> [!TIP]
> The UI auto-detects group count from the regex pattern and pre-populates the captures list with default names ("Group 1", "Group 2", ...). The user renames them.

### 3.2 Consumer Nodes Reference Captures by UUID

**Simple case — single capture output:**

```json
{
    "id": "uuid-data-ip",
    "node_type": "data",
    "settings": {
        "capture_ref": "c002-..."
    }
}
```

The UI displays this as: `Data: IP Address (from: Log Parser)`

**Composite case — structured `parts` array (replaces `value_ref` strings):**

Instead of `value_ref: "'<'+$tag$0+'>'+$val$0+'</'+$tag$0+'>'`, the Data node has:

```json
{
    "id": "uuid-data-xml",
    "node_type": "data",
    "settings": {
        "parts": [
            {"type": "text", "value": "<"},
            {"type": "var", "ref": "uuid-var-tag"},
            {"type": "text", "value": ">"},
            {"type": "var", "ref": "uuid-var-val"},
            {"type": "text", "value": "</"},
            {"type": "var", "ref": "uuid-var-tag"},
            {"type": "text", "value": ">"}
        ]
    }
}
```

Each `ref` is the UUID of a Var node. The UI resolves UUIDs to display names:

```
Data: XML Element
  parts: [ "<" | Tag ▸ | ">" | Value ▸ | "</" | Tag ▸ | ">" ]
```

> [!IMPORTANT]
> This eliminates the need for `@` syntax, `$` syntax, and the entire `parse_ref()` text parser for graph-mode configs. The reference is structural, not textual.

### 3.3 Part Types

| Part Type | JSON | Meaning |
|---|---|---|
| `text` | `{"type": "text", "value": "literal"}` | Static text |
| `capture` | `{"type": "capture", "ref": "uuid"}` | Direct capture from parent expression |
| `var` | `{"type": "var", "ref": "uuid"}` | Cross-scope variable (by Var node UUID) |
| `var` (indexed) | `{"type": "var", "ref": "uuid", "index": N}` | Specific match position from a multi-valued var |
| `var` (dynamic) | `{"type": "var", "ref": "uuid", "index": "current"}` | Var at ForEach iteration position |

### 3.4 Var Node — Persist + Name for Cross-Scope Only

With this model, Var becomes a thin persistence layer:

```json
{
    "id": "uuid-var-tag",
    "node_type": "var",
    "title": "Tag Name",
    "settings": {
        "name": "tag",
        "capture_ref": "c001-..."
    }
}
```

- `name` — human-readable identifier (for display and legacy compat)
- `capture_ref` — which capture to store (from parent expression)
- **No transforms on Var** — transforms are separate nodes

Sibling/cousin Data nodes reference by Var UUID:
```json
{"type": "var", "ref": "uuid-var-tag"}
```

The UI shows the Var's `title` instead of the UUID.

### 3.5 Transform — Separate Node

Transforms are already a separate `TransformOutput` node type. With this change:

- TransformOutput takes `capture_ref` (from parent) or `var_ref` (from Var) as input
- Outputs a transformed value that can itself be captured by a Var or consumed by Data
- Var stays clean: capture + persist, nothing else

```json
{
    "id": "uuid-transform-date",
    "node_type": "transform_output",
    "title": "Format Date",
    "settings": {
        "capture_ref": "c004-...",
        "transform": {"type": "replace", "pattern": "/", "replacement": "-"}
    }
}
```

### 3.6 ForEach Iteration Variable

ForEach exposes its iteration index as a special capture:

```json
{
    "id": "uuid-foreach",
    "node_type": "for_each",
    "title": "Each Field",
    "settings": {
        "var_ref": "uuid-var-fields",
        "captures": [
            {"id": "fe-idx-...", "name": "Index", "source": "index"},
            {"id": "fe-val-...", "name": "Value", "source": "value"}
        ]
    },
    "children": [...]
}
```

The `source` field distinguishes special captures:
- `"source": "index"` — the iteration counter (0, 1, 2, ...)
- `"source": "value"` — the current iteration value (the matched group)

Children reference these by UUID like any other capture.

### 3.7 Positional Group Access (CSV Headings)

For data-driven patterns where the group index is dynamic, the `var` part type has an optional `index` field:

```json
{"type": "var", "ref": "uuid-var-headings", "index": "current"}
```

The `index` field accepts:
- **`"current"`** — use the current ForEach iteration index (most common)
- **integer** — absolute match position (e.g. `3` for the 4th match)
- **absent** — default: use current match_count (same as today's implicit behaviour)

This resolves to: "get the value from var `headings` at the specified position". It's the structural equivalent of the old `$heading$1[+0]` syntax.

The engine compiles this to the existing `RefPart::Store { var_id, group, match_index }` machinery — no hot-path change needed.

### 3.8 Reference Resolution Hierarchy

| Situation | Graph-Mode Mechanism | Compiles To |
|---|---|---|
| Data outputs parent's capture group | `capture_ref: "uuid"` | `RefExpression(SimpleLocal(N))` |
| Data references cross-scope var | `parts: [{"type": "var", "ref": "uuid"}]` | `RefExpression(SimpleRemote { var_id, 0 })` |
| Composite output | `parts: [text + capture + var ...]` | `RefExpression(Complex)` with parts |
| Dynamic positional (CSV headings) | `{"type": "var", "ref": "uuid", "index": "current"}` | `RefPart::Store { match_index: offset(0) }` |
| ForEach iteration index | `capture_ref: "fe-idx-uuid"` | Iteration counter access |
| Legacy DS3 XML migration | `$N`, `$varId$N` text syntax | Parsed by `parse_ref()` as today |

### 3.9 What Gets Eliminated

| Removed | Replaced By |
|---|---|
| `$0`, `$1`, `$N` in value_ref | `capture_ref: "uuid"` |
| `$varId$0`, `$varId$N` | `{"type": "var", "ref": "uuid"}` |
| `@var` syntax (not needed) | UUID-based var refs |
| `'text'+$var+'text'` string expressions | `parts: [...]` structured array |
| `parse_ref()` for graph-mode | Compile-time UUID→index resolution |
| Var `id` as variable name | Var `name` field + UUID |
| Transforms on Var node | Separate TransformOutput nodes |

---

## 4. Migration Script

A script to update existing project.json files:

1. **Generate UUIDs** for all node IDs → update children arrays
2. **Remove connections and ports arrays**
3. **Add `captures` arrays** to Regex/Split nodes (scan pattern for group count)
4. **Generate capture UUIDs** for each group
5. **Replace `value_ref: "$N"`** on Data/Var with `capture_ref: "capture-uuid"` (resolve N → parent's capture)
6. **Replace composite `value_ref`** strings with structured `parts` arrays
7. **Extract Var names** from current `id` fields → add `name` to settings
8. **Remove transforms from Var** → create separate TransformOutput children where needed
9. **Verify output unchanged** — run all fixture tests

---

## 5. Files to Modify — Complete List

### Shared Types
#### [MODIFY] [types.rs](file:///home/jon/work/ds-rs/shared/src/types.rs)
- Remove: `GraphConnection`, `GraphPort`, `ProjectConnection`, `ProjectPort`
- Remove: `connections` from `GraphState`/`ProjectData`, `ports` from nodes
- Add: `CaptureDefinition { id: String, name: String, group: usize, source: Option<String> }`
- Add: `OutputPart` enum for structured parts: `Text { value }`, `Capture { ref }`, `Var { ref, index? }`

### Engine Core
#### [MODIFY] [node.rs](file:///home/jon/work/ds-rs/engine/src/node.rs)
- Add `name: String` to `NodeConfig::Var`
- Add `captures: Vec<CaptureDefinition>` to Regex, Split, All, ForEach
- Remove `transforms` from `NodeConfig::Var`

#### [MODIFY] [compiler.rs](file:///home/jon/work/ds-rs/engine/src/compiler.rs)
- Remove connections handling
- Resolve `capture_ref` → positional group index at compile time
- Compile `parts` arrays into `RefExpression`
- Read `name` from Var settings

#### [MODIFY] [refs.rs](file:///home/jon/work/ds-rs/engine/src/refs.rs)
- Keep `parse_ref()` for legacy DS3 XML migration only
- Add `compile_parts()` — converts structured parts array to `RefExpression`

#### [MODIFY] [engine.rs](file:///home/jon/work/ds-rs/engine/src/engine.rs)
- `register_vars` uses `name` instead of `id`
- No hot-path changes — `resolve_ref` works on compiled `RefExpression` as before

#### [MODIFY] [decompiler.rs](file:///home/jon/work/ds-rs/engine/src/decompiler.rs)
- Remove connections/ports generation
- Emit `captures` arrays on expression nodes
- Emit `capture_ref`/`parts` on consumer nodes
- Emit `name` on Var nodes

#### [MODIFY] [migration.rs](file:///home/jon/work/ds-rs/engine/src/migration.rs)
- Generate UUIDs, capture UUIDs
- Convert legacy refs to structured format

### Test Fixtures
#### [MODIFY] All 11 project.json fixtures
- Full migration to new format

#### [MODIFY] Internal tests
- `new_features_test.rs`, `ds3_tests.rs`, `compiler.rs` tests — any inline `value_ref: "$N"` style references migrated to `capture_ref`/`parts`
- Decompiler tests updated to expect new format

---

## Decisions (Resolved)

| Decision | Resolution |
|---|---|
| Dynamic positional index | Optional `index` field on `var` parts — `"current"` for ForEach iteration, integer for absolute, absent for default |
| Backwards compatibility | Clean break — migrate all project.json fixtures and internal tests at once. No dual-format support. `parse_ref()` retained only for legacy DS3 XML migration. |
| Variable reference syntax | No `@` syntax needed — everything is UUID-based, UI resolves display names |
| `capture_ref` vs `$N` | `capture_ref` only for graph-mode. `$N` solely for legacy DS3 XML migration. |
| Composite expressions | Structured `parts` arrays, not text expressions |
| Var and Transform | Separate concepts — Var = persist + name, Transform = separate node |

---

## Verification Plan

### Automated Tests
- `cargo test -p datasplitter-rs` — all 251+ tests pass
- Fixture projects produce identical output (behavior unchanged)
- Decompiler round-trip: `NodeConfig → ProjectData → compile_graph → NodeConfig`
- Legacy DS3 XML migration still works (uses `parse_ref()` internally)

### Manual Verification
- Inspect project.json fixture diffs for correctness
- Verify migration script handles all 11 projects correctly
- Verify no `$N` or `$varId$N` syntax remains in any graph-mode fixture
