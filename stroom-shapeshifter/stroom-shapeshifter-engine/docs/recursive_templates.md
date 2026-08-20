# Recursive Templates & Depth-Aware Branching

## Problem Statement

The pipeline stack UI has no support for `NodeConfig::Recursive` — the engine's XSLT apply-templates equivalent. The engine fully supports recursive processing, template registries, and scoped variables at runtime. The gap is in the editor model and UI.

---

## Core Design: Context Variables + Named Captures + Params

We avoid building a DOM by combining three mechanisms:

### 1. Context Variables (`__depth`, `__max_depth`, `__mode`)

The engine injects synthetic variables into each recursion scope. These let conditions branch on depth without XPath:

```
Instead of:  <xsl:if test="count(ancestor::section) > 2">
We write:    If { condition: GreaterThan { ref_expr: var_ref("__depth"), value: 2.0 } }
```

| Context Variable | Type | Description | Compiled out if unused? |
|----------|------|-------------|:-:|
| `__depth` | usize→String | Current recursion depth (0 at first call) | Yes |
| `__max_depth` | usize→String | Configured max_depth limit | Yes |
| `__mode` | String | The `mode` field of the executing template | Yes |

> [!NOTE]
> `__parent_match` (full text of parent's match) is useful for debugging but potentially large. It is **only injected when capture collection is enabled** (UI preview mode). In production parsing it is optimised out entirely.

### 2. Named Captures Per Depth (Primary Ancestor Access)

For fixed-depth hierarchies where you know the structure, users define differently-named Var nodes at each depth using Choose/If on `__depth`:

```
Recursive(id="processDoc"):
  Regex: <(\w+)[^>]*>(.*?)</\1>
  
  Choose:
    When(__depth == 0):
      Var: root_tag = $1          # "html" — captured at depth 0
      Recursive(content=$2)
    When(__depth == 1):
      Var: section_tag = $1       # "body" — captured at depth 1
      Recursive(content=$2)
    Otherwise:
      # Depth 2+ can read root_tag AND section_tag from ancestor scopes
      # No special syntax — ScopedVarRegistry.get() searches upward
      Data: @root_tag + "/" + @section_tag + "/" + $1
```

This works because `ScopedVarRegistry::get()` searches scopes from current (top) to global (bottom). Variables set in ancestor scopes are naturally visible to descendants — unless shadowed by the same name in a closer scope.

**Works well for**: Fixed-depth hierarchies, nearest-ancestor access, depth-conditional formatting.

### 3. Params on Recursive Call-Sites (Accumulator Pattern)

For variable-depth recursion where the same template runs at every level, named captures per depth break down — you'd need N `When` branches for N possible depths. The solution is **explicit parameter passing**, analogous to XSLT's `<xsl:with-param>`:

```
Recursive(id="buildPath"):
  Regex: <(\w+)>(.*?)</\1>
  
  # path is a param, passed from parent, defaults to ""
  TransformOutput(Concat): inputs=[@path, "/", $1], target_var="current_path"
  Data: @current_path
  
  # Pass accumulated path DOWN to child invocation
  Recursive(template_ref="buildPath", content=$2, params={
    path: @current_path
  })
```

**Engine implementation**: After `push_scope()` but **before** shadow-registration, inject params as pre-populated stores in the new scope. This means:
1. Parent computes param value (e.g. `@path + "/" + $1`) using its own scope
2. Child scope receives it as an initial value for `path`
3. Child's own Var/TransformOutput can extend it further
4. Shadow-registration finds `path` already exists in current scope, doesn't create empty entry

This solves the shadow-registration blocking problem: without params, `get("path")` at depth 2 finds the empty shadow entry and returns nothing. With params, the entry is pre-populated with the parent's accumulated value.

### Pattern Coverage Summary

| Pattern | Named vars | + __depth | + Params |
|---------|:-:|:-:|:-:|
| Fixed-depth hierarchy | ✅ | ✅ | ✅ |
| Nearest-ancestor access | ✅ | ✅ | ✅ |
| Depth-conditional format | ❌ | ✅ | ✅ |
| Path accumulation | ❌ | ❌ | ✅ |
| Cross-depth aggregation | ❌ | ❌ | ✅ |
| Variable-depth same-element | ❌ | ⚠️ | ✅ |

---

## Concrete Example: Nested XML → JSON

Input:
```xml
<root><a><b>hello</b><c><d>world</d></c></a></root>
```

Template definition:
```
Recursive(id="xmlToJson"):
  Regex: <(\w+)[^>]*>(.*?)</\1>     # $1=tag, $2=content
  
  Var: rtag = $1
  Var: rval = $2
  
  Data: "\"" + @rtag + "\":"
  Choose:
    When(Matches { @rval, "<" }):   # content has child elements
      Data: "{"
      Recursive(template_ref="xmlToJson", content_ref=@rval)
      Data: "}"
    Otherwise:                      # leaf text
      Data: "\"" + @rval + "\""
```

This is exactly how the existing [xml_to_json fixture](file:///home/jon/work/ds-rs/engine/tests/fixtures/projects/xml_to_json/project.json) works. At each depth, the regex matches elements, the Choose branches on whether the content contains more XML, and the call-site recurses into child content.

Pipeline stack visualization:
```
LAYER N: Recursive "xmlToJson"
┌──────────────┬──────────────────────────┬─────────────────────────┐
│  ● Recursive │  Depth: ◀ [0/3] ▶       │  SETTINGS               │
│    xmlToJson │  Match: ◀ [1/2] ▶       │  Name: xmlToJson        │
│              │                          │  Content: @rval         │
│              │  <a>                     │  Max Depth: 64          │
│              │    <b>hello</b>          │  Mode: ______           │
│              │    <c><d>world</d></c>   │  Template: (self)       │
│              │  </a>                    │  Params: (none)         │
│              │                          │  ─────────              │
│              │                          │  __depth = 0            │
│              │                          │  __max_depth = 64       │
│              │                          │  rtag = "a"             │
│              │                          │  rval = "<b>hello..."   │
└──────────────┴──────────────────────────┴─────────────────────────┘

LAYER N+1: Children of xmlToJson
┌──────────────┬──────────────────────────┬─────────────────────────┐
│  ○ Regex     │  ◀ [1/2] ▶              │  SETTINGS               │
│  ○ Var rtag  │  <b>hello</b>           │  ...                    │
│  ○ Var rval  │                          │                         │
│  ○ Data      │  (Choose selected)       │  BRANCHES               │
│  ● Choose    │  → Branch 1 active       │  @rval matches "<": ✗   │
│  ○ Recursive │                          │  Otherwise: ● active    │
│    ↻→ call   │                          │  ─────────              │
│              │                          │  __depth = 0            │
│              │                          │  rtag = "b"             │
└──────────────┴──────────────────────────┴─────────────────────────┘
```

Stepping **Depth** from `[0/3]` → `[1/3]` updates all content to show depth 1's matches. Stepping **Match** within a depth navigates between sibling matches at that depth.

---

## Proposed Changes

### A. Engine Changes

#### [MODIFY] [node.rs](file:///home/jon/work/ds-rs/engine/src/node.rs)

Add `params` field to `NodeConfig::Recursive`:

```rust
Recursive {
    id: Option<String>,
    content_ref: Option<RefExpression>,
    max_depth: usize,
    mode: Option<String>,
    template_ref: Option<String>,
    record_header: Option<String>,
    record_footer: Option<String>,
    /// Explicit parameters passed to the child scope (like xsl:with-param).
    /// Each entry maps a variable name to a RefExpression that is resolved
    /// in the PARENT scope and injected into the CHILD scope before processing.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    params: Vec<RecursiveParam>,
    children: Vec<NodeConfig>,
}
```

New struct:
```rust
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RecursiveParam {
    pub name: String,
    pub value: RefExpression,
}
```

#### [MODIFY] [engine.rs](file:///home/jon/work/ds-rs/engine/src/engine.rs)

**A1. Baked-in variable injection** — in both Recursive processing blocks ([L1752-1881](file:///home/jon/work/ds-rs/engine/src/engine.rs#L1752-L1881) and [L2214-2310](file:///home/jon/work/ds-rs/engine/src/engine.rs#L2214-L2310)):

After `vars.push_scope()`, before shadow-registration:

```rust
// 1. Inject context variables
let top = vars.scopes.last_mut().unwrap();
top.entry("__depth".into()).or_default()
    .push(Store::from_string(&depth.to_string()));
top.entry("__max_depth".into()).or_default()
    .push(Store::from_string(&max_depth.to_string()));
if let Some(mode) = mode {
    top.entry("__mode".into()).or_default()
        .push(Store::from_string(mode));
}

// 2. Inject params (resolved in PARENT scope before push)
// (params resolved before push_scope, stored in temp vec, then injected)
```

The param resolution must happen **before** `push_scope()` so refs resolve against the parent's scope. The results are then injected into the new scope:

```rust
// Resolve params in parent scope
let param_values: Vec<(String, Store)> = params.iter().map(|p| {
    let val = resolve_ref(&p.value, mr, match_count, vars, parent_encoding)
        .unwrap_or_default();
    (p.name.clone(), Store::from_string(&val))
}).collect();

vars.push_scope();

// Inject params into new scope (before shadow-registration)
let top = vars.scopes.last_mut().unwrap();
for (name, store) in param_values {
    top.entry(name).or_default().push(store);
}
```

**A2. Compile-time optimisation** — in `register_recursive_templates()` ([L2965](file:///home/jon/work/ds-rs/engine/src/engine.rs#L2965)):

When building `RecursiveTemplate`, scan children for any `Condition` or `RefExpression` referencing context variables. Store flags:

```rust
struct RecursiveTemplate {
    children: Vec<NodeConfig>,
    scope_var_ids: Vec<String>,
    has_output_children: bool,
    has_expr_children: bool,
    // NEW: only inject context vars that are actually referenced
    needs_depth: bool,
    needs_max_depth: bool,
    needs_mode: bool,
}
```

At runtime, skip injection of unreferenced context variables.

**A3. Preview-only `__parent_match`** — when `CaptureCollector` is active (UI preview mode only), also inject `__parent_match` as a context variable with the content bytes decoded to string. This is never injected in production.

#### [MODIFY] [capture.rs](file:///home/jon/work/ds-rs/engine/src/capture.rs)

Add `recursion_depth: usize` to `CaptureRecord`. Update all `record()` call sites to pass the current `depth`.

---

### B. Editor Model Changes

#### [MODIFY] [model.rs](file:///home/jon/work/ds-rs/node-editor/src/model.rs)

Add new `NodeSettings` variants:

```rust
/// Recursive template — XSLT apply-templates equivalent
Recursive {
    content_ref: String,    // ref expression for content bytes
    max_depth: String,      // default "64"
    template_ref: String,   // named template to invoke (empty = use own children)
    mode: String,           // template dispatch mode
    record_header: String,
    record_footer: String,
    params: String,         // JSON array of {name, value} pairs
},

/// ForEach iteration over multi-valued variable
ForEach {
    var_id: String,
    group: String,          // default "0"
},
```

Add serialization, deserialization, type_icon (`"↻"` / `"🔄"`), and display_name entries.

---

### C. Pipeline Stack UI Changes

#### [MODIFY] [pipeline_stack.rs](file:///home/jon/work/ds-rs/node-editor/src/pipeline_stack.rs)

##### C1. Recursive Node Settings Pane

```
┌─────────────────────────────────────┐
│  SETTINGS                           │
│  Name: xmlToJson                    │
│  Content Ref: @rval                 │
│  Max Depth: [64]                    │
│  Template: [▾ (self)          ]     │
│  Mode: ______                       │
│  Record Header: {                   │
│  Record Footer: }                   │
│  ─────────────                      │
│  PARAMS (passed to child scope)     │
│  ┌──────────┬──────────────────┐    │
│  │ Name     │ Value            │    │
│  ├──────────┼──────────────────┤    │
│  │ path     │ @path + "/" + $1 │    │
│  └──────────┴──────────────────┘    │
│  [+ Add Param]                      │
│  ─────────────                      │
│  CONTEXT VARIABLES                  │
│  __depth = 0         (← engine)    │
│  __max_depth = 64    (← engine)    │
│  ─────────────                      │
│  VARIABLES (scope)                  │
│  rtag = "a"          (← this)      │
│  root_tag = "html"   (← depth 0)  │
└─────────────────────────────────────┘
```

- **Template Ref**: Dropdown populated by scanning `nodes` for all Recursive nodes with non-empty id + children. "(self)" when empty (definition mode).
- **Params table**: Editable name/value pairs. Value field accepts ref expression syntax. [+ Add Param] adds a row.
- **Context variables**: Shown separately from user variables, labelled "← engine".

##### C2. Depth Stepper

For Recursive template definitions, add a depth stepper alongside the match stepper:

```
Depth: ◀ [0/3] ▶    Match: ◀ [1/5] ▶
```

- New signal: `depth_steps: RwSignal<HashMap<usize, (usize, usize)>>` per-layer `(current, max_observed)`
- Changing depth re-filters `CaptureRecord`s by `recursion_depth` to show content at that level
- `__depth` context variable in the variables pane updates reactively
- Match stepper resets to `[1/N]` when depth changes

##### C3. Definition vs Call-Site

| Mode | Has children? | template_ref | Icon | Ops list | Next layer |
|------|:-:|:-:|:-:|:-:|:-:|
| Definition | ✅ | empty | `↻` | `↻ xmlToJson` | Own children |
| Call-site | ❌ | set | `↻→` | `↻→ xmlToJson` | Template's children (read-only) |

Call-sites show the referenced template's children in the next layer with a header: `↻ Template: xmlToJson (read-only)`. Navigable for inspection but not editable from the call-site. Edits require navigating to the template definition.

##### C4. Recursive Layer Header Badge

```
▼ LAYER 3 ─ ↻ depth 2/64 ──────────────────────
```

Indicates the layer is showing recursive content, not a static tree position.

##### C5. Choose/If Branch Visualization

Settings pane for Choose nodes renders conditions human-readably:

```
BRANCHES
  When: @rval matches "<"       → [3 children]  ● active
  When: __depth > 2             → [1 child]
  Otherwise:                    → [2 children]
```

- Conditions rendered via `render_condition()` helper that walks the `Condition` enum
- Active branch determined from current preview data (which branch the engine took)
- Clicking a branch → next layer shows that branch's children

##### C6. Template Library Panel

Collapsible panel at top of pipeline stack:

```
┌─ TEMPLATES ────────────────────────┐
│  ↻ xmlToJson    (3 children)       │
│  ↻ buildPath    (2 children)       │
│  [+ New Template]                  │
└────────────────────────────────────┘
```

Clicking a template navigates to its definition layer. [+ New Template] creates a new Recursive node with a generated id at the current insertion point.

---

### D. Server API Extension

#### [MODIFY] Server match endpoint

Add optional `recursion_depth` to `MatchesForNodeRequest`:

```rust
pub struct MatchesForNodeRequest {
    pub preview_id: String,
    pub node_id: String,
    pub parent_offset: Option<usize>,
    pub parent_length: Option<usize>,
    pub recursion_depth: Option<usize>,  // NEW
}
```

---

## Implementation Phases

### Phase R1: Engine — Baked-In Variables & Params

1. Add `RecursiveParam` struct and `params` field to `NodeConfig::Recursive`
2. Add param resolution before `push_scope()` and injection after
3. Inject `__depth`, `__max_depth`, `__mode` after `push_scope()` (both Recursive handlers)
4. Add `needs_depth`/`needs_max_depth`/`needs_mode` flags to `RecursiveTemplate` for compile-time optimisation
5. Add `recursion_depth` to `CaptureRecord`
6. Add `__parent_match` context variable injection when capture collector is active (preview-only)
7. Engine tests:
   - `__depth` resolves correctly at each level
   - Choose/If branches on `__depth`
   - Params pass accumulated values across depths
   - Params are resolved in parent scope, injected in child scope
   - Unused baked-ins are not injected when flags are false

**Files**: `node.rs`, `engine.rs`, `capture.rs`, `new_features_test.rs`

### Phase R2: Editor Model

1. Add `NodeSettings::Recursive` and `NodeSettings::ForEach`
2. Serialization / deserialization for both
3. `type_icon()`, `display_name_for()` entries
4. Build verification

**Files**: `model.rs`

### Phase R3: Settings Pane & Inline Editing

1. Recursive settings: content_ref, max_depth, template_ref dropdown, mode, record_header/footer
2. Params table: editable name/value pairs with [+ Add Param] / [✕ Remove]
3. ForEach settings: var_id, group
4. All inputs wired to `nodes.update()`

**Files**: `pipeline_stack.rs`, `style.css`

### Phase R4: Depth Stepper & Preview

1. `depth_steps` signal
2. Depth stepper UI alongside match stepper
3. Extend `fetch_match_data()` with `recursion_depth` parameter
4. Filter CaptureRecords by `recursion_depth`
5. Context variables display in variables pane
6. Depth change cascades to child layers

**Files**: `pipeline_stack.rs`, `api.rs`, server endpoint

### Phase R5: Template Library & Call-Site Display

1. Template library panel (scan, list, navigate, create)
2. Definition vs call-site icon/label distinction
3. Call-site read-only template children view
4. Recursive layer header depth badge

**Files**: `pipeline_stack.rs`, `style.css`

### Phase R6: Choose/If Branch Visualization

1. `render_condition()` helper for human-readable condition text
2. Branch list in settings pane with child count
3. Active branch indicator from preview data
4. Click branch → next layer shows branch children

**Files**: `pipeline_stack.rs`, `style.css`

---

## Verification Plan

### Engine Tests
- Recursive XML→JSON with depth-aware Choose: verify output correctness
- `__depth` == 0 at first level, increments correctly
- Choose selects correct branch based on `__depth`
- Params: accumulate path across 4 depth levels, verify final value
- Params: parent scope resolution (param value uses parent's variables, not child's)
- Scoped isolation: depth 2's vars don't leak to depth 1 after pop_scope
- Compile-time optimisation: `needs_depth=false` → `__depth` not injected
- `__parent_match` only injected with active CaptureCollector

### UI Tests
- Create Recursive template definition → children in next layer
- Create Recursive call-site → read-only template children view
- Depth stepper: step 0→1→2 → input context updates, `__depth` context var updates
- Params table: add/edit/remove params, verify JSON serialization
- Template library: lists all templates, click navigates
- Template Ref dropdown: shows all available templates
- Choose branch: conditions render correctly, active indicator works
- Branch click: next layer shows branch children
