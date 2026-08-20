# Composable Matcher Nodes — Implementation Plan

Based on [design plan v4](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/design/combinator_design_plan.md) and a full survey of the existing codebase.

## Current Codebase

| File | Role | Lines |
|------|------|-------|
| [node.rs](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/node.rs) | `NodeConfig` enum — core data model | 170 |
| [engine.rs](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/engine.rs) | Execution loop, matching, dispatch | 847 |
| [config.rs](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/config.rs) | DS3 XML config parser → `NodeConfig` | 556 |
| [xml_writer.rs](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/xml_writer.rs) | `records/record/data` XML output | 164 |
| [refs.rs](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/refs.rs) | `$1`, `$varId$1` reference parsing | 396 |
| [buffer.rs](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/buffer.rs) | `CharBuffer` sliding window | 470~ |
| [reader.rs](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/reader.rs) | `DS3Reader` wrapping `Read` | 350~ |
| [store.rs](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/store.rs) | `Store` for var persistence | 130~ |
| [error.rs](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/error.rs) | `ConfigError`, `ParseError`, `ParseMessage` | 108 |
| [lib.rs](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/lib.rs) | Module exports | 10 |
| [Cargo.toml](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/Cargo.toml) | Deps: regex, fancy-regex, quick-xml, thiserror | 15 |

**Tests**: 19 fixture-based integration tests in [ds3_tests.rs](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/tests/ds3_tests.rs) with `.ds3.xml` + `.in` + `.out.xml` triplets.

**Node editor**: Separate crate at `node-editor/` with `model.rs` and `main.rs`.

---

## Crate Structure Evolution

The project will evolve from a single crate to a workspace:

```
datasplitter-rs/
├── Cargo.toml               ← workspace root
├── crates/
│   ├── ds3-core/             ← engine, config, node model (existing src/)
│   │   ├── src/
│   │   │   ├── node.rs       ← NodeConfig + MatcherNode + TransformNode + StructureNode
│   │   │   ├── engine.rs     ← execution loop with virtual compilation + decorator wiring
│   │   │   ├── config.rs     ← DS3 XML parser (extended for new elements)
│   │   │   ├── matcher/      ← NEW: atom implementations
│   │   │   │   ├── mod.rs
│   │   │   │   ├── tag.rs
│   │   │   │   ├── take_while.rs
│   │   │   │   ├── take_until.rs
│   │   │   │   ├── take_n.rs
│   │   │   │   ├── any_char.rs
│   │   │   │   └── predicate.rs
│   │   │   ├── combinator/   ← NEW: combinator implementations
│   │   │   │   ├── mod.rs
│   │   │   │   ├── sequence.rs
│   │   │   │   ├── choice.rs
│   │   │   │   ├── repeat.rs
│   │   │   │   ├── optional.rs
│   │   │   │   ├── delimited.rs
│   │   │   │   └── separated.rs
│   │   │   ├── transform/    ← NEW: transform node implementations
│   │   │   │   ├── mod.rs
│   │   │   │   ├── concat.rs
│   │   │   │   ├── format.rs
│   │   │   │   ├── replace.rs
│   │   │   │   ├── map.rs
│   │   │   │   └── conditional.rs
│   │   │   ├── output/       ← NEW: output writers
│   │   │   │   ├── mod.rs
│   │   │   │   ├── writer.rs      ← OutputWriter trait
│   │   │   │   ├── xml_writer.rs  ← existing, refactored
│   │   │   │   ├── json_writer.rs
│   │   │   │   └── csv_writer.rs
│   │   │   ├── wiring.rs     ← NEW: virtual compilation + decorator wiring
│   │   │   └── ...existing files...
│   │   └── Cargo.toml
│   ├── ds3-editor/           ← editor-specific (preview, AI chat)
│   │   ├── src/
│   │   │   ├── preview.rs    ← live data preview engine
│   │   │   ├── capture.rs    ← Captured decorator
│   │   │   └── timing.rs     ← Timed decorator
│   │   └── Cargo.toml
│   └── ds3-migration/        ← legacy config migration
│       ├── src/
│       │   └── migrate.rs
│       └── Cargo.toml
├── node-editor/              ← existing UI crate
├── tests/                    ← existing fixtures
└── design/                   ← design docs
```

> [!IMPORTANT]
> The workspace split happens incrementally — Phases 1–4 can work within the existing single crate. The workspace split is introduced at Phase 5 (migration) and Phase 8 (editor preview).

---

## Phase 1: Matcher Atoms (~2 days)

### Goal
Add atomic matching operations as first-class node types alongside existing Split/Regex/All.

### Proposed Changes

---

#### [NEW] `src/matcher/mod.rs`

New module containing the `MatcherAtom` trait and atom implementations:

```rust
pub trait MatcherAtom {
    fn try_match(&self, input: &str) -> Option<AtomMatch>;
}

pub struct AtomMatch {
    pub consumed: usize,       // chars consumed
    pub output: String,        // matched content
    pub groups: Vec<Option<String>>,  // capture groups (for Regex atom)
}
```

#### [NEW] `src/matcher/tag.rs`, `take_while.rs`, `take_until.rs`, `take_n.rs`, `any_char.rs`

One file per atom. Each implements `MatcherAtom`. Simple, focused modules (~30–60 lines each).

#### [NEW] `src/matcher/predicate.rs`

Predicate enum + custom charset parser.

```rust
pub enum Predicate {
    Alphabetic, Alphanumeric, Numeric, Whitespace, NonWhitespace, Any,
    Custom(CharSet),
}

pub struct CharSet { /* parsed from "[a-zA-Z0-9_.-]" */ }
```

Includes a mini-parser for `[a-zA-Z_]` syntax — ~100 lines.

#### [MODIFY] [node.rs](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/node.rs)

Add new variants to `NodeConfig`:

```rust
// New atom variants
Tag { id: Option<String>, text: String, children: Vec<NodeConfig> },
TakeWhile { id: Option<String>, predicate: Predicate, children: Vec<NodeConfig> },
TakeUntil { id: Option<String>, pattern: String, children: Vec<NodeConfig> },
TakeN { id: Option<String>, count: usize, children: Vec<NodeConfig> },
AnyChar { id: Option<String>, children: Vec<NodeConfig> },
```

Update `children()`, `children_mut()`, `is_expression()`, `type_name()`, `id()` to handle new variants.

#### [MODIFY] [config.rs](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/config.rs)

Add parsing for new XML elements: `<tag>`, `<takeWhile>`, `<takeUntil>`, `<takeN>`, `<anyChar>`. Extend `parse_element()` match arm.

#### [MODIFY] [engine.rs](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/engine.rs)

Extend `try_match()` to dispatch to atom implementations for new `NodeConfig` variants. Each atom calls its `MatcherAtom::try_match()` and wraps the result in `MatchRes`.

#### [MODIFY] [lib.rs](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/lib.rs)

Add `pub mod matcher;`

---

## Phase 2: Combinator Nodes (~3 days)

### Goal
Add combinators that compose atoms/other combinators into complex matchers with labeled outputs.

### Proposed Changes

---

#### [NEW] `src/combinator/mod.rs`

Combinator trait and composite matching logic:

```rust
pub trait Combinator {
    fn try_match(&self, input: &str, children: &[Box<dyn MatcherAtom>]) -> Option<CompositeMatch>;
}
```

#### [NEW] `src/combinator/sequence.rs`, `choice.rs`, `repeat.rs`, `optional.rs`

Core combinators. `Sequence` runs children in order. `Choice` tries each until one matches. `Repeat` runs a child min..max times. ~50–80 lines each.

#### [MODIFY] [node.rs](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/node.rs)

Add combinator variants to `NodeConfig`:

```rust
Sequence { id: Option<String>, output_labels: Vec<Option<String>>, children: Vec<NodeConfig> },
Choice { id: Option<String>, children: Vec<NodeConfig> },
Optional { id: Option<String>, children: Vec<NodeConfig> },
RepeatN { id: Option<String>, min: usize, max: Option<usize>, children: Vec<NodeConfig> },
```

#### [MODIFY] [engine.rs](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/engine.rs)

Add a `try_match_composed()` function that walks a combinator tree, threading input through atoms and collecting labeled outputs. This is the start of the "virtual compilation" model — the combinator tree is traversed and each atom is dispatched.

#### [MODIFY] [config.rs](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/config.rs)

Add parsing for `<sequence>`, `<choice>`, `<optional>`, `<repeat>` XML elements.

#### [MODIFY] [lib.rs](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/lib.rs)

Add `pub mod combinator;`

---

## Phase 3: Pattern Library (~3 days)

### Goal
Named, reusable compositions stored in a library with categories and search.

### Proposed Changes

---

#### [NEW] `src/pattern.rs`

`PatternTemplate` struct, library storage, and reference resolution:

```rust
pub struct PatternLibrary {
    templates: HashMap<String, PatternTemplate>,
}

pub struct PatternTemplate {
    pub id: String,
    pub name: String,
    pub category: String,
    pub is_builtin: bool,
    pub root_node: NodeConfig,
    pub output_labels: Vec<String>,
}
```

#### [NEW] `src/builtin_patterns.rs`

Built-in patterns (Digits, Word, IP Address, ISO Date, etc.) defined as `NodeConfig` trees. ~200 lines.

#### [MODIFY] [node.rs](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/node.rs)

Add `PatternRef` variant:

```rust
PatternRef { id: Option<String>, template_id: String, children: Vec<NodeConfig> },
```

#### [MODIFY] [config.rs](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/config.rs)

Parse `<patternLibrary>`, `<pattern>`, and `<patternRef>` elements. The library is parsed first and made available for ref resolution during the main config parse.

#### [MODIFY] [engine.rs](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/engine.rs)

When encountering `PatternRef`, resolve the template from the library and inline its `root_node` for execution.

---

## Phase 4: Advanced Combinators + Peek/Not/Reverse (~3 days)

### Goal
Add Delimited, Separated, Peek, Not, Reverse atoms.

### Proposed Changes

---

#### [NEW] `src/combinator/delimited.rs`, `separated.rs`
#### [NEW] `src/matcher/peek.rs`, `not.rs`, `reverse.rs`

Each ~30–50 lines. `Peek` runs inner atom but doesn't consume. `Not` inverts success/failure. `Reverse` reverses matched string.

#### [MODIFY] [node.rs](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/node.rs)

Add variants: `Delimited`, `Separated`, `Peek`, `Not`, `Reverse`.

#### [MODIFY] [config.rs](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/config.rs)

Parse new XML elements.

#### [MODIFY] [engine.rs](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/engine.rs)

Extend `try_match()` and `try_match_composed()` for new node types.

---

## Phase 5: Migration Layer (~2 days)

### Goal
Auto-convert existing DS3 Split/Regex/All configs to atom/combinator compositions.

### Proposed Changes

---

#### [NEW] `src/migration.rs`

Core migration logic:

```rust
pub fn migrate_node(node: &NodeConfig) -> NodeConfig {
    match node {
        NodeConfig::Split { delimiter, escape, container_start, container_end, .. } => {
            // → Separated(TakeUntil(delim), Tag(delim))
            // + handle escape and containers
        }
        NodeConfig::Regex { .. } => { /* direct mapping to Regex atom */ }
        NodeConfig::All { .. } => { /* → TakeWhile(Any) */ }
        _ => node.clone(),
    }
}
```

~200 lines. Handles the mappings from the design plan table.

#### [MODIFY] [engine.rs](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/engine.rs)

Add an optional migration pass at the start of `parse()` — if a feature flag is set, migrate the entire config tree before execution.

---

## Phase 6: Binary/Encoding Layer (~3 days)

### Goal
Operate on raw `&[u8]` byte streams with cascading encoding inheritance.

### Proposed Changes

---

#### [NEW] `src/encoding.rs`

`Encoding` enum, inheritance resolution, BOM sniffing:

```rust
pub enum Encoding {
    Utf8, Latin1, Utf16Le, Utf16Be, Ascii, ShiftJis, Ebcdic, Raw, Auto,
}

pub fn resolve_encoding(node_enc: Option<Encoding>, parent_enc: Encoding) -> Encoding { ... }
pub fn detect_bom(bytes: &[u8]) -> Option<Encoding> { ... }
```

#### [NEW] `src/matcher/byte_atoms.rs`

Byte-level atoms: `TakeBytes`, `MatchByte`, `ReadShort`, `ReadInt`, `ReadFloat`, `ReadDouble`, `ReadLong`.

```rust
pub fn read_short(bytes: &[u8], signed: bool, endian: Endianness) -> Option<(i64, usize)> { ... }
pub fn read_int(bytes: &[u8], size: IntSize, signed: bool, endian: Endianness) -> Option<(i64, usize)> { ... }
```

#### [MODIFY] [node.rs](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/node.rs)

Add `encoding: Option<Encoding>` to all node variants. Add byte atom variants.

#### [MODIFY] [reader.rs](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/reader.rs)

Extend `DS3Reader` to expose raw `&[u8]` windows alongside `&str` windows. Encoding resolution at read time.

#### [MODIFY] [Cargo.toml](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/Cargo.toml)

Add `encoding_rs = "0.8"` dependency.

---

## Phase 7: Output Transform Layer (~5 days)

### Goal
Replace XSLT with in-graph transform and structure nodes. Multiple output formats.

### Proposed Changes

---

#### [NEW] `src/transform/mod.rs`

`TransformNode` enum and execution:

```rust
pub enum TransformNode {
    Concat { separator: Option<String> },
    Split { delimiter: String },
    Replace { pattern: String, replacement: String, is_regex: bool },
    Format { template: String },
    Map { entries: Vec<(String, String)>, default: Option<String> },
    Coalesce, Lowercase, Uppercase, Trim, ToNumber,
    Conditional { condition: ConditionExpr },
}

pub fn execute_transform(node: &TransformNode, inputs: &[&str]) -> Option<String> { ... }
```

#### [NEW] `src/transform/condition.rs`

`ConditionExpr` enum and evaluation. ~100 lines.

#### [NEW] `src/structure.rs`

`StructureNode` enum and tree builder:

```rust
pub enum StructureNode {
    Object { name: String, children: Vec<StructureNode> },
    Array { name: String, children: Vec<StructureNode> },
    Field { name: String, value_source: ValueSource },
    Literal { name: String, value: String },
    Iterate { name: String, matcher_ref: String, children: Vec<StructureNode> },
    ConditionalBlock { condition: ConditionExpr, children: Vec<StructureNode> },
}
```

#### [NEW] `src/output/mod.rs`, `writer.rs`

`OutputWriter` trait:

```rust
pub trait OutputWriter {
    fn start_document(&mut self) -> Result<(), ParseError>;
    fn end_document(&mut self) -> Result<(), ParseError>;
    fn write_structure(&mut self, node: &StructureNode, values: &ValueMap) -> Result<(), ParseError>;
}
```

#### [NEW] `src/output/json_writer.rs`

`JsonWriter` implementing `OutputWriter`. ~200 lines.

#### [MODIFY] [xml_writer.rs](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/xml_writer.rs) → `src/output/xml_writer.rs`

Refactor existing `XmlWriter` to implement the `OutputWriter` trait. Move to the `output/` module.

#### [MODIFY] [node.rs](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/node.rs)

Add `NodeConfig` variants for transform and structure nodes. Add `OutputFormat` to the `Root` variant.

#### [MODIFY] [config.rs](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/config.rs)

Parse `<object>`, `<array>`, `<field>`, `<literal>`, `<iterate>`, `<concat>`, `<format>`, `<replace>`, `<map>`, `<conditional>`, and `<labelOutput>` / `<labelInput>` elements.

#### [MODIFY] [engine.rs](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/engine.rs)

After match processing, check for structure nodes and route to `OutputWriter` instead of `XmlWriter`. Implement label reference resolution (compile labels to var-store read/write). Implement dead output elimination pass.

#### [NEW] `src/wiring.rs`

Virtual compilation module — builds the execution graph from `NodeConfig`, wires decorator chains, performs dead output elimination:

```rust
pub struct WiringMode {
    pub capture_enabled: bool,
    pub timing_nodes: HashSet<String>,  // node IDs to time
}

pub fn wire_node(node: &NodeConfig, mode: &WiringMode) -> NodeFn { ... }
```

---

## Phase 8: Live Data Preview (~4 days)

### Goal
Resizable preview panel with match highlighting, powered by Captured decorators.

### Proposed Changes

---

#### [NEW] `src/capture.rs`

`Captured` decorator implementation:

```rust
pub struct CaptureRecord {
    pub node_id: String,
    pub input_span: (usize, usize),  // (offset, length)
    pub output: Option<String>,
}

pub fn captured(inner: NodeFn, node_id: String) -> NodeFn { ... }
```

#### [MODIFY] `src/wiring.rs`

Extend `wire_node` to wrap with `Captured` when `mode.capture_enabled` is true.

#### [NEW] `src/preview.rs`

Preview engine — runs the core engine against sample data with capture decorators, collects `CaptureRecord`s, and returns them for the editor to display:

```rust
pub struct PreviewResult {
    pub captures: Vec<CaptureRecord>,
    pub output: String,
    pub errors: Vec<ParseMessage>,
}

pub fn run_preview(config: &NodeConfig, sample: &[u8], output_format: OutputFormat) -> PreviewResult { ... }
```

#### Node editor changes

The `node-editor/` crate renders the preview panel UI. This is primarily UI work — reading `PreviewResult` and rendering highlighted spans.

---

## Phase 9: Execution Timing & Instrumentation (~2 days)

### Goal
Construction-time decorator wiring for timing. Zero overhead when not wired.

### Proposed Changes

---

#### [NEW] `src/timing.rs`

`Timed` decorator and statistics collection:

```rust
pub struct TimingStats {
    pub total_ns: u128,
    pub count: u64,
    pub match_count: u64,
}

pub fn timed(inner: NodeFn, node_id: String) -> NodeFn { ... }
```

#### [MODIFY] `src/wiring.rs`

Extend `wire_node` to stack `Timed` decorators when `mode.timing_nodes` contains the node ID. Composable with `Captured`.

---

## Phase 10: AI Chat Assistant (~5 days)

### Goal
AI-powered chat panel in the editor for guided construction and debugging.

### Proposed Changes

---

This phase is primarily **node-editor crate** work:

#### [NEW] `node-editor/src/ai_chat.rs`

Chat panel UI component, message history, LLM API integration.

#### [NEW] `node-editor/src/ai_context.rs`

Context builder — serialises current canvas state, sample data, pattern library, and errors into a prompt for the LLM.

#### [NEW] `node-editor/src/ai_apply.rs`

"Apply to Canvas" action — deserialises LLM-generated node graph descriptions into `NodeConfig` trees and positions them on the canvas.

---

## Verification Plan

### Existing Tests (Regression Baseline)

All 19 fixture-based integration tests **must pass** after every phase:

```bash
cargo test --test ds3_tests
```

These tests ensure backward compatibility — existing DS3 configs continue to produce identical output.

### Phase-Specific Tests

#### Phase 1: Matcher Atoms

**New unit tests** in `src/matcher/mod.rs` (or `tests/atom_tests.rs`):

```bash
cargo test matcher
```

- `Tag("hello")` matches `"hello world"` → consumed=5, output="hello"
- `TakeWhile(numeric)` on `"12345abc"` → consumed=5, output="12345"
- `TakeUntil(",")` on `"abc,def"` → consumed=3, output="abc"
- `TakeN(3)` on `"abcdef"` → consumed=3, output="abc"
- `AnyChar` on `"x"` → consumed=1, output="x"
- Custom predicate `[a-zA-Z_]` on `"hello_world 123"` → consumed=11

**New fixture test**: `020_tag_match.ds3.xml` with `<tag>` element in config.

```bash
cargo test test_020
```

#### Phase 2: Combinator Nodes

**New unit tests** in `src/combinator/mod.rs`:

```bash
cargo test combinator
```

- `Sequence(Tag("a"), Tag("b"))` on `"ab"` → match
- `Choice(Tag("x"), Tag("y"))` on `"y"` → match on second branch
- `Repeat(Tag("a"), min=2, max=4)` on `"aaa"` → 3 matches
- `Optional(Tag("x"))` on `"y"` → succeeds with no consumption

**New fixture tests**: `021_sequence.ds3.xml`, `022_choice.ds3.xml`.

```bash
cargo test test_021
cargo test test_022
```

#### Phase 3: Pattern Library

**New unit tests** in `src/pattern.rs`:

```bash
cargo test pattern
```

- Load a pattern library from XML, resolve a `PatternRef`
- Built-in "Digits" pattern matches `"42"`, "IP Address" matches `"192.168.1.1"`

#### Phase 4: Advanced Combinators

**New unit tests** for Delimited, Separated, Peek, Not, Reverse:

```bash
cargo test combinator
```

- `Delimited(Tag('"'), TakeUntil('"'), Tag('"'))` on `"\"hello\""` → output="hello"
- `Peek(Tag("x"))` on `"xy"` → matches but consumed=0
- `Not(Tag("x"))` on `"y"` → succeeds
- `Reverse(TakeWhile(alpha))` on `"hello"` → output="olleh"

#### Phase 5: Migration Layer

**New test** in `tests/migration_tests.rs`:

```bash
cargo test migration
```

- Migrate each of the 19 existing fixture configs to atom/combinator form
- Re-run them and verify identical output

#### Phase 6: Binary/Encoding

**New fixture tests**: `023_encoding_latin1.ds3.xml`, `024_byte_atoms.ds3.xml`.

```bash
cargo test test_023
cargo test test_024
```

- BOM detection on UTF-16 input
- `ReadShort` / `ReadInt` on binary data

#### Phase 7: Output Transform Layer

**New fixture tests**: `025_json_output.ds3.xml`, `026_transform_format.ds3.xml`.

```bash
cargo test test_025
cargo test test_026
```

- Config with Structure nodes produces JSON instead of XML
- Format transform `"{0}T{1}"` produces expected concatenation
- Dead output elimination: verify that unused groups don't appear in debug output
- Label references: label output in one group, label input in another, verify values flow

#### Phase 8: Live Data Preview

**New test** in `src/preview.rs` (unit test):

```bash
cargo test preview
```

- Run preview on a simple config + sample → verify `CaptureRecord`s contain expected spans
- Verify preview timeout (large input doesn't hang)

#### Phase 9: Execution Timing

**New test** in `src/timing.rs` (unit test):

```bash
cargo test timing
```

- Wire a node with `Timed` decorator, execute, verify `TimingStats` populated
- Wire without decorator, verify zero overhead (no stats struct allocated)

#### Phase 10: AI Chat

Manual testing — the AI chat requires an LLM endpoint and is primarily UI. Verification:

- Mock LLM responses and verify "Apply to Canvas" creates correct `NodeConfig` trees
- Test in the node editor manually

### Running All Tests

```bash
# Full regression + new tests
cargo test

# Just the existing fixture tests
cargo test --test ds3_tests

# Just the new module tests
cargo test matcher combinator pattern
```
