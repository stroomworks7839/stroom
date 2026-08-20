# Byte-Level Engine Architecture Plan

## Design Principle

**Everything is bytes.** The reader holds bytes. Stores hold bytes. Matched groups are bytes. Only nodes that specifically need character semantics handle encoding internally.

---

## Key Decisions

### Which nodes need encoding?

| Node | Needs encoding? | Why |
|------|----------------|-----|
| **Regex** (standard) | Compilation only | `regex::bytes::Regex` compiles with Unicode mode flag depending on encoding. Matching is on `&[u8]` directly. |
| **Regex** (fancy) | Yes — matching | `fancy_regex` requires `&str`. Decode input bytes → `&str`, match, re-encode groups to bytes. |
| **Split** | Compilation only | Delimiter `char[]` compiled to byte pattern at build time. Matching is byte comparison. |
| **Reverse** | Yes | Decode bytes → reverse chars → re-encode to bytes. |
| **Group** | No | Passes bytes through to child expressions. Reversal is handled by dedicated Reverse nodes. |
| **Data** | Yes — output | Needs encoding to decode stored byte values for string interpolation and output formatting. |
| **MatchByte / ReadNumeric / TakeBytes** | No | Pure byte operations. |
| **All / TakeN / TakeUntil / etc.** | No | Operate on byte spans. |

### Regex matches bytes directly

`regex::bytes::Regex` (from the `regex` crate) matches `&[u8]`:
- Returns byte offsets — no conversion needed
- Captured groups are `&[u8]` slices
- Unicode selectively enabled via `(?u:...)` flag

`fancy_regex` has **no bytes mode** — `&str` only. Used as fallback for PCRE features (backreferences, lookaheads). These patterns are rare; the common fast path is pure bytes.

### Split matches bytes directly

Split delimiters, escape sequences, and container markers are currently `&[char]`. In the byte-level design:
- Compile delimiter chars to byte patterns at config build time (e.g., `'|'` → `[0x7C]`)
- `split_find()` does byte-slice comparison: `input[pos..].starts_with(&delimiter_bytes)`
- No string conversion needed at match time

### Stores hold `Vec<u8>`

Captured values stored as raw bytes. Nodes that need text decode on retrieval using the inherited encoding.

### Reverse operates on decoded text

The reverse–regex–reverse pattern:
1. Reverse node receives `&[u8]` from input
2. Decodes to string using inherited encoding
3. Reverses chars
4. Re-encodes to bytes for child node matching
5. Child results (byte groups) are decoded, reversed, re-encoded

Group does not need encoding — it passes bytes directly to child expression nodes. Character reversal is handled by dedicated Reverse nodes wrapping the Group's children, not by a flag on Group itself.

### No `\r` filtering in reader

Reader passes all bytes through. `\r` stripping is a transform concern.

---

## Performance Assessment

### Principle: convert once, at the right boundary

The current implementation converts on **every match attempt** — `reader.buffer.as_string()` (line 228) allocates a new `String` from the entire `Vec<char>` buffer on every loop iteration. In the byte-level design, conversions happen only where nodes need text semantics.

### Cost comparison per operation

| Operation | Current (chars/strings) | Proposed (bytes) |
|-----------|------------------------|------------------|
| **Read into buffer** | O(n) char decode per byte (`read_char()`) | O(n) raw `read()` — no decode |
| **Per match attempt** | O(n) `as_string()` allocation | O(1) `&[u8]` slice — zero alloc |
| **Regex matching** | Zero (already `String`) | Zero for `regex::bytes` on `&[u8]`; O(n) `from_utf8` for `fancy_regex` fallback |
| **Split matching** | O(n) `chars().collect()` + char comparisons | O(1) byte slice `starts_with()` |
| **MatchByte/ReadNumeric** | O(n) `as_bytes()` roundtrip (chars→string→bytes) | Zero — already `&[u8]` |
| **Store capture** | O(n) `CharBuffer::from_str()` (string→chars) | O(n) byte copy (`.to_vec()`) |
| **Resolve ref (store lookup)** | O(n) `buf.as_string()` (chars→string) | O(n) `from_utf8_lossy` — same cost, but only at the resolve boundary, not per-match |
| **Condition/Transform/Data** | Works on `String` — no change | Unchanged — `resolve_ref` returns `String` |

### Key performance wins

1. **Eliminated per-match allocation**: The biggest win. Currently every `try_match()` call forces `as_string()` on the entire buffer window. With bytes, matching operates on a `&[u8]` slice — zero allocation.

2. **Eliminated double conversion for byte nodes**: `MatchByte` and `ReadNumeric` currently go bytes→chars→string→bytes. Now they receive `&[u8]` directly.

3. **Split matching is faster**: Byte-slice `starts_with()` vs char-by-char comparison.

4. **Decode happens once at `resolve_ref`**: String conversion only occurs when a stored value is resolved for use by conditions, transforms, or output — not during matching.

### Potential cost increase

- `fancy_regex` patterns (backreferences, lookaheads) now require a `std::str::from_utf8()` validation + decode at the match site. This is O(n) but only for the rare fancy path. `from_utf8()` is validation-only — no allocation if the bytes are valid UTF-8.

---

## Proposed Changes

### Phase 1: Byte-level stores + dead code removal

#### [MODIFY] [store.rs](file:///home/stroomdev66/stroomworks_work/ds-rs/engine/src/store.rs)
- `Vec<Option<CharBuffer>>` → `Vec<Option<Vec<u8>>>`
- `set()` accepts `Vec<u8>`; `get()` returns `Option<&[u8]>`

#### [MODIFY] [engine.rs](file:///home/stroomdev66/stroomworks_work/ds-rs/engine/src/engine.rs)
- Store captured values as bytes
- Resolve refs return bytes
- Remove all `CharBuffer` usage

#### [MODIFY] [buffer.rs](file:///home/stroomdev66/stroomworks_work/ds-rs/engine/src/buffer.rs)
- Remove `Buffer` trait, `ReverseBuffer`, `AsRef<str>` stub
- Keep `CharBuffer` temporarily for reader (removed in Phase 2)

---

### Phase 2: Byte-level reader + byte-mode regex + encoding wiring

#### [MODIFY] [engine.rs — CompiledRegex](file:///home/stroomdev66/stroomworks_work/ds-rs/engine/src/engine.rs)
```rust
enum CompiledRegex {
    Bytes(regex::bytes::Regex),    // common — matches &[u8]
    Fancy(fancy_regex::Regex),     // fallback — needs &str
}
```

#### [MODIFY] [engine.rs — try_match](file:///home/stroomdev66/stroomworks_work/ds-rs/engine/src/engine.rs)
- Signature: `fn try_match(... data: &[u8]) -> Option<MatchRes>`
- `MatchRes.groups`: `Vec<Option<Vec<u8>>>`
- `MatchRes.advance`: byte count
- Split: delimiter compiled to bytes, matching is `&[u8]` comparison
- Reverse: decode → reverse chars → re-encode → pass to child

#### [MODIFY] [engine.rs — split_find](file:///home/stroomdev66/stroomworks_work/ds-rs/engine/src/engine.rs)
- Change `delimiter: &[char]` → `delimiter: &[u8]`
- Change `escape: Option<&[char]>` → `escape: Option<&[u8]>`
- All pattern comparisons become byte-slice `starts_with`

#### [MODIFY] [reader.rs](file:///home/stroomdev66/stroomworks_work/ds-rs/engine/src/reader.rs)
- Replace `Vec<char>` with `Vec<u8>` byte buffer
- `fill_buffer()` reads raw bytes — no filtering, no char decoding
- Line tracking: scan for `\n` bytes for error reporting
- Wire in `detect_bom()` on first fill

#### [MODIFY] [encoding.rs](file:///home/stroomdev66/stroomworks_work/ds-rs/engine/src/encoding.rs)
- Wire `resolve_encoding()` into engine's node recursion
- Add `fn decode(bytes: &[u8], enc: Encoding) -> Cow<str>`
- Add `fn encode(text: &str, enc: Encoding) -> Vec<u8>`

#### [DELETE] [buffer.rs](file:///home/stroomdev66/stroomworks_work/ds-rs/engine/src/buffer.rs)
- No longer needed

---

### Phase 3: Future enhancements
- Regex-to-combinator compilation (simple patterns → TakeBytes/Sequence at compile time)
- Multi-byte encoding support (UTF-16 byte-offset mapping)
- Per-node encoding overrides in project.json / UI

## Verification

```bash
cargo test -p datasplitter-rs
cargo check 2>&1 | grep warning
```

Add new tests: BOM detection, non-ASCII UTF-8 byte offsets, binary data passthrough.
