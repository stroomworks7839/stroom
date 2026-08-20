# Byte-Level Engine — Performance & Design Reference

Reference document covering design decisions, performance assessment, and future work for the DS3 byte-level engine refactor.

---

## 1. Completed Phases Summary

| Phase | Status | Key Change |
|-------|--------|------------|
| Phase 1: Store + Dead Code | ✅ | Stores hold `Vec<u8>`, removed `CharBuffer`/`Buffer` trait |
| Phase 2: Byte-Level Matching | ✅ | `regex::bytes::Regex`, `try_match(&[u8])`, `split_find_bytes`, `MatchRes.groups: Vec<Option<Vec<u8>>>` |
| Phase 3: Reader + Cleanup | ✅ | `ByteBuffer` backed by `Vec<u8>`, raw byte I/O, all bytes passed through unchanged |
| Phase 4: Encoding Wiring | ✅ | `encoding: Option<Encoding>` on nodes, `decode()`/`encode()` via `encoding_rs`, 31 encodings |
| Phase 5: Encoding-Aware Matchers | ✅ | MatchTag, TakeUntil, TakeWhile, TakeN, AnyChar all operate at byte level with encoding-aware code unit sizing |
| Phase 6: Eager Byte Compilation | ✅ | Split/MatchTag/TakeUntil store `String` + `_bytes`; one-time `compile_byte_literals()` after BOM detection; zero per-match `encode()` |

---

## 2. Design Decisions

### Everything is Bytes

The core principle: bytes flow from reader → buffer → matcher → store → resolve_ref. Text decoding only occurs at the `resolve_ref` boundary when a stored value is consumed for output, conditions, or transforms.

### Encoding-Agnostic Configuration

The config tree stores all string literals as `String` (e.g. `delimiter: String`, `text: String`). This keeps the configuration portable and serializable without encoding assumptions. Encoding-specific byte representations are compiled in-place after BOM detection at runtime.

### Eager Byte Compilation

```
Config Build Time            First Read                 Matching
─────────────────       ──────────────────────      ──────────
NodeConfig stores       fill_buffer() → BOM →       try_match reads
String values           compile_byte_literals()     compiled byte fields
(encoding-agnostic)     fills byte fields in-place  directly — zero lookup
                        compile_regexes() runs
```

**No HashMap. No cache. No per-match encoding.** The tree is compiled in-place once after encoding is known. `try_match` reads `_bytes` fields directly.

### Encoding Inheritance

Encodings resolve during the `compile_byte_literals()` tree walk via `resolve_encoding(node_encoding, parent_encoding)`. If a node's `encoding` is `None`, it inherits from its parent; root defaults to `Utf8` (or BOM-detected encoding).

### Comprehensive Node Encoding Assessment

| Node | Encoding at Build Time? | Encoding at Match Time? | How |
|------|------------------------|------------------------|-----|
| **Split** | ✅ `compile_byte_literals()` | ❌ Zero | Reads `delimiter_bytes`/`escape_bytes`/`container_*_bytes` directly |
| **MatchTag** | ✅ `compile_byte_literals()` | ❌ Zero | Reads `text_bytes` directly |
| **MatchTakeUntil** | ✅ `compile_byte_literals()` | ❌ Zero | Reads `pattern_bytes` directly |
| **MatchTakeWhile** | — No string literals | ❌ Zero | `predicate.matches_byte(b)` — pure byte predicate |
| **MatchTakeN** | — No string literals | ⚡ Trivial | `encoding.code_unit_size()` — O(1) integer per char |
| **MatchAnyChar** | — No string literals | ⚡ Trivial | `encoding.code_unit_size()` — O(1) integer |
| **Regex** (bytes) | ✅ `compile_regexes()` | ❌ Zero | `regex::bytes::Regex` on `&[u8]` |
| **Regex** (fancy) | ✅ `compile_regexes()` | ⚠️ `from_utf8` | `fancy_regex` requires `&str` — validation only |
| **Reverse** | — | ⚠️ Full decode+encode | Must decode→reverse chars→re-encode (dynamic input) |
| **All** | — | ❌ Zero | Consumes entire buffer slice |
| **Group / Sequence / Choice / Optional / Repeat** | — | ❌ Zero | Structural — pass bytes to children |
| **Delimited / Separated / Peek / Not** | — | ❌ Zero | Structural combinators |
| **Data / Var / Switch / Choose / If** | — | ❌ Zero | Output/variable resolution at `resolve_ref` time |
| **MatchByte / ReadNumeric / TakeBytes** | — | ❌ Zero | Pure byte operations |
| **Root** | — | ❌ Zero | Header/footer written as UTF-8 strings |

### Encoding Safety for Byte-Level Matching

`compile_byte_literals()` correctly encodes delimiters for all encodings via `encoding_rs`. `split_find_bytes` advances by `encoding.code_unit_size()` per position, ensuring correct alignment for fixed-width encodings.

| Encoding Category | Status | Notes |
|---|---|---|
| UTF-8 | ✅ Safe | Self-synchronising — no continuation byte matches ASCII |
| All single-byte (Latin-1, Windows-1252, KOI8-R, ISO-8859-x) | ✅ Safe | Every byte is one character, `code_unit_size()` = 1 |
| UTF-16LE / UTF-16BE | ✅ Safe | `code_unit_size()` = 2; scan advances 2 bytes at a time; delimiters compiled to correct 2-byte form |
| EUC-JP / EUC-KR | ⚠️ Low risk | `code_unit_size()` = 1 (variable-length); trail bytes 0xA1–0xFE don't overlap common ASCII delimiters |
| Shift_JIS | ❌ Unsafe | `code_unit_size()` = 1 (variable-length); trail bytes 0x40–0x7E overlap ASCII — scan may land on trail byte and false-match a single-byte delimiter |
| GBK / Big5 | ❌ Unsafe | `code_unit_size()` = 1 (variable-length); trail bytes overlap ASCII range — same false-match risk as Shift_JIS |

---

## 3. Hot-Path Performance Assessment

### The Engine Loops

The engine has two nested loop levels:

**Outer root loop** (`parse_with_captures`) — iterates over the buffer, advancing 1 byte on unmatched content:

```
loop {
    for child in root.children() {
        process_top_expression(child, reader, ...);
    }
    if no_progress && buffer.length > 0 {
        reader.move_forward(1);  // skip 1 byte of unmatched content
    }
    reader.fill_buffer()?;
}
```

**Inner expression loop** (`process_top_expression`) — the real hot path, runs once per expression match:

```
loop {
    if buffer.is_empty() { break; }
    if match_count >= max_match { break; }
    mr = try_match(regexes, node, buffer.as_bytes(), encoding);  // ← HOT
    match mr {
        Some(mr) => {
            process_match_children(mr, ...);   // ← HOT
            reader.move_forward(mr.advance);   // ← HOT
            reader.fill_buffer()?;
        }
        None => break,                         // no match = done
    }
}
```

For a 100 MB log file with 1M records, the inner loop runs ~1M+ times.

### Per-Operation Cost

| Operation | Cost | Allocations | Notes |
|-----------|------|-------------|-------|
| `buffer.as_bytes()` | O(1) | 0 | Pointer+length into existing `Vec<u8>` |
| `try_match` (Regex bytes) | O(n × pattern) | 0 per attempt | Groups allocate `Vec<u8>` only on success |
| `try_match` (Fancy regex) | O(n) | 1 validation | `from_utf8` is validation-only; allocates on success |
| `try_match` (Split) | O(d) per position | 0 | Byte-slice comparison — `delimiter_bytes` read directly from compiled field |
| `try_match` (MatchTag) | O(t) | 0 | Byte-slice `starts_with(text_bytes)` — pre-compiled, zero encode |
| `try_match` (TakeUntil) | O(n × p) | 0 | `data.windows(pattern_bytes.len())` — pre-compiled, zero encode |
| `try_match` (TakeWhile) | O(n) | 0 | `predicate.matches_byte(b)` per code unit |
| `try_match` (TakeN) | O(n) | 0 | `code_unit_size()` counting — O(1) per char |
| `try_match` (AnyChar) | O(1) | 0 | Single `code_unit_size()` lookup |
| `reader.move_forward(n)` | O(n) | 0 | Byte-by-byte scan for line/column tracking (counts `\n`, UTF-8 continuation bytes) |
| Store capture | O(g) | 1 per group | `group_bytes.to_vec()` copies matched bytes |
| `resolve_ref` | O(parts) | 0–1 | Builds `String` from parts; UTF-8 derived vars with no transforms bypass via `resolve_ref_bytes` (zero-alloc) |
| `decode()` | O(n) | 0–1 | Zero-copy for UTF-8 (`Cow::Borrowed` via `from_utf8_lossy`); allocates for non-UTF-8 |

### Allocation Profile (Typical CSV Record)

| Phase | Allocations |
|-------|-------------|
| Match (Split on `\n`) | 1 |
| Inner match (Split on `,`) | 3 |
| Store captures (`Var`) | 0–3 |
| Resolve refs (Data output) | 0–3 |
| **Total** | **~5–8** (vs ~20+ pre-refactor) |

### Key Performance Characteristics

1. **Zero-allocation matching** — regex operates directly on `&[u8]` buffer slices
2. **Zero-copy buffer access** — `buffer.as_bytes()` returns a pointer, no copy
3. **Lazy decoding** — `decode()` only called when a stored value is consumed for output/conditions
4. **UTF-8 zero-copy decode** — `decode()` returns `Cow::Borrowed` for valid UTF-8 (via `from_utf8_lossy`)
5. **Single-byte delimiter fast path** — degenerates to byte equality check
6. **Zero per-match encode** — all string literals pre-compiled to `_bytes` fields during `compile_byte_literals()`; `try_match` reads compiled fields directly
7. **Encoding-aware regex compilation** — `compile_regexes()` uses BOM-detected encoding, not hardcoded UTF-8
8. **`resolve_ref_bytes` fast path** — derived vars with UTF-8 encoding and no transforms skip the decode→encode round-trip entirely
9. **Pre-compiled Replace regexes** — `TransformNode::Replace` patterns compiled once into `RegexCache`, not per invocation
10. **Stack-based integer formatting** — `itoa::Buffer` avoids heap allocation for `ForEach` iteration counters

### What's NOT on the Hot Path

| Operation | When | Cost |
|-----------|------|------|
| `compile_byte_literals()` | Once, after BOM detection | O(nodes × 6 fields) — trivial |
| `compile_regexes()` | Once, after BOM detection | O(regex count × pattern length) |
| BOM detection (`fill_buffer`) | Once, first read | O(4) — checks 2–4 byte prefix |
| `encode()`/`decode()` | Only in `Reverse` node + `resolve_ref` output | O(n) per call |

---

## 4. Comparison vs Hand-Coded Parsers

### Throughput Estimates

| Parser | MB/s | Architecture |
|--------|------|-------------|
| `awk '{print $3}'` | 200–400 | Interpreted, line-at-a-time |
| `grep -oP` (PCRE) | 500–1000 | Compiled NFA/DFA, streaming |
| `jq .field` | 100–300 | Interpreted, full JSON parse |
| Hand-coded C | 1000–3000 | Zero-copy, manual state machine |
| Hand-coded Rust (`nom`/`winnow`) | 800–2000 | Zero-copy combinator, compiled |
| **DS3 engine** | **300–800** | Byte-level regex+split, recursive tree |

### Why DS3 is Slower

1. **Regex NFA/DFA overhead** — per-byte state transitions vs hand-coded `memchr`
2. **Recursive tree walk** — function call overhead vs flat loop
3. **Dynamic dispatch** — `match node { ... }` vs compile-time structure
4. **Per-group allocation** — `Vec<u8>` copy vs `&[u8]` slice borrowing
5. **`resolve_ref` allocates for complex refs** — concatenated refs still build a `String` from parts (simple refs now have a bytes fast path)

### Why DS3 is Competitive

1. **Configuration-driven** — one engine handles CSV, fixed-width, regex, XML, syslog
2. **Correctness** — handles Unicode, escaping, containers, nesting
3. **Composability** — arbitrarily deep node nesting
4. **Maintainability** — config change (UI drag-and-drop), not code change

---

## 5. Future Optimisation Opportunities

| Technique | Impact | Effort | Status |
|-----------|--------|--------|--------|
| **`memchr` for single-byte split** — SIMD-accelerated delimiter scanning in `split_find_bytes` | 1.3–2× for split-heavy configs | Low | ✅ Implemented |
| **`SmallVec` for groups** — avoid heap alloc when group count ≤ 4–8 | ~10% reduced allocation pressure | Low | ✅ Implemented |
| **`resolve_ref_bytes` fast path** — bypass decode→encode for UTF-8 derived vars | Moderate for output-heavy configs | Low | ✅ Implemented |
| **Pre-compiled Replace regexes** — compile `TransformNode::Replace` patterns into `RegexCache` | Eliminates per-call regex compilation | Low | ✅ Implemented |
| **`itoa` stack formatting** — stack-based integer formatting for `ForEach` counters | Eliminates heap alloc per iteration | Low | ✅ Implemented |
| **`resolve_ref` Cow return** — return `Cow<str>` for single-part refs (avoids `String` build) | Moderate for output-heavy configs | Medium | Not started |
| **Regex-to-combinator compilation** — simple patterns → direct byte scans at build time | 2–3× for simple patterns | High | Not started |
| **`memchr` for `move_forward`** — SIMD newline counting in line tracker | Medium for large records | Low | Not started |
| ~~**Group slice borrowing**~~ | ~~1.2–1.5× fewer allocs~~ | ~~Medium~~ | Dropped — lifetime propagation complexity outweighs gain over `SmallVec` |
| ~~**Arena allocator for stores**~~ | ~~Medium~~ | ~~Medium~~ | Dropped — `Vec<u8>` values cross scope boundaries |
| ~~**Batch/parallel processing**~~ | ~~2–4× on multi-core~~ | ~~High~~ | Dropped — parallelism achieved at pipeline level |

See `future_optimisations.md` for detailed descriptions of remaining items.

### NFA/DFA Explained

- **NFA** (Non-deterministic Finite Automaton): regex compiles to a graph with multiple possible transitions per input byte. Engine tracks all active states simultaneously. Handles all features but has per-byte overhead.
- **DFA** (Deterministic Finite Automaton): one transition per (state, byte) pair — table lookup. Blazingly fast but tables can be large. `regex` crate uses a lazy/hybrid DFA.
- **The gap**: even the fast DFA needs one table lookup per byte. A hand-coded parser for `^(\S+) (\S+)` just calls `memchr(' ')` twice.
