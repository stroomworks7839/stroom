# Design & Implementation Plan: Supporting Avro, Parquet, and Protobuf in Shapeshifter

This document outlines the architectural analysis, challenges, and proposed design paths for adding Avro, Parquet, and Protobuf parsing support to the Shapeshifter engine.

## Goal Description
Support parsing of structured binary formats in Shapeshifter. 

To balance flexibility and performance, we propose a hybrid architecture:
1.  **Hybrid Parsing**: Support both low-level interpreted binary match steps (seeks, varints, decompression) and high-level native matchers (Avro, Parquet, Protobuf).
2.  **Typed Variable Registry (`TypedValue`)**: Transition variable storage from unstructured byte arrays (which required string serialization for numeric checks) to a typed variable system. This enables storing raw captured bytes alongside parsed integers/floats, allowing direct mathematical lookups (e.g., offsets and lengths) without string parsing.

---

## Technical Architecture: Typed Variable Subsystem

Currently, the `Store` registry holds values as raw string-formatted bytes (`Vec<u8>`). To eliminate the serialization overhead for numeric offsets and sizes, we introduce typed storage.

### 1. The `TypedValue` Enum (`engine/src/engine/store.rs`)
Replace unstructured storage with a typed model:
```rust
#[derive(Debug, Clone, PartialEq)]
pub enum TypedValue {
    /// Raw captured bytes (default).
    Bytes(Vec<u8>),
    /// Decoded signed integer.
    Int(i64),
    /// Decoded floating-point number.
    Float(f64),
    /// Decoded boolean.
    Bool(bool),
}
```

On `Store`, the values vector is refactored:
```rust
pub struct Store {
    values: Vec<Option<TypedValue>>,
}
```

#### `TypedValue` API Surface

`TypedValue` needs a small set of conversion methods that centralise the
formatting/parsing logic and prevent ad-hoc string handling from leaking into
the hot path:

```rust
impl TypedValue {
    /// Return the integer value, or attempt to parse Bytes as decimal.
    pub fn as_i64(&self) -> Option<i64> { ... }

    /// Return the integer as usize (for lengths, offsets).
    pub fn as_usize(&self) -> Option<usize> { ... }

    /// Return the float value, or attempt to parse Bytes.
    pub fn as_f64(&self) -> Option<f64> { ... }

    /// Format to UTF-8 string (used ONLY at output boundary).
    pub fn to_string_lossy(&self) -> Cow<str> { ... }

    /// Return raw bytes, or format numeric types to bytes.
    /// Used by write_ref for output.
    pub fn as_bytes_cow(&self) -> Cow<[u8]> { ... }
}
```

> [!IMPORTANT]
> The `as_bytes_cow()` method is used by `write_ref` and `resolve_ref_bytes`
> to produce byte output from typed values. For `TypedValue::Int(42)`, it
> formats to `b"42"` using `itoa::Buffer` (stack-allocated, zero-heap).
> For `TypedValue::Float`, use `ryu::Buffer` (fast float-to-string).
> This keeps formatting off the hot match path while supporting output writing.

---

### 2. Capture Binding Types & Casting Rules (`engine/src/project.rs`)
We add a `dataType` field to variable captures so users can specify how captured bytes are bound and cast:

```rust
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum DataType {
    Bytes,
    String,
    Integer,
    Float,
    Boolean,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CaptureBinding {
    pub name: String,
    pub select: CaptureSource,
    #[serde(default = "default_data_type", rename = "data-type")]
    pub data_type: DataType,
}

fn default_data_type() -> DataType {
    DataType::Bytes
}
```

#### Variable Casting & Binding Logic:
When binding a capture to a variable name:
1.  **If the source is already typed** (e.g., a `ReadVarint` match step returns a `TypedValue::Int(15)`):
    *   If `data_type` is `Integer` or `Bytes` (default), store it directly as `TypedValue::Int(15)`.
    *   If `data_type` is `String`, format it to decimal string bytes and store as `TypedValue::Bytes(b"15".to_vec())`.
2.  **If the source is untyped bytes** (e.g., a regex group capture returns raw bytes `b"123"`):
    *   If `data_type` is `Integer`, attempt to parse the bytes as a UTF-8 decimal string into `i64`. If successful, store as `TypedValue::Int(123)`. Otherwise, fallback to `TypedValue::Bytes`.
    *   If `data_type` is `Float`, parse as `f64` and store as `TypedValue::Float(123.0)`.
    *   If `data_type` is `Bytes` (default), store directly as `TypedValue::Bytes(b"123".to_vec())`.

---

### 3. Impact on Matching & Step Execution
*   **Integer Capture**: Match steps that read numbers (e.g., `ReadNumeric` and `ReadVarint`) will return their numeric outputs directly as `TypedValue::Int` or `TypedValue::Float`. They no longer perform string formatting (`itoa`/`format!`) during the match-consume phase.
*   **Zero-Overhead Ref Resolution**: When a subsequent step (like `TakeBytes` or `Seek`) references a prior step's output (e.g., `TakeBytes(StepRef::StepOutput(1))`), the resolver reads `prior_outputs[idx]` directly:
    *   **Native Path**: If the value is `TypedValue::Int(n)`, return `n as usize` directly.
    *   **Bytes Fallback**: If the value is `TypedValue::Bytes(bytes)` (e.g. from a regex capture of ASCII digits), attempt to parse the bytes as a UTF-8 decimal string into `usize`.
*   **Output Formatting**: When a variable is written to the output stream (e.g., `<xsl:value-of select="@length"/>`), the engine converts the `TypedValue` to its string representation at the output boundary.

---

## High-Level Native Matchers

By extending `MatchExpression`, we can parse common binary formats using native Rust crates at native speeds, injecting parsed record fields directly into Shapeshifter's variable registry.

### 1. MatchExpression Schema Extensions (`engine/src/project.rs`)
```rust
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum MatchExpression {
    Regex { pattern: String, flags: RegexFlags, advance: i32 },
    Delimiter { delimiter: String, escape: Option<String>, container_start: Option<String>, container_end: Option<String> },
    Progressive(Vec<MatchStep>),
    All,
    Source,
    Named,

    // ── High-Level Native Matchers ──
    Avro { schema: Option<String> },
    Parquet { columns: Option<Vec<String>> },
    Protobuf { descriptor_path: String, message_type: String },
}
```

### 2. Variable Binding
When a native matcher executes, the engine runs the native decoder (e.g., `parquet-rs` or `prost`), and iterates through records, binding fields as `TypedValue` variants directly into the registry stack (e.g., an integer field is stored as `TypedValue::Int`).

---

## Detailed Codebase Modifications

### 1. Dependency Additions (`engine/Cargo.toml`)
```toml
[dependencies]
# Encoding codecs (always-on — small, pure Rust, no transitive deps)
base64 = "0.22"
hex = "0.4"
percent-encoding = "2.3"

# Compression codecs (feature-gated)
flate2 = { version = "1.0", optional = true }
snap = { version = "1.0", optional = true }
zstd = { version = "0.13", optional = true }
lz4_flex = { version = "0.11", optional = true }

# Native format matchers (feature-gated)
apache-avro = { version = "0.16", optional = true }
parquet = { version = "52.0", features = ["arrow"], optional = true }
prost = { version = "0.12", optional = true }
prost-types = { version = "0.12", optional = true }

# Output formatting
ryu = "1"                  # Fast float-to-string for TypedValue::Float output
```

> [!TIP]
> Encoding codecs (base64, hex, url) are always-on — they add negligible
> compile time and are broadly useful. Compression and native format crates
> are gated behind feature flags to keep the default build lean.
>
> ```toml
> [features]
> default = []
> compression = ["dep:flate2", "dep:snap", "dep:zstd", "dep:lz4_flex"]
> binary-formats = ["compression", "dep:apache-avro", "dep:parquet", "dep:prost", "dep:prost-types"]
> ```

### 2. Execution Refactoring (`engine/src/engine/matching.rs`)
Update `execute_step` to return typed step results:
```rust
struct StepResult {
    output: TypedValue,
    bytes_consumed: usize,
}

fn execute_step(
    step: &CompiledMatchStep,
    data: &[u8],
    encoding: Encoding,
    prior_outputs: &[TypedValue],
) -> Option<StepResult>
```

### 3. Reference Resolution (`engine/src/engine/core.rs`)
Update `resolve_ref` and `write_ref` to format `TypedValue` variants (Int, Float, Bool) to text on the fly during final output writing.

---

## Performance Assessment: Native vs. Interpreted Primitives

| Aspect | High-Level Native Matcher | Low-Level Custom Steps (Interpreter) |
| :--- | :--- | :--- |
| **Throughput** | **$1.0\times$ baseline** (Fastest: GB/s range) | **$0.05\times - 0.25\times$** (Previously $0.01\times$ - typed values reduce bottleneck by ~3x) |
| **String Formatting**| Zero-copy conversion where possible. | Avoided entirely during match; only executed during final body output writing. |
| **Memory Allocation**| Highly optimized pre-allocated arrays. | Minimal (avoids string allocation for numeric steps). |

---

## UI Design & Impact (`node-editor/`)

Update the progressive match interface in `node-editor/src/template_editor/template_detail.rs`:
1.  **Step Selector**: Add options for `ReadVarint`, `ReadVarintZigZag`, `Seek`, `Tell`, and `Decompress`.
2.  **Match Expression panel**: Add config fields for Avro schema override and Protobuf descriptor/type parameters.
3.  **Capture Binding Type selector**: Render a dropdown list to configure `dataType` (`Bytes`, `String`, `Integer`, `Float`, `Boolean`) on template capture fields.

---

## Verification & Benchmark Plan

### 1. Low-Level Atom Tests
Validate seeks, varints, and codec steps (compression + encoding) with
hand-crafted binary payloads.

### 2. Cast & Coercion Tests
Verify that `DataType` casting and XSLT-style condition coercion work
correctly across all `TypedValue` variants.

### 3. Native Format Comparative Tests
For each binary format (Avro, Parquet, Protobuf), build **two** test
harnesses that parse the same input file and produce the same output:

| Harness | How it parses | Purpose |
|---------|---------------|---------|
| **Native crate** | Direct Rust calls to `apache-avro`, `parquet-rs`, `prost` | Baseline correctness + throughput reference |
| **Shapeshifter template** | Project JSON with appropriate `MatchExpression` variant | Verify shapeshifter produces identical output |

Test procedure for each format:
1.  Generate a reference dataset (e.g., 1000 records with known field values).
2.  Parse with the native crate, capture output.
3.  Parse with a shapeshifter template, capture output.
4.  Assert byte-identical output.
5.  Benchmark both, record throughput ratio.

### 4. Codec Round-Trip Tests
For each codec (base64, hex, url-encoding, deflate, snappy, zstd, lz4):
encode known data, decode it, assert original bytes are recovered.

### 5. End-to-End Integration
Full pipeline tests: read a real-world binary file → match with progressive
steps → decode/decompress embedded data → produce structured output.

---

## Deep Review: Additional Thoughts & Research

This section contains additional analysis from a thorough review of the plan
against the actual codebase, identifying gaps, risks, and design refinements.

### A. `MatchRes` / `GroupVec` — The Bridge Problem

The current plan focuses on `Store` → `TypedValue` and `execute_step` →
`StepResult { output: TypedValue }`, but there's a critical gap: the
**`MatchRes`** struct that bridges matching → capture binding.

Currently `MatchRes.groups` is `SmallVec<[Option<Vec<u8>>; 4]>`. When
`execute_step` starts returning `TypedValue`, the progressive match loop
(`try_match_progressive`) must propagate types through `MatchRes.groups` so
that `bind_captures` can store them correctly.

**Design decision**: Change `GroupVec` directly to `SmallVec<[Option<TypedValue>; 4]>`.
All matchers wrap their byte outputs in `TypedValue::Bytes(...)`. Progressive
matchers use `TypedValue::Int` etc. for numeric step outputs. This is a
mechanical change across all matcher paths — no parallel fields, no fallback
logic, one clean type throughout the pipeline.

---

### B. `StepRef::CaptureRef` → `StepRef::StepOutput` — ✅ COMPLETED

The original `CaptureRef(String)` resolution was broken: it ignored the name
entirely and used a heuristic (scan prior outputs in reverse for the first
parseable numeric value). This was fragile for templates with multiple numeric
steps.

**What was implemented** (not the originally proposed `HashMap` approach — we
went further):

1.  **Replaced `StepRef::CaptureRef(String)` with `StepRef::StepOutput(usize)`**
    in `project.rs`. The step index is a direct positional reference within
    the containing sequence. No name, no map, no heuristic.

2.  **Removed `capture_step_map`** from `CompiledTemplate` and the `compile()`
    function. The compile-time name→index mapping is no longer needed because
    the reference is already an index.

3.  **Hot-path cost is zero**: Resolution is `prior_outputs[idx]` — a direct
    array index. Both `resolve_step_ref` and `resolve_step_ref_split` are
    `#[inline]` with no string comparison or map lookup.

4.  **Local-first lexical scoping**: `resolve_step_ref_split` (used inside
    combinators like Repeat, Choice, Sequence) checks the local sibling scope
    before the parent scope. `StepOutput(0)` inside a
    `Repeat { [ReadNumeric, TakeBytes(StepOutput(0))] }` resolves to the
    sibling ReadNumeric output, not a parent step at the same index.

**Current code** (`engine/src/engine/matching.rs`):
```rust
#[inline]
fn resolve_step_ref(step_ref: &StepRef, prior_outputs: &[Vec<u8>]) -> Option<usize> {
    match step_ref {
        StepRef::Literal(n) => Some(*n),
        StepRef::StepOutput(idx) => {
            prior_outputs.get(*idx)
                .and_then(|output| std::str::from_utf8(output).ok()
                    .and_then(|s| s.parse::<usize>().ok()))
        }
    }
}

#[inline]
fn resolve_step_ref_split(step_ref: &StepRef, prior: &[Vec<u8>], local: &[Vec<u8>]) -> Option<usize> {
    match step_ref {
        StepRef::Literal(n) => Some(*n),
        StepRef::StepOutput(idx) => {
            // Check sibling scope first (lexical scoping — inner shadows outer).
            let output = if *idx < local.len() {
                local.get(*idx)
            } else {
                prior.get(*idx)
            };
            output.and_then(|o| std::str::from_utf8(o).ok()
                .and_then(|s| s.parse::<usize>().ok()))
        }
    }
}
```

**Node editor UI**: Updated to use `$N` syntax (e.g., `$0`, `$1`) instead
of `$name`. The `StepOutput` variant serializes as `{"StepOutput": 0}` in JSON.

**Tests**: 122 lib tests pass including a new `test_step_output_scoping_repeat`
that verifies local-first scoping inside Repeat combinators.

> [!NOTE]
> When `TypedValue` is implemented (Phase 2), `resolve_step_ref` will change
> from `parse::<usize>()` to `TypedValue::as_usize()`, eliminating the
> string-parse step entirely for numeric step outputs.

---

### C. `execute_step_sequence` / `execute_step_with_outputs` — Output Cloning

Currently, combinators (Choice, Repeat, etc.) clone `prior_outputs` to build
a combined view. With `TypedValue` replacing `Vec<u8>`, the cloning cost
changes:

- `TypedValue::Int(i64)` = 16 bytes (discriminant + value), `Copy`-eligible
- `TypedValue::Bytes(Vec<u8>)` = 24 bytes + heap, requires clone

This means cloning a `Vec<TypedValue>` is **more expensive** for
`Bytes` variants than cloning `Vec<Vec<u8>>` (same heap cost, larger enum on
the stack due to discriminant + padding).

**Mitigation**: The current implementation already optimises this with a
split prior/local approach in `execute_step_with_outputs`: `TakeBytes` resolves
against the split views without cloning (using `resolve_step_ref_split`),
and only non-TakeBytes combinators build the combined slice when needed for
recursive calls. The `TypedValue` change should be transparent here — just
swap the type parameter.

---

### D. Streaming & Seek Compatibility

The engine's streaming architecture (`exec.rs`, `fill_buffer` loop) reads
fixed-size chunks and processes them sequentially. Some binary formats require
random access (`Seek`), which is incompatible with pure streaming:

- **Avro**: Sequential data blocks after a header. Can be streamed. ✅
- **Protobuf**: Length-delimited sequential messages. Can be streamed. ✅
- **Parquet**: Footer at end of file, random-access column chunks.
  **Requires `Read + Seek`.** ❌ streaming

**Design decision — separate entry points by capability:**

The engine provides two entry points based on the input's capabilities:

```rust
// Streaming: stdin, pipes, sockets — no seek
pub fn parse_project<R: Read>(project: &Project, input: R, ...) -> Result<()>

// Seekable: files, Cursor<Vec<u8>> — supports random access
pub fn parse_project_seekable<R: Read + Seek>(project: &Project, input: R, ...) -> Result<()>
```

At template compile time, the engine checks whether any template requires
seeking (e.g., `Parquet` match expression, `Seek` match step). At runtime:

- If the caller uses `parse_project` (streaming) with a seekable-only format,
  the engine returns a clear error: *"Parquet requires a seekable input;
  use parse_project_seekable or provide a file path."*
- If the caller wants to pipe stdin into a seekable format, **that's their
  responsibility** — they buffer into a `Cursor<Vec<u8>>` before calling
  `parse_project_seekable`. The engine doesn't provide escape hatches or
  magic `buffer_size` values.

This keeps the engine simple (one contract per entry point, no special cases)
and memory-efficient (the Parquet crate can do selective column reads from
disk instead of loading the entire file).

> [!NOTE]
> Memory-mapped I/O (`memmap2`) is a future optimisation. The caller could
> pass a `memmap2::Mmap` (which implements `Read + Seek` via `Cursor`) for
> zero-copy reads from disk. This is transparent to the engine — it just
> sees a `Read + Seek` source.

The interpreted `Seek` match step (for low-level binary parsing) has a
different constraint: it operates within the current match buffer, not the
file. `Seek` is relative to the current chunk and cannot cross chunk
boundaries. This is acceptable for formats where the data block fits within
a single buffer (which is the common case — `buffer_size` can be increased).

---

### E. Varint Encoding Details & Edge Cases

The plan mentions `ReadVarint` and `ReadVarintZigZag` but doesn't specify
the encoding. There are multiple varint conventions:

| Format | Encoding | Max Bytes | Signedness |
|--------|----------|-----------|------------|
| Protobuf varint | LEB128 (unsigned) | 10 | unsigned base, ZigZag for signed |
| Avro varint | Vint / ZigZag | 10 | ZigZag for all integers |
| Thrift CompactProtocol | ZigZag varint | 10 | ZigZag |
| SQLite serial type | Huffman varint | 9 | variable |

Both Protobuf and Avro use the same underlying encoding (LEB128), but Avro
applies ZigZag encoding to *all* integer types, while Protobuf only uses
ZigZag for `sint32`/`sint64`.

**Recommendation**: Implement two steps:
- `ReadVarint` → standard LEB128 (unsigned), stores as `TypedValue::Int(n as i64)`
- `ReadVarintZigZag` → LEB128 + ZigZag decode, stores as `TypedValue::Int(n)`

Implementation is straightforward (< 20 lines each):

```rust
fn decode_varint(data: &[u8]) -> Option<(u64, usize)> {
    let mut result: u64 = 0;
    let mut shift: u32 = 0;
    for (i, &byte) in data.iter().enumerate() {
        if i >= 10 { return None; } // overflow guard
        result |= ((byte & 0x7F) as u64) << shift;
        if byte & 0x80 == 0 {
            return Some((result, i + 1));
        }
        shift += 7;
    }
    None // unterminated
}

fn zigzag_decode(n: u64) -> i64 {
    ((n >> 1) as i64) ^ -((n & 1) as i64)
}
```

---

### F. Codec Steps — Decode, Encode & Decompress

The original plan included only `Decompress`. We generalise this to a unified
codec step that covers compression, encoding, and data transformation:

```rust
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum Codec {
    // Encoding
    Base64,
    Base64Url,
    Hex,
    UrlEncoding,
    // Compression
    Deflate,
    Gzip,
    Snappy,
    Zstd,
    Lz4,
}

// Match steps
MatchStep::Decode { data: StepRef, codec: Codec },
MatchStep::Encode { data: StepRef, codec: Codec },
```

**Semantics**: Both `Decode` and `Encode` are **capture** steps:
-   `data` references a prior step's output (e.g., `StepOutput(2)`).
-   The step transforms those bytes and returns the result as
    `TypedValue::Bytes(transformed)` — a new step output.
-   Processing the transformed content (e.g., parsing decompressed Avro blocks)
    is done by a child template via `apply-templates select="@decoded_var"`,
    which feeds the variable's bytes as input to child templates. This follows
    the XSLT model where `apply-templates` accepts a `select` expression.

This avoids mutating the match buffer mid-execution (which would invalidate
position tracking) and composes cleanly with the existing architecture.

**Dependencies** (Phase 3):

| Codec | Crate | Notes |
|-------|-------|-------|
| Base64, Base64Url | `base64` | Zero-copy decode |
| Hex | `hex` | Or hand-rolled (~10 lines) |
| UrlEncoding | `percent-encoding` | RFC 3986 |
| Deflate, Gzip | `flate2` | Streaming decompress |
| Snappy | `snap` | Block-mode only (Avro uses this) |
| Zstd | `zstd` | Frame-mode |
| Lz4 | `lz4_flex` | Pure Rust, no C dependency |

> [!TIP]
> Encoding codecs (base64, hex, url) are small, pure-Rust crates with no
> transitive dependencies. They can be always-on (no feature gate needed).
> Compression codecs are larger and should be behind the `binary-formats`
> feature flag.

---

### G. `MatchRes` Size & Performance Impact

With `GroupVec` changed to `SmallVec<[Option<TypedValue>; 4]>` (section A),
`MatchRes` size increases slightly: `TypedValue` is ~32 bytes (discriminant +
largest variant `Bytes(Vec<u8>)`) vs 24 bytes for `Vec<u8>`. For the inline
capacity of 4 groups, that's ~32 bytes more on the stack.

This is negligible — `MatchRes` is created once per match and lives for the
duration of body execution. It's not in a tight loop. Groups are passed by
reference (`&MatchRes`) into `execute_body`, so no cloning occurs on the
happy path.

---

### H. Impact Surface Analysis

A complete audit of where `Store::get()` returns `&[u8]` and all call sites
need updating:

| Call Site | Current Behaviour | Required Change |
|-----------|-------------------|-----------------|
| `resolve_ref` (core.rs L754) | `decode(buf, encoding)` | Match on `TypedValue`: `Int` → `itoa`, `Bytes` → `decode` |
| `write_ref` (core.rs L638) | `writer.write_all(buf)` | Match on `TypedValue`: `Int` → `itoa` to stack buf, write |
| `resolve_ref_bytes` (core.rs L839) | `buf.to_vec()` | `TypedValue::as_bytes_cow().to_vec()` |
| `resolve_lookup_index` (core.rs L584) | `from_utf8 → parse::<usize>` | `TypedValue::as_usize()` |
| `evaluate_condition` (core.rs L525-533) | `resolve_ref → parse::<f64>` for GreaterThan/LessThan | XSLT-style coercion: if typed side is numeric, parse literal to number and compare numerically. String fallback otherwise. See section I. |
| `bind_captures` (body.rs L240) | Stores `Vec<u8>` | Must convert step outputs → `TypedValue` using `DataType` |
| `execute_transform_fn` (body.rs L768) | `resolve_ref → String` | Unchanged — transform functions operate on strings |
| `Condition::IsFirst/IsLast` (core.rs L553) | Compare `b"true"` | Could use `TypedValue::Bool(true)` — or keep as-is |
| `__match_idx` / `__match_count` (body.rs L117-131) | `itoa → Vec<u8>` | Store as `TypedValue::Int` — eliminates itoa for synthetics |

> [!TIP]
> The synthetic variables `__match_idx` and `__match_count` are set on every
> match iteration. Currently they format integers to bytes with `itoa`. With
> `TypedValue::Int`, this becomes a direct integer store — a small but real
> win on hot loops with many matches.

---

### I. Condition Semantics — XSLT-Style Type Coercion

Condition evaluation should follow XPath 1.0's type coercion rules rather
than relying on string formatting consistency. This eliminates the
`ryu`/`format!` formatting concern entirely.

**Coercion rules for `Condition::Equals(ref_expr, literal)`:**
1.  Resolve `ref_expr` → `TypedValue`.
2.  If the value is numeric (`Int` or `Float`): try to parse `literal` as a
    number. If parseable, compare numerically. Otherwise, format the number
    to a string and compare as strings.
3.  If the value is `Bool`: try to parse `literal` as boolean (`"true"`/`"false"`).
    If parseable, compare as booleans. Otherwise, format and compare as strings.
4.  If the value is `Bytes`: decode to string, compare as strings.

**Coercion rules for `GreaterThan` / `LessThan`:**
Both sides are coerced to `f64`. If either side cannot be converted to a
number, the comparison is false (same as XPath).

**Why this works:**

| Variable | Literal | Comparison | Result |
|----------|---------|------------|--------|
| `Int(42)` | `"42"` | `42 == 42` (numeric) | ✅ true |
| `Float(42.0)` | `"42"` | `42.0 == 42.0` (numeric) | ✅ true |
| `Int(42)` | `"42.0"` | `42 == 42` (numeric, both parsed) | ✅ true |
| `Bytes("hello")` | `"hello"` | string compare | ✅ true |
| `Int(42)` | `"forty-two"` | "forty-two" not parseable → `"42" == "forty-two"` | ✅ false |

String formatting (`ryu`, `itoa`) is only needed at the **output boundary**
(`write_ref`, `resolve_ref`), never during condition evaluation. The choice
of `ryu` vs `format!` for float output is purely a performance decision with
no impact on condition correctness.

---

### J. Phased Implementation Order

Given the scope of changes and the risk of breaking existing tests, the work
should be phased:

#### Phase 1: TypedValue Core (Minimal, High-Value)
1.  Define `TypedValue` enum in `store.rs` with conversion methods.
2.  Change `Store` to hold `Vec<Option<TypedValue>>`.
3.  Update `Store::get()` to return `Option<&TypedValue>`.
4.  Update `bind_captures` to construct `TypedValue::Bytes` for all existing
    captures (initial pass — captures default to `Bytes` until `DataType` is added in Phase 2).
5.  Update `resolve_ref`, `write_ref`, `resolve_ref_bytes`, `resolve_lookup_index`,
    `evaluate_condition` to accept `TypedValue`.
6.  Update synthetic vars (`__match_idx`, `__match_count`, `__foreach_is_first`,
    `__foreach_is_last`) to use `TypedValue::Int` / `TypedValue::Bool`.
7.  **Run all 1024 existing tests.** Zero regressions expected.

#### Phase 2: Typed Step Outputs (Progressive Match)
1.  Change `GroupVec` to `SmallVec<[Option<TypedValue>; 4]>`. Update all matchers to wrap byte outputs in `TypedValue::Bytes`.
2.  Update `StepResult.output` from `Vec<u8>` to `TypedValue`.
3.  Change `ReadNumeric` to return `TypedValue::Int` / `TypedValue::Float`.
4.  Update `try_match_progressive` to store `TypedValue` groups directly.
5.  Update `bind_captures` to use `TypedValue` from groups.
6.  ~~Fix `StepRef::CaptureRef` name-based resolution (see section B).~~ **✅ DONE** — replaced with `StepRef::StepOutput(usize)` with local-first lexical scoping.
7.  Add `DataType` field to `CaptureBinding` with `Bytes` default.
8.  **Run all tests + add typed capture tests.**

#### Phase 3: Binary Primitives & Codecs (New Match Steps)
1.  Add `ReadVarint`, `ReadVarintZigZag` to `MatchStep` / `CompiledMatchStep`.
2.  Add `Seek(StepRef)` and `Tell` to `MatchStep` / `CompiledMatchStep`.
3.  Add `Decode { data: StepRef, codec: Codec }` and `Encode { data: StepRef, codec: Codec }`.
4.  Define `Codec` enum: `Base64`, `Base64Url`, `Hex`, `UrlEncoding`, `Deflate`, `Gzip`, `Snappy`, `Zstd`, `Lz4`.
5.  Add dependencies: `base64`, `hex`, `percent-encoding`, `flate2`, `snap`, `zstd`, `lz4_flex`.
6.  Implement execution in `matching.rs`.
7.  **Add unit tests with hand-crafted binary payloads + codec round-trip tests.**

#### Phase 4: Native Format Matchers
1.  Add `Avro`, `Parquet`, `Protobuf` variants to `MatchExpression`.
2.  Gate behind `binary-formats` Cargo feature.
3.  Implement native decoders that emit `TypedValue` bindings.
4.  Handle Parquet's `Read + Seek` requirement via `parse_project_seekable` entry point (see section D).
5.  **Add integration tests with real Avro/Parquet/Protobuf files.**
6.  **Benchmark against native crate throughput.**

#### Phase 5: UI Updates
1.  Step selector additions for new match steps.
2.  Match expression panel for native format configuration.
3.  Capture binding type dropdown.

---

### K. Resolved Research Questions

1.  **Parquet column projection** — ✅ DECIDED: Support column projection.
    Add an optional `columns: Vec<String>` field to the `Parquet` match
    expression. When specified, only the listed columns are decoded.
    When omitted, all columns are decoded. Column projection is essential
    for wide schemas and `parquet-rs` supports it natively via
    `ProjectionMask`.

2.  **Nested Avro types** — ✅ DECIDED: Treat nested structures as
    sub-matches for child templates — the same approach used for JSON and
    XML nested formats. The native Avro decoder emits top-level fields as
    `TypedValue` bindings and nested records/arrays as sub-documents that
    child templates process via `apply-templates`. This is consistent with
    the existing recursive template architecture.

3.  **Protobuf descriptor loading** — ✅ DECIDED: File path (`descriptor_path`)
    is sufficient. All execution is server-side, not WASM or browser.
    No change needed.

4.  **Feature-gated compile times** — ✅ DECIDED: Use the 3-tier feature
    flag approach defined in §1 Dependencies: encoding codecs always-on,
    `compression` feature for compression crates, `binary-formats` feature
    for native format crates (includes `compression`).

5.  **`ryu` vs `format!` for float output** — ✅ DECIDED: Use `ryu` from
    the start. `ryu` is ~2-5× faster than `format!` for float-to-string
    and is already listed as a dependency. No correctness concern (§I).

---

### L. Future Work

Items discovered during implementation audits. None are correctness bugs;
all are hardening, performance, or design-gap improvements.

#### Performance

1.  **Protobuf descriptor caching** — `decode_protobuf_message` re-parses
    `DescriptorPool` on every call, giving O(n) descriptor parsing per
    record. The parsed pool and resolved `MessageDescriptor` should be
    cached in `CompiledMatch::Protobuf` at compile time and passed into
    the decoder.

#### Design Gaps

2.  **Avro multi-record streaming** — `decode_avro_record` sets
    `advance: data.len()`, consuming the entire OCF buffer on the first
    match. Only the first record is decoded via `try_match_template`.
    To handle multi-record OCF files, either wire `decode_avro_all` into
    a batch executor (Parquet-style) or implement per-record OCF cursor
    advancing so the streaming loop sees one record per iteration.

3.  **Parquet prologue/epilogue** — `execute_parquet` does not execute
    the Source template's prologue or epilogue body. Add support for
    source template body splitting in the Parquet path (analogous to
    `execute_source` in the streaming path).

4.  **Parquet instrumentation** — `parse_project_seekable` always uses
    `NoOpInstrument`. Add a `parse_project_seekable_with_instrument`
    variant for UI/debug use so the profiling panel can report Parquet
    template timing.

#### Correctness Edge Cases

5.  **Parquet nested schema projection** — `ProjectionMask::leaves`
    currently receives Arrow field indices, not Parquet leaf column
    indices. This is correct for flat schemas but would project wrong
    columns for schemas with struct, list, or map columns. For nested
    schemas, use `parquet_schema` column traversal to map column names
    to leaf indices.

#### Cosmetic

6.  **Avro `groups[0]` format** — Uses Rust `{:?}` Debug format instead
    of a meaningful representation (e.g. JSON via `serde_json`). The
    comment says "JSON representation" but the value is Debug-formatted.
    Non-blocking — the value is non-empty so skip-empty checks pass.
