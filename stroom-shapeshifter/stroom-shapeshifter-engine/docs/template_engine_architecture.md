# DataSplitter Template Engine — Architecture

> Comprehensive reference for the XSLT-inspired template processing model,
> source file map, data flow, and key design decisions.

---

## Table of Contents

1. [Overview](#overview)
2. [Data Flow](#data-flow)
3. [Project Configuration Model](#project-configuration-model)
4. [Template Anatomy](#template-anatomy)
5. [Match Expressions](#match-expressions)
6. [Capture Bindings](#capture-bindings)
7. [Output Body](#output-body)
8. [Engine Execution](#engine-execution)
9. [Compilation Pipelines](#compilation-pipelines)
10. [Source File Map](#source-file-map)
11. [Crate Architecture](#crate-architecture)
12. [Key Design Decisions](#key-design-decisions)
13. [Test Infrastructure](#test-infrastructure)

---

## Overview

DataSplitter is a data parsing engine that extracts structured fields from
arbitrary text or binary input using pattern matching. It is inspired by
XSLT's template dispatch model:

- Input bytes flow through a **flat list of templates**
- Each template specifies a **match expression** (regex, delimiter, combinator sequence)
- Matching templates produce **captures** (named variables) and **output** (text, recursion)
- Templates are dispatched by **mode** (partitioning) and filtered by **guard** (pre-conditions)
- `apply-templates` recurses into captured content, enabling hierarchical parsing

The engine operates at the **byte level** for performance. All matching is done
on raw `&[u8]` — text decoding only happens at output time.

---

## Data Flow

```
                    ┌─────────────────────────┐
                    │   Input Sources          │
                    │  (files, streams, stdin) │
                    └────────────┬────────────┘
                                 │ &[u8]
                    ┌────────────▼────────────┐
                    │   Buffered Reader        │
                    │  (chunked, source.       │
                    │   buffer_size bytes)      │
                    └────────────┬────────────┘
                                 │ &[u8] chunk
                    ┌────────────▼────────────┐
                    │   Template Dispatch      │
                    │  for each mode-filtered  │
                    │  template:               │
                    │    1. Guard check         │
                    │    2. Match expression    │
                    │    3. Bind captures       │
                    │    4. Execute body        │
                    └────────────┬────────────┘
                                 │ writes
                    ┌────────────▼────────────┐
                    │   Output Writer          │
                    │  (impl Write)            │
                    └─────────────────────────┘
```

### Streaming Model

The engine reads input in chunks of `source.buffer_size` bytes. Each chunk is
processed by matching templates against the buffer content. The delimiter-based
match cursor advances through the buffer, and leftover bytes (partial matches at
chunk boundaries) are carried over to the next read.

---

## Project Configuration Model

A project is defined by `ProjectConfig` (serialised as `project.json`):

```
ProjectConfig
├── name: String
├── version: u32                    (always 3)
├── source: SourceConfig
│   ├── buffer_size: usize          (default: 20,000 bytes)
│   ├── ignore_errors: bool
│   └── encoding: String            ("auto", "UTF-8", "latin1", etc.)
├── templates: Vec<Template>        (flat list — order matters for priority)
└── patterns: Vec<CombinatorPattern> (reusable match step sequences)
```

### Example `project.json`

```json
{
  "name": "Apache HTTPD",
  "version": 3,
  "source": {
    "buffer_size": 20000,
    "ignore_errors": true,
    "encoding": "auto"
  },
  "templates": [
    {
      "id": "...",
      "name": "line",
      "match_expr": { "Delimiter": { "delimiter": "\n" } },
      "body": [
        { "Apply": { "content": { "parts": [{ "Store": { "group": 0 } }] }, "mode": "fields" } }
      ]
    },
    {
      "id": "...",
      "name": "field",
      "mode": "fields",
      "match_expr": { "Regex": { "pattern": "(\\S+)\\s+(.*)" } },
      "captures": [
        { "name": "key", "source": { "Group": 1 } },
        { "name": "value", "source": { "Group": 2 } }
      ],
      "body": [
        { "Text": { "parts": [{ "Text": "<" }] } },
        { "ValueOf": { "Var": "key" } },
        { "Text": { "parts": [{ "Text": ">" }] } },
        { "ValueOf": { "Var": "value" } }
      ]
    }
  ]
}
```

---

## Template Anatomy

Each `Template` combines matching, capture, and output in one unit:

```
Template
├── id: Uuid                        unique identifier
├── name: String                    human-readable display name
├── mode: Option<String>            mode partition (None = default mode)
├── guard: Option<Condition>        pre-filter on scope vars (before matching)
├── params: Vec<ParamDecl>          declared parameters (for CallTemplate)
├── match_expr: MatchExpression     how to match content bytes
├── match_limits: MatchLimits       min/max/only match constraints
├── captures: Vec<CaptureBinding>   bind match groups → named variables
├── body: Vec<OutputNode>           output instructions (executed per match)
└── encoding: Option<String>        encoding override for this template
```

### Dispatch Order

For each `apply-templates` invocation:

1. **Mode filter** — only templates whose `mode` matches the Apply's mode
2. **Guard check** — evaluate `guard` condition against current scope variables
   (e.g., `__depth`, params). Fails → skip template entirely
3. **Match test** — attempt match expression against content bytes
4. **Capture bind** — populate named variables from match groups
5. **Body execute** — emit output nodes sequentially

### Special Template Types

| `match_expr` | Purpose |
|---|---|
| `All` | Matches entire content as $0 — used for envelope/root templates |
| `Named` | Cannot be matched by `apply-templates` — only via `CallTemplate` |
| `Delimiter { .. }` | Split-based matching with escape/container support |
| `Regex { .. }` | Regex pattern matching with capture groups |
| `Progressive(steps)` | Sequential combinator matching for binary/structured data |

---

## Match Expressions

### Delimiter

Splits content on a separator byte sequence. Supports escape characters and
container pairs (e.g., quotes). Group 0 = segment including delimiter,
Group 1 = segment with containers stripped and escapes removed.

```json
{ "Delimiter": { "delimiter": ",", "escape": "\\", "container_start": "\"", "container_end": "\"" } }
```

### Regex

Standard regex matching via `regex::bytes` (fast path) or `fancy-regex`
(backreferences/lookaheads). The `advance` field controls cursor positioning:
- `0` = advance by full match length (default)
- `N > 0` = advance to the byte position where group N ends

```json
{ "Regex": { "pattern": "^(\\d+)\\s+(\\w+)$", "flags": { "case_insensitive": true } } }
```

### Progressive

Sequential combinator matching for binary and structured data. Each step
consumes bytes in order. Steps can reference captures from earlier steps
(e.g., `TakeBytes($length)` where `length` was read by `ReadNumeric`).

**Atom steps:**
`Tag`, `MatchByte`, `TakeWhile`, `TakeUntil`, `TakeBytes`, `TakeN`,
`AnyChar`, `ReadNumeric`, `Regex`

**Combinator steps:**
`Choice`, `Optional`, `Repeat`, `Sequence`, `PatternRef`, `Peek`, `Not`

```json
{ "Progressive": [
    { "ReadNumeric": { "numeric_type": "Short", "endian": "Big" } },
    { "ReadNumeric": { "numeric_type": "Int", "endian": "Big" } },
    { "TakeBytes": { "CaptureRef": "length" } }
]}
```

---

## Capture Bindings

Captures bind match results to named scope variables, populated after a
successful match and before the body executes:

| Source | Description |
|---|---|
| `Group(N)` | Regex capture group by index (0 = full match) |
| `Step(N)` | Progressive match step by index |
| `Expression(RefExpr)` | Computed from a reference expression |
| `KeyValue { key_ref, value_ref }` | Dynamic name+value from expressions |

---

## Output Body

The body is a `Vec<OutputNode>` executed sequentially after captures are bound:

| Node | Description | XSLT Equivalent |
|---|---|---|
| `Text(RefExpr)` | Emit interpolated text | `xsl:text` / `xsl:value-of` |
| `ValueOf(ValueRef)` | Write a capture group or variable | `xsl:value-of` |
| `Apply(ApplyDirective)` | Recursive template dispatch on content | `xsl:apply-templates` |
| `CallTemplate { name, params }` | Invoke a Named template directly | `xsl:call-template` |
| `Variable { name, body }` | Bind a variable from child output | `xsl:variable` |
| `If { condition, then }` | Conditional output | `xsl:if` |
| `Choose { branches, otherwise }` | Multi-branch conditional | `xsl:choose` |
| `Switch { on_ref, cases, default }` | Value-based dispatch | — |
| `ValueMap { on_ref, entries }` | Lookup table transformation | — |
| `TransformOutput { transform, inputs }` | Apply transform function | — |

### Apply vs CallTemplate

- **Apply** matches content bytes against mode-filtered templates. It is the
  XSLT `apply-templates` — content-driven dispatch with recursion depth limits.
- **CallTemplate** invokes a `Named` template by name with explicit parameters.
  No content matching occurs. Parameters are scoped (pushed/popped on the
  variable registry to prevent caller/callee leakage).

---

## Engine Execution

### Core Architecture (`engine/`)

The engine is split into four submodules:

| Module | Responsibility |
|---|---|
| `core.rs` | Regex compilation, match results (`MatchRes`), variable registry (`ScopedVarRegistry`), reference resolution, condition evaluation, delimiter split matching |
| `exec.rs` | Template dispatch, body execution, apply-templates recursion, streaming buffer management |
| `store.rs` | Captured value storage (indexed by match count and group) |
| `instrument.rs` | Zero-cost instrumentation trait — `NoOpInstrument` (production) and `RecordingInstrument` (UI/debug) |

### Key Runtime Types

| Type | Description |
|---|---|
| `MatchRes` | Match result: groups as `GroupVec` (SmallVec of byte slices), match metadata |
| `ScopedVarRegistry` | Stack-based variable scope — `push_scope()`/`pop_scope()` for isolation |
| `CompiledRegex` | Enum of `regex::bytes::Regex` (fast) or `fancy_regex::Regex` (backrefs) |
| `CompiledDelimiter` | Pre-encoded delimiter bytes cached per template to avoid per-match encoding |
| `RegexCache` | `HashMap<Uuid, CompiledRegex>` — compiled once, reused per template |
| `DelimiterCache` | `HashMap<Uuid, CompiledDelimiter>` — same for delimiter templates |
| `Store` | Raw byte storage indexed by match count |

### Performance Design

- **Byte-level matching** — all matching operates on `&[u8]`, no UTF-8 validation
- **SmallVec groups** — `GroupVec = SmallVec<[&[u8]; 8]>` avoids heap allocation
  for captures ≤ 8 groups (covers 99% of patterns)
- **Compiled delimiter caching** — delimiter bytes are pre-encoded once at init,
  not per-match
- **Monomorphised instrumentation** — `NoOpInstrument` is a ZST; all callbacks
  compile to nothing in production builds
- **memchr fast path** — single-byte delimiters use `memchr` for SIMD-accelerated
  searching

---

## Compilation Pipelines

There are three paths into the engine, all producing `ProjectConfig`:

```
┌─────────────────┐     ┌──────────────┐     ┌──────────────────┐
│  Node Editor UI │     │  Legacy XML  │     │  Direct JSON     │
│  (graph model)  │     │  (.ds3.xml)  │     │  (project.json)  │
└────────┬────────┘     └──────┬───────┘     └────────┬─────────┘
         │                     │                      │
    compiler.rs          legacy_config.rs              │
         │                     │                      │
         ▼                     ▼                      │
    NodeConfig            LegacyNode                  │
         │                     │                      │
         │               migration.rs                 │
         │                     │                      │
         │                     ▼                      │
         │               NodeConfig                   │
         │                     │                      │
         ├─────────────────────┘                      │
         │                                            │
  template_compiler.rs                           serde::from_str
         │                                            │
         ▼                                            ▼
    ProjectConfig ────────────────────────────── ProjectConfig
         │
         ▼
    engine/exec.rs
```

### Path 1: Node Editor → Engine

1. **`compiler.rs`** — Converts the flat graph model (`ProjectData` with
   `ProjectNode[]` + `ProjectBinding[]`) into a `NodeConfig` tree. Resolves
   capture UUIDs to group indices, lowers bindings to children.

2. **`template_compiler.rs`** — Converts `NodeConfig` tree into flat
   `ProjectConfig`. Handles:
   - Root header/footer → envelope template wrapping
   - Group record header/footer → body `OutputNode::Text` wrapping
   - Expression → Template with match expression + captures + body
   - Recursive nodes → mode-based sub-templates with `Apply` directives

3. **`template_registry.rs`** — Resolves `TemplateRef` nodes (e.g., built-in
   `xml_escape_attr`) by inlining their content before compilation.

### Path 2: Legacy XML → Engine

1. **`legacy_config.rs`** — Parses DS3 v3.0 XML into `LegacyNode` tree
   (semantic structure only, no output formatting).

2. **`legacy.rs`** — Defines `LegacyNode` intermediate representation.

3. **`migration.rs`** — Converts `LegacyNode` into `NodeConfig`, applying XML
   output formatting (envelope headers, record wrapping, data templates).

4. Then follows Path 1 from `template_compiler.rs`.

### Path 3: Direct JSON

`ProjectConfig` implements `Serialize`/`Deserialize` — project.json files are
loaded directly via `serde_json::from_str()` with no compilation step.

### Reverse: Engine → Editor

**`decompiler.rs`** — Converts `NodeConfig` back into the flat graph model
(`ProjectData`) for the node editor UI. This is the inverse of `compiler.rs`.

---

## Source File Map

### `engine/` crate — Core engine library

#### Data Model
| File | Lines | Description |
|---|---|---|
| `template.rs` | ~570 | **Template model** — `ProjectConfig`, `Template`, `MatchExpression`, `OutputNode`, `CaptureBinding`, `ApplyDirective`, and all supporting types |
| `node.rs` | ~960 | **Legacy node model** — `NodeConfig` enum (IR between compilers), `Condition`, `RegexFlags`, `SwitchCase`, `WhenBranch` |
| `refs.rs` | ~850 | **Reference expressions** — `RefExpression`, `RefPart`, `RefStrategy`, `$`-syntax parser, convenience constructors |

#### Engine Runtime
| File | Lines | Description |
|---|---|---|
| `engine/exec.rs` | ~1370 | **Template executor** — streaming buffer management, template dispatch loop, `apply-templates` recursion, `CallTemplate` invocation, body output, condition/transform evaluation |
| `engine/core.rs` | ~770 | **Engine core** — regex compilation, `MatchRes`, `ScopedVarRegistry`, `resolve_ref`, `evaluate_condition`, `split_find_bytes`, `build_split_match_bytes` |
| `engine/store.rs` | ~110 | **Value store** — `Store` for captured byte values indexed by match count |
| `engine/instrument.rs` | ~390 | **Instrumentation** — `Instrument` trait, `NoOpInstrument`, `RecordingInstrument` |

#### Matching
| File | Lines | Description |
|---|---|---|
| `matcher/mod.rs` | ~50 | Matcher trait and module declarations |
| `matcher/tag.rs` | ~50 | Exact literal matching |
| `matcher/take_while.rs` | ~70 | Predicate-based consumption |
| `matcher/take_until.rs` | ~90 | Pattern-terminated consumption (memchr fast path) |
| `matcher/take_n.rs` | ~50 | Fixed character count consumption |
| `matcher/any_char.rs` | ~30 | Single character consumption |
| `matcher/byte_atoms.rs` | ~200 | Binary byte matching and `ReadNumeric` |
| `matcher/predicate.rs` | ~300 | Character predicates (alpha, digit, whitespace, etc.) |
| `matcher/peek.rs` | ~40 | Lookahead (match without consuming) |
| `matcher/not.rs` | ~40 | Negative lookahead |
| `matcher/reverse.rs` | ~50 | Reverse matching |
| `match_lookup.rs` | ~300 | Pre-compiled lookup tables for fast predicate checking |

#### Combinators
| File | Lines | Description |
|---|---|---|
| `combinator/mod.rs` | ~30 | Module declarations |
| `combinator/sequence.rs` | ~30 | Sequential step execution |
| `combinator/choice.rs` | ~20 | First-match alternative selection |
| `combinator/optional.rs` | ~20 | Optional (0 or 1) matching |
| `combinator/repeat.rs` | ~20 | Bounded repetition |
| `combinator/delimited.rs` | ~25 | Delimited sequences |
| `combinator/separated.rs` | ~25 | Separator-delimited repetition |

#### Transforms
| File | Lines | Description |
|---|---|---|
| `transform/mod.rs` | ~265 | `TransformNode` enum + `execute_transform()` — Concat, Replace, Format, Map, Coalesce, Lowercase, Uppercase, Trim, ToNumber, Translate, Substring |
| `transform/condition.rs` | ~130 | `ConditionExpr` for transform-level conditionals |

#### Compilation
| File | Lines | Description |
|---|---|---|
| `compiler.rs` | ~1270 | **Graph → NodeConfig** compiler. Converts flat graph (nodes + bindings) to tree |
| `template_compiler.rs` | ~880 | **NodeConfig → ProjectConfig** compiler. Flattens tree into template list |
| `decompiler.rs` | ~1140 | **NodeConfig → Graph** decompiler. Reverse of `compiler.rs` |
| `template_registry.rs` | ~400 | Template library — built-in patterns (e.g., `xml_escape_attr`), resolution |

#### Legacy Support
| File | Lines | Description |
|---|---|---|
| `legacy_config.rs` | ~900 | DS3 XML config parser (quick-xml) |
| `legacy.rs` | ~100 | `LegacyNode` intermediate representation |
| `migration.rs` | ~640 | `LegacyNode` → `NodeConfig` with XML output formatting |

#### Infrastructure
| File | Lines | Description |
|---|---|---|
| `encoding.rs` | ~600 | Multi-encoding support (UTF-8, UTF-16, Latin1, auto-detect) |
| `error.rs` | ~80 | `ParseError`, `ParseMessage`, `Severity` |
| `capture.rs` | ~30 | `CaptureRecord` for UI highlighting |
| `structure.rs` | ~120 | Structural analysis utilities |
| `wiring.rs` | ~470 | Dead output elimination, label collection |
| `output/mod.rs` | ~50 | Output formatting infrastructure |
| `output/json_writer.rs` | ~120 | JSON output writer |
| `preview.rs` | ~180 | Preview engine — `run_preview()` with instrumentation |
| `ai/mod.rs` | ~150 | AI context building and config summarisation |
| `ai/apply.rs` | ~180 | AI response parsing — extract DS3 XML from LLM output |

### `server/` crate — HTTP API server

| File | Lines | Description |
|---|---|---|
| `main.rs` | ~60 | Actix-web server startup |
| `handlers.rs` | ~290 | Request handlers — preview, validate, project CRUD |
| `projects.rs` | ~250 | Project persistence (filesystem) |
| `config.rs` | ~80 | Server configuration |
| `cache.rs` | ~70 | Compilation cache |
| `llm.rs` | ~300 | LLM integration for AI assistant |

### `shared/` crate — Shared types

| File | Lines | Description |
|---|---|---|
| `types.rs` | ~350 | `ProjectData`, `ProjectNode`, `ProjectBinding` — the graph model shared between editor and server |

---

## Crate Architecture

```
┌─────────────────────────────────────────┐
│              node-editor                │  Yew/WASM frontend
│            (Rust → WASM)                │  Graph-based visual editor
└───────────────────┬─────────────────────┘
                    │ HTTP (JSON)
┌───────────────────▼─────────────────────┐
│               server                    │  Actix-web HTTP API
│          (ds3-server crate)             │
└───────────────────┬─────────────────────┘
                    │ lib calls
┌───────────────────▼─────────────────────┐
│               engine                    │  Core processing library
│       (datasplitter-rs crate)           │
└─────────────────────────────────────────┘
                    ▲
┌───────────────────┴─────────────────────┐
│               shared                    │  Graph model types
│         (ds3-shared crate)              │  (ProjectData, ProjectNode)
└─────────────────────────────────────────┘
```

---

## Key Design Decisions

### 1. Flat Templates vs Tree

The legacy `NodeConfig` is a deep tree (Root → Split → Group → Regex → Var/Data).
The template model flattens this into a list of templates with mode-based dispatch,
mirroring XSLT. Benefits:
- Simpler to reason about priority (list order)
- Templates are independently testable
- Recursive processing via `apply-templates` is explicit
- No deep nesting — all templates are peers

### 2. Byte-Level Engine

All matching operates on `&[u8]`. Text decoding (`decode()`) happens only at
output time. This avoids UTF-8 validation overhead on hot paths and enables
native binary format parsing.

### 3. Zero-Cost Instrumentation

The engine is generic over `I: Instrument`. In production, `NoOpInstrument` (a
ZST) is used — the compiler monomorphises and inlines all callback bodies to
nothing. In debug/UI mode, `RecordingInstrument` captures full match/capture/timing
data for the editor's output preview.

### 4. Scoped Variables

`ScopedVarRegistry` uses a stack of `HashMap` scopes. Each `CallTemplate`
invocation pushes a new scope, binds parameters, executes the body, then pops.
This prevents caller/callee variable leakage — a template's internal variables
are invisible to its caller.

### 5. Guard Pre-Filtering

Template `guard` conditions are evaluated against an empty `MatchRes` before
the match expression runs. This allows templates to be skipped cheaply based on
scope state (e.g., `__depth > 3`, `Exists($some_var)`) without paying the cost
of regex compilation or matching.

### 6. Progressive Matching

For binary/structured formats, the `Progressive` match expression provides a
composable sequence of typed steps. Steps can reference earlier captures
(e.g., read a length field, then `TakeBytes` that many bytes), enabling TLV
and other length-prefixed format parsing without regex.

### 7. NodeConfig as Intermediate Representation

`NodeConfig` remains as the IR between two active compilation paths:
- **Graph compiler** (`compiler.rs`) — editor UI → NodeConfig
- **Legacy migration** (`migration.rs`) — DS3 XML → NodeConfig

Both feed into `template_compiler.rs` → `ProjectConfig`. NodeConfig will be
deprecated when both compilers can target `ProjectConfig` directly.

---

## Test Infrastructure

### Unit Tests

Each module contains `#[cfg(test)] mod tests` with targeted unit tests.
The engine currently has **211 tests** covering:
- Template serialisation roundtrips
- Match expression correctness (delimiter, regex, progressive)
- Capture binding
- Body output (conditions, transforms, apply, call-template)
- Variable scoping
- Streaming/chunked reads
- Legacy XML migration
- Graph compilation/decompilation

### Fixture Tests

`engine/tests/fixtures/projects/` contains complete project configurations:

| Fixture | Tests |
|---|---|
| `apache_httpd` | Classic log parsing with delimiter + regex |
| `win_sec` / `win_sec_xml` | Windows Security event log (legacy XML migration) |
| `win_app` / `win_app_xml` | Windows Application event log |
| `ausearch` | Linux audit log with transforms |
| `identity_transform` | XML tag renaming with Choose |
| `json_to_xml` | JSON→XML conversion |
| `xml_to_json` / `xml_to_json_attrs` | XML→JSON conversion |
| `xml_to_json_unified` | Unified XML→JSON with attributes |

Each fixture has:
- `project.json` — the template configuration
- `example_input.*` — sample input data
- `example_output.*` — expected output for regression testing
