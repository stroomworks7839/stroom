# Future Optimisations

Deferred performance optimisations identified during the Phase 1 and Phase 2 hot-path audits, plus items from the byte-level design reviews and the current data parsing/transformation hot-path audit. Ordered by estimated impact.

---

## Current Hot-Path Audit Additions

### A. `transform::execute_transform` — Cache Regex Replacements

**Source**: Current parsing/transformation hot-path audit

**Problem**: `TransformNode::Replace { is_regex: true, .. }` recompiles `regex::Regex` on every transform execution. In record-heavy data flows this moves regex compilation into the per-match hot path, which is substantially more expensive than executing a precompiled regex.

**Proposed fix**: Add a compile/preparation phase for transforms, similar to `compile_regexes`, that stores compiled regex replacements alongside the transform node or in a transform cache keyed by pattern. `execute_transform` should receive the compiled form or look it up from the cache instead of calling `regex::Regex::new` per invocation.

**Impact**: High when regex transforms are used per record or per field.
**Risk**: Low to Medium — localised change, but requires choosing where compiled transform state lives.

---

### B. `Switch` / `ValueMap` / Transform `Map` — Compile Lookup Maps

**Source**: Current parsing/transformation hot-path audit

**Problem**: `Switch`, `ValueMap`, and `TransformNode::Map` resolve values with linear scans over vectors. This is acceptable for small lists but becomes a hot-path cost for larger mapping tables or frequently evaluated branches.

**Proposed fix**: Compile lookup structures once:
- `SwitchCase` values → branch index via `HashMap<String, usize>`.
- `ValueMapEntry` values → mapped output via `HashMap<String, String>` or borrowed/arc-backed equivalent.
- `TransformNode::Map` entries → `HashMap<String, String>`.

For small maps, retain the vector scan if benchmarking shows it is faster below a low threshold.

**Impact**: Medium to High for large branch/map configurations.
**Risk**: Medium — requires adding compiled state to node structures or a side cache while preserving serialisable config shape.

---

### C. `DS3Reader::fill_buffer` — Avoid Temporary Allocation During Buffer Shift

**Source**: Current parsing/transformation hot-path audit

**Problem**: Buffer compaction currently copies the remaining window into a temporary `Vec` before copying it back to the start of the buffer. This allocates and copies twice whenever the reader shifts buffered content.

**Proposed fix**: Replace the temporary allocation with an in-place copy, e.g. `copy_within`, for the visible buffer range. This should move remaining bytes to the start without allocating.

**Impact**: Medium for large inputs with frequent partial-buffer shifts.
**Risk**: Low — contained change in reader buffer management.

---

### D. Scoped Variable Registry — Cache Variable Lookup Locations

**Source**: Current parsing/transformation hot-path audit

**Problem**: `ScopedVarRegistry::get` and `entry_mut` scan the scope stack from the current scope to global on every variable access. In nested `Group`, `ForEach`, and recursive processing, ref resolution and variable writes can cause repeated scope-stack traversals.

**Proposed fix**: Add a lookup cache for variable name → scope index, invalidated or updated on `push_scope`, `pop_scope`, and shadow-registration. Alternatively, compile frequently referenced variable IDs to stable registry slots where possible.

**Impact**: Medium for deeply nested or variable-heavy configurations.
**Risk**: Medium — correctness depends on preserving shadowing semantics across scope push/pop.

---

### E. Byte-Slice Reference Resolution — Avoid Owned `Vec<u8>` for Read-Only Refs

**Source**: Current parsing/transformation hot-path audit

**Problem**: `resolve_ref_bytes` returns `Option<Vec<u8>>`. Simple local and remote references often point at existing match groups or stored values, but the function clones bytes even when the caller only needs to read, write, or immediately decode them.

**Proposed fix**: Introduce a borrowed byte reference resolver, e.g. `resolve_ref_bytes_cow` returning `Cow<'_, [u8]>`, or a separate `resolve_ref_bytes_view` for simple local/remote references. Only allocate for complex refs that concatenate literals and values.

**Impact**: Medium — removes repeated byte clones in `Group`, `Recursive`, `KeyValueCapture`, and variable assignment paths.
**Risk**: Medium — lifetimes across match results and scoped stores need careful handling.

---


## 1. `resolve_ref` — Return `Cow<str>`

**Source**: Phase 2 audit (B3), byte-level performance design

**Problem**: `resolve_ref` returns `Option<String>`, forcing a heap allocation on every ref resolution even when the decoded `Cow<str>` could be returned borrowed.

**Proposed fix**: Change `resolve_ref` return from `Option<String>` to `Option<Cow<'_, str>>`. This eliminates an allocation in the `SimpleLocal` and `SimpleRemote` fast paths when the decoded value is borrowed. Approximately 15 call sites need updating.

**Impact**: Medium — eliminates one allocation per ref resolution (~thousands per parse run).
**Risk**: Medium — wide API surface change across ~15 call sites.

---

## 2. `split_memchr` / `GroupVec` — Full `Arc<[u8]>` Refactor

**Source**: Phase 1 audit (deferred), Phase 2 audit (A2 — conservative fix applied)

**Problem**: `GroupVec = SmallVec<[Option<Vec<u8>>; 4]>` means every group in every match result is an independently heap-allocated `Vec<u8>`. In `split_memchr`, groups 1/2/3 are identical byte slices but each is a separate allocation. The Phase 2 conservative fix reduced clones within `split_memchr` but didn't change the `GroupVec` type.

**Proposed fix**: Change `GroupVec` to `SmallVec<[Option<Arc<[u8]>>; 4]>` (or `Rc<[u8]>` if single-threaded is guaranteed). All matchers (`split_memchr`, `split_find_bytes`, `try_match`, regex matchers) would construct groups via `Arc::from(&data[start..end])`. Consumers that need owned data call `.to_vec()` only when mutating.

**Impact**: High — eliminates the majority of heap allocations in the matching hot path. For CSV data with hundreds of fields, this would remove thousands of allocations per parse.
**Risk**: High — changes the fundamental group storage type. All construction and consumption sites across `engine.rs`, `store.rs`, and ref resolution need updating. Should be done as a focused, isolated refactor with comprehensive before/after allocation profiling.

---

## 3. `transform::execute_transform` — Work with `&[u8]` Directly

**Source**: Phase 1 audit (deferred)

**Problem**: `execute_transform` operates on `&str` inputs and returns `String`. When transforms are applied to match groups (which are `Vec<u8>`), the caller must decode to `String`, transform, then re-encode to `Vec<u8>`. For transforms that don't inspect character boundaries (e.g. `Replace` with literal patterns, `Concat`), this decode/encode round-trip is unnecessary.

**Proposed fix**: Add a `execute_transform_bytes` variant that operates on `&[u8]` inputs and returns `Vec<u8>`. For transforms that are byte-safe (literal Replace, Concat, Trim of ASCII whitespace), operate directly on bytes. Fall back to the string path for transforms requiring Unicode semantics (Lowercase, Uppercase, NormalizeSpace, regex Replace).

**Impact**: Medium — eliminates decode/encode allocations for the common literal-transform case.
**Risk**: Medium — requires careful classification of which transforms are byte-safe.

---

## 4. Regex-to-Combinator Compilation

**Source**: byte-level performance design, byte-level plan review

**Problem**: Even the fast hybrid DFA used by the `regex` crate needs one table lookup per byte. Simple patterns like `^(\S+) (\S+)` could be compiled to direct `memchr` calls which run at 16–32 bytes/cycle via SIMD.

**Proposed fix**: At compile time, analyse regex patterns and convert simple ones to direct byte matchers. For example, `^([^,]+)` becomes a `memchr(',')` call. This would be a build-time optimisation pass that replaces Regex nodes with equivalent combinator trees or direct byte-scan functions.

**Impact**: High — 2–3× throughput improvement for simple regex patterns.
**Risk**: High — needs pattern analysis and code generation. Only applicable to a subset of patterns.

---

## 5. `memchr` for `move_forward` Line Scanning

**Source**: byte-level plan review

**Problem**: `reader.move_forward(n)` scans `n` bytes byte-by-byte looking for `\n` to update the line counter. For large advances (e.g. skipping a multi-KB record), this is O(n) with no SIMD acceleration.

**Proposed fix**: Use `memchr::memchr(b'\n', &buf[..n])` to count newlines in the advance region. `memchr` uses SIMD on x86/ARM for 16–32 bytes per cycle.

**Impact**: Medium — avoids byte-by-byte `\n` scan in `move_forward`. Most beneficial for configs with large record sizes.
**Risk**: Low — drop-in replacement for the inner loop.

---

## 6. PMC / PCS Code Deduplication

**Source**: Phase 2 audit (C1)

**Problem**: `process_match_children` and `process_children_slice` contain nearly identical match arms for Var, Data, Group, TransformOutput, ForEach, Choose, Switch, If, Recursive, and ValueMap. Any performance fix or bug fix must be applied to both functions, increasing maintenance burden and risk of divergence.

**Proposed fix**: Extract per-node-type handling into shared helper functions. Each helper takes the variant's fields and shared context parameters (`regexes`, `vars`, `mr`, `match_count`, `writer`, etc.). Both PMC and PCS call the same helpers.

**Impact**: Low (code quality) — no performance change, but significantly reduces maintenance burden and divergence risk.
**Risk**: Medium — large structural refactor that touches the core dispatch loop. Should be done after all performance fixes are stable.
