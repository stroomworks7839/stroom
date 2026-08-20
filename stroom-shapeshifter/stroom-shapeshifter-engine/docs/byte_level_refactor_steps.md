# Byte-Level Engine Refactor — Implementation Steps

## Phase 1: Store + Dead Code (no behaviour change, all tests must pass after each step)

- [ ] **1.1** `buffer.rs` — Remove dead code
  - Delete `Buffer` trait (lines 10-58)
  - Delete `ReverseBuffer` struct + impl (entire block ~line 150+)
  - Delete `AsRef<str>` impl for CharBuffer (the `unimplemented!()` stub)
  - Keep `CharBuffer` struct + inherent methods (used by reader + store)
  - Update `reader.rs` and `engine.rs` imports: remove `Buffer` from `use crate::buffer::{Buffer, CharBuffer}`

- [ ] **1.2** `store.rs` — Replace `CharBuffer` with `Vec<u8>`
  - `values: Vec<Option<CharBuffer>>` → `Vec<Option<Vec<u8>>>`
  - `set()`: accept `Vec<u8>` instead of `CharBuffer`
  - `get()`: return `Option<&[u8]>` instead of `Option<&CharBuffer>`
  - `StoreSet::store_value()`: accept `Vec<u8>`
  - `StoreSet::get_value()`: return `Option<&[u8]>`
  - Update unit tests: `CharBuffer::from_str("hello")` → `b"hello".to_vec()`, assert with `.unwrap() == b"hello"`

- [ ] **1.3** `engine.rs` — Update store interactions
  - `CharBuffer::from_str(&resolved)` → `resolved.into_bytes()` (or `.as_bytes().to_vec()`) at all ~10 call sites
  - `buf.as_string()` in `resolve_ref` (line 1858) → `String::from_utf8_lossy(buf).into_owned()` (or `from_utf8` with error handling)
  - `mr.groups` still `Vec<Option<String>>` at this stage — only stores change
  - Remove `use crate::buffer::{Buffer, CharBuffer}` from engine.rs
  - **Test**: all fixture tests pass

---

## Phase 2: Byte-level matching (core refactor — compile-test frequently)

- [ ] **2.1** `engine.rs` — Switch `CompiledRegex` to byte mode
  - `CompiledRegex::Standard(regex::Regex)` → `CompiledRegex::Bytes(regex::bytes::Regex)`
  - `captures_text(&str)` → `captures_bytes(&[u8])`: returns `Vec<Option<Vec<u8>>>` + byte offsets
  - `group_end(&str, usize)` → `group_end_bytes(&[u8], usize)`: returns byte offset
  - `compile_regexes`: `regex::Regex::new()` → `regex::bytes::Regex::new()`
  - `Fancy` variant stays `&str`-based — its `captures_text` decodes `&[u8]` → `&str` internally
  - Add `regex` dependency check: `regex::bytes::Regex` is in the same crate, no new dep
  - **Compile check**: should compile with temporary adapter code

- [ ] **2.2** `engine.rs` — Change `MatchRes` to bytes
  - `groups: Vec<Option<String>>` → `groups: Vec<Option<Vec<u8>>>`
  - `advance: usize` — semantics change from chars to bytes (update doc comment)
  - Ripple: all places that read `mr.groups` need updating

- [ ] **2.3** `engine.rs` — Change `try_match` signature to `&[u8]`
  - `fn try_match(regexes: &RegexCache, node: &NodeConfig, data: &[u8]) -> Option<MatchRes>`
  - **Regex node**: use `regex.captures_bytes(data)`, advance = byte offset
  - **All node**: `data.len()` for advance (bytes, not chars), group = `data.to_vec()`
  - **MatchTag/TakeWhile/TakeUntil/TakeN/AnyChar**: update to byte-level (these matchers need updating too)
  - **TakeBytes**: already byte-oriented, simplify (remove `Vec<char>` intermediate)
  - **MatchByte**: already byte-oriented via `text.as_bytes()`, simplify to use `data` directly
  - **ReadNumeric**: already byte-oriented via `text.as_bytes()`, simplify to use `data` directly
  - **Reverse node**: decode `data` → chars → reverse → re-encode → pass to child
  - **Sequence/Choice/Optional/Repeat/Peek/Not/Delimited/Separated**: update remaining slice from byte offset

- [ ] **2.4** `node.rs` — Change Split delimiter types  
  - `delimiter: Vec<char>` → `delimiter: Vec<u8>`
  - `escape: Option<Vec<char>>` → `escape: Option<Vec<u8>>`
  - `container_start: Option<Vec<char>>` → `container_start: Option<Vec<u8>>`
  - `container_end: Option<Vec<char>>` → `container_end: Option<Vec<u8>>`
  - Update `compiler.rs`, `decompiler.rs`, `migration.rs`, `legacy_config.rs` — everywhere Split is constructed/destructured

- [ ] **2.5** `engine.rs` — Change `split_find` + `build_split_match` to bytes
  - `text: &str` → `data: &[u8]`  
  - `delimiter: &[char]` → `delimiter: &[u8]`
  - All other char params → byte params
  - `chars_match` → `bytes_match`: `data[pos..].starts_with(pattern)`
  - Groups become `Vec<u8>` (byte slices, not char collection)
  - `advance` = byte count

- [ ] **2.6** `engine.rs` — Update `resolve_ref` for byte stores
  - Store lookup returns `&[u8]` → decode to `String` at this boundary
  - `buf.as_string()` → `String::from_utf8_lossy(buf).into_owned()`
  - Local group reference: `mr.groups[idx]` is now `Option<Vec<u8>>` → decode to String
  - **`resolve_ref` still returns `Option<String>`** — it's used by `evaluate_condition`, `apply_transforms`, `ValueMap`, `Data` nodes which all need text semantics
  - This is the **decode boundary**: stored as bytes, resolved as text

- [ ] **2.7** `engine.rs` — Update `process_match_children` + `process_children_slice`
  - `Var` nodes: store `resolved.into_bytes()` into stores (already bytes from 1.3)
  - `Data` nodes: `resolve_ref` still returns `String`, `.as_bytes()` for write — unchanged
  - `Group` nodes: extract group bytes from `mr.groups`, pass to `process_group_children`
  - `TransformOutput`: `resolve_ref` → String, transform → String, store as `.into_bytes()`

- [ ] **2.8** `engine.rs` — Update `process_group_children` to `&[u8]`
  - `text: &str` → `data: &[u8]`
  - Inner matching loop: pass `&remaining[..]` byte slices to `try_match`
  - Advance by bytes, not chars
  - `remaining = &remaining[adv_bytes..]`
  - Synthetic MatchRes for no-expression case: groups as byte vecs

- [ ] **2.9** `engine.rs` — Update `process_top_expression` 
  - `reader.buffer.as_string()` → `reader.buffer.as_bytes()` (or direct slice from byte reader)
  - `reader.move_forward(mr.advance)` — advance is now bytes
  - `chars_consumed` → `bytes_consumed` for capture offsets
  - `CaptureCollector`: input spans already byte-based, but group output changes to `Option<Vec<u8>>` or keep as decoded `Option<String>` for UI display

- [ ] **2.10** Update matcher modules (`matcher/` directory)
  - `tag.rs`, `take_while.rs`, `take_until.rs`, `take_n.rs`, `any_char.rs` — change from `&str` → `&[u8]`
  - `byte_atoms.rs` — already byte-level, verify no changes needed
  - Return types: `output: String` → `output: Vec<u8>`, advance in bytes

---

## Phase 3: Reader + cleanup

- [ ] **3.1** `reader.rs` — Replace `Vec<char>` with `Vec<u8>`
  - `buffer: CharBuffer` → `buffer: Vec<u8>` with offset/length fields inline
  - `fill_buffer()` — read raw bytes from BufReader, no `read_char()`, no `\r` filter
  - `move_forward()` — advance by bytes, update line tracking by scanning for `\n`
  - `as_bytes()` — return `&[u8]` slice of current window  
  - Remove `read_char()` helper function
  - Wire in `detect_bom()` on first fill

- [ ] **3.2** Delete `buffer.rs`
  - Remove `CharBuffer` entirely
  - Remove `mod buffer` from `lib.rs`
  - Verify no remaining imports

- [ ] **3.3** `capture.rs` — Update CaptureRecord
  - `output: Option<String>` — keep as String (it's for UI display)
  - But change call site: decode group bytes for the capture record
  - `input_span` — already byte offsets, update to use `bytes_consumed`

- [ ] **3.4** Full test pass + warnings check
  ```bash
  cargo test -p datasplitter-rs
  cargo check 2>&1 | grep warning
  ```

- [ ] **3.5** Copy updated design doc to `design/` folder

---

## Phase 4: Encoding wiring (can be deferred)

- [ ] **4.1** Add `encoding: Option<Encoding>` to Regex, Split, Data, Group in `node.rs`
- [ ] **4.2** Wire `resolve_encoding()` into engine node recursion
- [ ] **4.3** Add `decode()`/`encode()` functions to `encoding.rs`
- [ ] **4.4** Use encoding in `resolve_ref` decode boundary
- [ ] **4.5** Remove Group `reverse` field (use Reverse nodes instead)
