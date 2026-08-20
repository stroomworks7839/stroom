# Composable Matcher Nodes — Design Plan (v4)

## Overview

Extend the DS3 node editor with **five layers** of abstraction:

0. **Binary/Encoding Layer** — operate on raw bytes with cascading encoding inheritance + auto-detection
1. **Matcher Atoms** — atomic matching operations (regex, literal tag, take-while, etc.)
2. **Combinator Nodes** — compose atoms into complex matchers (sequence, choice, repeat, etc.)
3. **Pattern Library** — named, reusable atom/combinator compositions (IP address, date format, CSV field, etc.)
4. **Output Transform Layer** — transform and structure matched data into nested output (replacing XSLT)

Plus a **Migration Layer** to auto-convert existing DS3 XML configs to the new mechanism.

---

## Layer 0: Binary/Encoding Foundation

The engine operates on **raw `&[u8]` byte streams**. Encoding is resolved per-node via CSS-style inheritance.

### Encoding Inheritance

Encoding **cascades from parent to child** — any node can override:

```
Root (encoding=UTF-8)              ← default, most users never touch this
  └─ Split                         ← inherits UTF-8
       └─ Group
            └─ Regex               ← inherits UTF-8
            └─ TakeBytes(4)        ← raw bytes, no encoding needed
            └─ Regex (enc=Latin-1) ← override for this node only
```

- **Root default**: `UTF-8` (zero config for simple cases)
- **Per-node override**: optional `encoding` property
- **Resolution**: own override → nearest ancestor override → root → UTF-8
- **Auto-detection**: BOM sniffing at the root level — if the input starts with a BOM, the encoding is auto-detected and applied as the root default (overridable)

### Supported Encodings

| Encoding | Use Case |
|----------|----------|
| `UTF-8` | Default, most modern files |
| `Latin-1` / `ISO-8859-1` | Legacy European text |
| `UTF-16-LE` / `UTF-16-BE` | Windows files, Java serialization |
| `ASCII` | Simple byte=char files |
| `Shift-JIS` | Japanese text |
| `EBCDIC` | Mainframe data |
| [raw](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/xml_writer.rs#130-136) | No decoding — byte-level matching only |
| `auto` | Auto-detect via BOM sniffing |

### Byte-Level Atoms

When `encoding=raw` or for binary-aware matching:

| Atom | Settings | Output | Description |
|------|----------|--------|-------------|
| **TakeBytes** | `count` | N raw bytes | Read exactly N bytes |
| **MatchByte** | [value](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/config.rs#336-341) (hex) | Single byte | Match a specific byte value |
| **ByteOrder** | `endianness` | Marks interpretation | Sets byte order for numeric reads |
| **ReadShort** | `signed` | 16-bit integer | Read 2-byte short |
| **ReadInt** | [size](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/node-editor/src/model.rs#344-354) (1/2/4/8), `signed` | Integer value | Read signed/unsigned integer |
| **ReadLong** | [size](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/node-editor/src/model.rs#344-354) (8) | 64-bit integer | Read 8-byte long |
| **ReadFloat** | — | 32-bit float | IEEE 754 single precision |
| **ReadDouble** | — | 64-bit float | IEEE 754 double precision |

### Engine Pipeline

```
Raw input (&[u8])
    │
    ├─ Auto-detect encoding (BOM sniff if encoding=auto)
    ▼
┌─────────────────────────────┐
│  Resolve effective encoding │  (cascade: node → parent → root → UTF-8)
└─────────────────────────────┘
    │
    ├─ encoding = "raw"  ──→  Match on &[u8] directly (byte atoms)
    └─ encoding = text   ──→  Decode window to &str on-the-fly → text atoms
```

### Data Model

```rust
enum Encoding {
    Utf8, Latin1, Utf16Le, Utf16Be, Ascii, ShiftJis, Ebcdic,
    Raw,   // no decoding — byte-level only
    Auto,  // auto-detect via BOM
}

impl Default for Encoding {
    fn default() -> Self { Encoding::Utf8 }
}
```

Every node gains `encoding: Option<Encoding>`. `None` = inherit from parent.

---

## Layer 1: Matcher Atoms

Atoms are the **smallest indivisible matching units** — each performs one atomic operation.

| Atom | Settings | Output | Nom Equivalent |
|------|----------|--------|----------------|
| **Regex** | `pattern`, `dotAll`, `caseIns` | Match + capture groups | [regex](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/config.rs#179-221) |
| **Tag** | [text](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/refs.rs#64-68) (literal string) | Exact string match | `tag("...")` |
| **TakeWhile** | `predicate` | Chars matching predicate | `take_while(pred)` |
| **TakeUntil** | `pattern` (string) | Chars before pattern | `take_until("...")` |
| **TakeN** | `count` (number) | Exactly N chars | `take(N)` |
| **AnyChar** | — | Single character | `anychar` |
| **Peek** | (wraps 1 inner atom) | Match without consuming | `peek(inner)` |
| **Not** | (wraps 1 inner atom) | Fail if inner succeeds | `not(inner)` |
| **Reverse** | (wraps 1 inner atom) | Reverse the matched content | Utility transform |

> [!NOTE]
> **Reverse** is extracted from being an embedded Group property into a standalone atom. This keeps atoms composable — reverse can wrap any matcher, not just groups.

### Predicates

Predefined options **plus** a mini-expression language for custom charsets:

- `alphabetic` — `char::is_alphabetic`
- `alphanumeric` — `char::is_alphanumeric`
- `numeric` — `char::is_numeric`
- `whitespace` — `char::is_whitespace`
- `non-whitespace` — `!char::is_whitespace`
- `any` — matches everything
- **Custom expression** — mini charset language: `[a-zA-Z0-9_.-]` with ranges, negation (`[^...]`), and named classes (`[:alpha:]`)

### Visual Design

Atoms appear as **small, compact nodes** with a teal/cyan header and a single output port:

```
┌──────────────────┐
│ ⚡ TakeWhile     │
├──────────────────┤     
│ Pred: alphabetic │  ●→
└──────────────────┘
```

---

## Layer 2: Combinator Composition

Combinators **compose atoms (or other combinators) into complex matchers**. They are the wiring logic.

> [!IMPORTANT]
> **Nested composition is first-class.** The most common pattern is sequences of atoms fed into a Choice. The UI must make this nesting clear at every depth.

| Combinator | Inputs | Semantics | Nom Equivalent |
|------------|--------|-----------|----------------|
| **Sequence** | 2+ matchers (ordered) | All must match in order | `tuple((a, b, c))` |
| **Choice** | 2+ matchers (priority) | First match wins | `alt((a, b, c))` |
| **Optional** | 1 matcher | Match or skip | `opt(inner)` |
| **Repeat** | 1 matcher + [min](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/tests/ds3_tests.rs#149-153)/[max](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/tests/ds3_tests.rs#191-195) | Match N times | `many_m_n(m, n, inner)` |
| **Delimited** | 3 matchers (open, content, close) | Match between boundaries | [delimited(...)](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/tests/ds3_tests.rs#119-123) |
| **Separated** | 2 matchers (element, delimiter) | List with separator | `separated_list0(...)` |

### Labeled Outputs

Combinators have **optionally labeled output ports**. Instead of relying on `$1`, `$2` positional references, atoms and combinators can have **named outputs** that appear as wirable ports:

```
                     ┌─────────────────────────────┐
Tag('"')   ●─────→  ●│ open                        │
                     │  ⚙ Sequence                  │  ● content →  (to Group input)
TakeUntil  ●─────→  ●│ content  [labeled: content]  │  ● full →
                     │                               │
Tag('"')   ●─────→  ●│ close                        │
                      └─────────────────────────────┘
```

- Each atom/combinator in a composition can have an optional **label**
- Labeled items appear as **named output ports** on the parent combinator
- Groups wire to these named ports directly instead of using `$1`
- Unlabeled outputs still get auto-numbered (`$0`, `$1`, `$2`...) for backward compatibility

This replaces the current model where Groups define `value="$1"` — instead, the **wiring is visual**:

```
                        ┌──────────┐
  Sequence ● content →──│ Group    │──→ Data
           ● full →     │          │
                        └──────────┘
```

### Visual Design — Expand/Collapse Drill-Down

Rather than showing all atoms inline on the main canvas, pattern nodes use a **multi-level expand/collapse** UI:

#### Level 0: Collapsed (Normal View)

The pattern node appears as a regular node on the canvas, showing its type and a compact summary:

```
┌──────────────────────────┐
│ ⚙ CSV Field Parser       │
├──────────────────────────┤
│ Choice(Quoted, Unquoted) │  ● content →
│ ▶ Expand                 │  ● full →
└──────────────────────────┘
```

#### Level 1: Expanded (Structure View)

Clicking **▶ Expand** opens the node to show the top-level combinator structure. Atoms appear as compact inline blocks within the node body:

```
┌──────────────────────────────────────────────┐
│ ⚙ CSV Field Parser                     ▼ ▲  │
├──────────────────────────────────────────────┤
│                                              │
│  ┌─ Choice ──────────────────────────────┐   │
│  │                                       │   │
│  │  1: ┌─ Sequence ─────────────────┐    │   │
│  │     │ Tag('"') → TakeUntil('"')  │    │   │  ● content →
│  │     │ → Tag('"')   ▶ Detail      │    │   │  ● full →
│  │     └────────────────────────────┘    │   │
│  │                                       │   │
│  │  2: ┌─ Sequence ─────────────────┐    │   │
│  │     │ TakeUntil(',')  ▶ Detail   │    │   │
│  │     └────────────────────────────┘    │   │
│  │                                       │   │
│  └───────────────────────────────────────┘   │
│                                              │
└──────────────────────────────────────────────┘
```

#### Level 2: Detail (Atom View)

Clicking **▶ Detail** on a sub-component opens a **modal** showing the full atom graph with wiring, editable settings, and labeled outputs. This is the deepest level — individual atoms with their settings panels:

```
┌─ Detail: Quoted Field Sequence ──────────────────────┐
│                                                       │
│  ┌──────────────┐    ┌──────────────┐    ┌─────────┐ │
│  │ ⚡ Tag        │    │ ⚡ TakeUntil  │    │ ⚡ Tag   │ │
│  │ text: "      │●──→│ pattern: "   │●──→│ text: " │ │
│  └──────────────┘    └──────────────┘    └─────────┘ │
│                             ↓                         │
│                       [label: content]                │
│                                                       │
│  [Save]  [Cancel]                                     │
└───────────────────────────────────────────────────────┘
```

#### Interaction Model

| Action | Result |
|--------|--------|
| Click **▶ Expand** on a node | Opens structure view inline |
| Click **▼ Collapse** | Returns to compact view |
| Click **▶ Detail** on a sub-component | Opens atom-level modal |
| Double-click a pattern node | Opens structure view |
| Right-click → "Edit atoms" | Opens atom-level modal |

### Nested Composition Example

**Use case**: Parse either a quoted CSV field OR an unquoted field:

At **Level 0** (collapsed): `Choice(Quoted, Unquoted) ●→`

At **Level 1** (expanded): Shows the two Sequence branches inside the Choice.

At **Level 2** (detail modal): Shows the individual Tag/TakeUntil atoms with their wiring:

```
Tag('"')      ●─→ ┌──────────┐
TakeUntil('"')●─→ │ Sequence  │ ●──→ ┌────────┐
Tag('"')      ●─→ └──────────┘      │        │
                                     │ Choice │ ● content →  Group
TakeUntil(',')●─→ ┌──────────┐      │        │
                   │ Sequence  │ ●──→ └────────┘
                   └──────────┘
```

---

## Layer 3: Pattern Library

### Concept

The Pattern Library stores **named, reusable compositions** — from individual atoms up to complex multi-level compositions. Think of it as a function library:

- **Atom templates**: a single configured atom (e.g., "Digits" = `TakeWhile(numeric)`)
- **Pattern templates**: composed atoms (e.g., "IP Address" = `Seq(digits, Tag("."), digits, Tag("."), digits, Tag("."), digits)`)
- **Complex patterns**: nested combinator trees (e.g., "CSV Field" = `Choice(QuotedField, UnquotedField)`)
- **Transform templates**: reusable transform/structure compositions (e.g., "Syslog to JSON" = a pre-wired Object with common syslog fields and Format transforms)
- **Pipeline templates**: end-to-end matcher → transform → structure groups (e.g., "CSV Row Parser" = Split + Field mapping + JSON output)

### Built-in Pattern Library

Ship with a set of common patterns:

| Pattern Name | Composition | Example Match |
|-------------|-------------|---------------|
| **Digits** | `TakeWhile(numeric)` | `42`, `100` |
| **Word** | `TakeWhile(alphabetic)` | `hello` |
| **IP Address** | `Seq(Digits, ".", Digits, ".", Digits, ".", Digits)` | `192.168.1.1` |
| **ISO Date** | `Seq(Digits4, "-", Digits2, "-", Digits2)` | `2024-03-18` |
| **ISO DateTime** | `Seq(ISODate, "T", Time)` | `2024-03-18T14:30:00` |
| **Quoted String** | `Delimited('"', TakeUntil('"'), '"')` | `"hello"` |
| **CSV Field** | `Choice(QuotedString, TakeUntil(","))` | `"quoted"` or [plain](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/refs.rs#348-353) |
| **Email** | `Seq(Word, "@", Word, ".", Word)` | `user@domain.com` |
| **Key=Value** | `Seq(TakeUntil("="), "=", TakeUntil("&"))` | `key=value` |

### Visual Design

#### Library Panel

A collapsible side panel with categorized templates:

```
┌─ Pattern Library ───────────────────┐
│ 🔍 Search...                        │
│                                     │
│ ▶ Common                            │
│   📦 Digits                         │
│   📦 Word                           │
│   📦 Quoted String                  │
│                                     │
│ ▶ Network                           │
│   📦 IP Address                     │
│   📦 MAC Address                    │
│                                     │
│ ▶ Date/Time                         │
│   📦 ISO Date                       │
│   📦 ISO DateTime                   │
│                                     │
│ ▶ Custom (user-defined)             │
│   📦 My CSV Field                   │
│                                     │
│ [+ New Pattern]                     │
└─────────────────────────────────────┘
```

#### Using vs Editing Templates

| Action | Result |
|--------|--------|
| **Click** a library pattern | Adds a reference node to the canvas |
| **✏️ Edit icon** on a library pattern | Opens the pattern definition in a **separate modal** |
| **Double-click** a template reference on canvas | Opens the pattern structure view (read-only) |
| **Right-click → Edit definition** on a reference | Opens the modal editor |



```
┌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌┐
╎ 📦 IP Address         ╎
╎                       ╎  ● match →
╎ Seq(d.d.d.d)          ╎
└╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌┘
```

### Data Model

```rust
struct PatternTemplate {
    id: Uuid,
    name: String,
    description: String,
    category: String,           // "Common", "Network", "Date/Time", "Custom"
    is_builtin: bool,           // true for shipped patterns
    nodes: Vec<MatcherNode>,
    connections: Vec<MatcherConnection>,
    output_node: Uuid,
    /// Named output labels for this pattern.
    output_labels: Vec<String>,
}

enum MatcherNode {
    // Text atoms
    Regex { pattern: String, dot_all: bool, case_insensitive: bool },
    Tag { text: String },
    TakeWhile { predicate: Predicate },
    TakeUntil { pattern: String },
    TakeN { count: usize },
    AnyChar,
    Reverse,        // wraps inner matcher, reverses output
    
    // Byte atoms
    TakeBytes { count: usize },
    MatchByte { value: u8 },
    ReadShort { signed: bool },  // 16-bit
    ReadInt { size: IntSize, signed: bool },
    ReadFloat,      // IEEE 754 32-bit
    ReadDouble,     // IEEE 754 64-bit
    
    // Combinators
    Sequence { output_labels: Vec<Option<String>> },
    Choice,
    Optional,
    Repeat { min: usize, max: Option<usize> },
    Delimited,
    Separated,
    Peek,
    Not,
    
    // Template reference
    PatternRef { template_id: Uuid },
}

enum Predicate {
    Alphabetic, Alphanumeric, Numeric, Whitespace, NonWhitespace,
    CustomCharset(String),  // mini-expression: "[a-zA-Z0-9_.-]"
}

enum IntSize { I8, I16, I32, I64 }
```

### Storage

```xml
<dataSplitter>
  <patternLibrary>
    <pattern id="ip-addr" name="IP Address" category="Network">
      <sequence>
        <takeWhile predicate="numeric" label="octet1"/>
        <tag text="."/>
        <takeWhile predicate="numeric" label="octet2"/>
        <tag text="."/>
        <takeWhile predicate="numeric" label="octet3"/>
        <tag text="."/>
        <takeWhile predicate="numeric" label="octet4"/>
      </sequence>
    </pattern>
  </patternLibrary>
  
  <!-- Main config references patterns -->
  <patternRef ref="ip-addr">
    <group>
      <data name="ip" value="match"/>
      <data name="first-octet" value="octet1"/>
    </group>
  </patternRef>
</dataSplitter>
```

---

## Migration Layer

### Auto-Conversion of Legacy DS3 XML

Existing Split/Regex/All XML configs are **auto-converted** to the new atom/combinator model via a mapping layer:

| Legacy Node | Auto-Converted To |
|-------------|-------------------|
| `<split delimiter=",">` | `Separated(TakeUntil(","), Tag(","))` |
| `<split delimiter="," escape="\\">` | `Separated(Escaped(TakeUntil(","), "\\"), Tag(","))` |
| `<split delimiter="," containerStart="(" containerEnd=")">` | `Separated(Choice(Delimited("(", TakeUntil(")"), ")"), TakeUntil(",")), Tag(","))` |
| `<regex pattern="...">` | [Regex("...")](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/node.rs#31-35) (atom, direct mapping) |
| `<all>` | `TakeWhile(any)` |
| `<group reverse="true">` | [Reverse(Group(...))](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/buffer.rs#300-306) |

The migration layer:
1. **Reads** existing DS3 XML
2. **Maps** each legacy node to equivalent atom/combinator composition
3. **Preserves** all child Data/Var/Group nodes
4. **Emits** the new-format config (can be serialised back to XML)

> [!NOTE]
> Legacy configs continue to work — the migration layer is applied transparently at load time. Users can optionally "upgrade" a config to the new format, which makes the atom structure visible and editable in the node editor.

---

## Layer 4: Output Transform Layer

### Problem: Why XSLT Exists Today

The current Data Splitter produces a flat `records/record/data` XML output:

```xml
<records>
  <record>
    <data name="date" value="2024-03-18"/>
    <data name="severity" value="ERROR"/>
    <data name="message" value="Connection failed"/>
  </record>
</records>
```

This flat structure is then piped through XSLT to produce the actual desired output — whether that's a different XML schema, JSON, or a restructured hierarchy. The XSLT step adds complexity, is hard to debug, and can't be edited visually in the node editor.

**The Output Transform Layer eliminates XSLT** by letting the node graph itself define the output structure.

### Concept

Instead of Data nodes only emitting flat `name="x" value="y"` pairs, introduce two new categories of nodes:

1. **Transform Nodes** — manipulate matched values before output (concatenate, split, format, map, filter)
2. **Structure Nodes** — define the shape of the output (objects, arrays, fields, literals)

These wire into the graph **after** matchers/combinators, replacing the current Data node's role:

```
  Matcher ──→ Group ──→ Transform ──→ Structure ──→ Output
  (parse)     (scope)   (reshape)     (shape)       (emit)
```

### Transform Nodes

Transform nodes take matched values as input and produce transformed values as output. They use an **orange header** to distinguish them from matcher atoms (teal) and combinators (grey).

| Transform | Inputs | Output | Description | XSLT Equivalent |
|-----------|--------|--------|-------------|------------------|
| **Concat** | 2+ values | Single string | Join values with optional separator | `concat()` / `string-join()` |
| **Split** | 1 value + delimiter | Array of strings | Split a value into parts | `tokenize()` |
| **Substring** | 1 value + start/length | Substring | Extract part of a string | `substring()` |
| **Replace** | 1 value + pattern + replacement | Modified string | Regex or literal replace | `replace()` |
| **Format** | 1+ values + template | Formatted string | Template string with `{0}`, `{1}` placeholders | `format-number()` / `concat()` |
| **Map** | 1 value + lookup table | Mapped value | Value mapping / lookup (e.g., code → description) | `if/choose` |
| **Coalesce** | 2+ values | First non-empty | Return first non-null/non-empty value | `if/choose` with fallback |
| **Lowercase** | 1 value | Lowered string | Convert to lowercase | `lower-case()` |
| **Uppercase** | 1 value | Uppered string | Convert to uppercase | `upper-case()` |
| **Trim** | 1 value | Trimmed string | Strip leading/trailing whitespace | `normalize-space()` |
| **ToNumber** | 1 value | Number | Parse string as number (int or float) | `number()` |
| **Conditional** | 1 value + condition | Pass-through or null | Only emit value if condition matches | `xsl:if` / `xsl:when` |

#### Transform Visual Design

```
                    ┌───────────────────┐
  ● date ────────→  │ 🔧 Format          │
  ● severity ───→  │ "{0} [{1}]: {2}"  │  ● formatted →
  ● message ────→  │                   │
                    └───────────────────┘
```

Transforms are **composable** — the output of one transform feeds into another:

```
  ● raw_date ──→  ┌──────────┐     ┌───────────┐
                  │ 🔧 Split  │ ──→ │ 🔧 Format  │ ● iso_date →
                  │ delim="/" │     │ "{2}-{0}-{1}" │
                  └──────────┘     └───────────┘
```

### Structure Nodes

Structure nodes define the **shape of the output** — nested objects, arrays, and typed fields. They use a **purple header**.

| Structure Node | Semantics | Output |
|---------------|-----------|--------|
| **Object** | Named container with key-value children | `{ ... }` |
| **Array** | Ordered list of repeated items | `[ ... ]` |
| **Field** | Named value (leaf) | `"key": "value"` |
| **Literal** | Static constant value | `"key": "constant"` |
| **Iterate** | Repeat structure for each match of a pattern | Array of objects |
| **Conditional** | Include/exclude structure based on a value | Optional structure |

#### Structure Visual Design

```
                    ┌─────────────────────────┐
  ● ip ──────────→  │ 🟣 Object "event"       │
  ● severity ───→  │                         │
  ● message ────→  │  ┌── Field "source_ip" ←─── ● ip
                    │  ┌── Field "level"     ←─── ● severity
                    │  ┌── Field "msg"       ←─── ● message
                    │                         │  ● output →
                    └─────────────────────────┘
```

### Complete Pipeline Example

**Input** (syslog line):
```
Mar 18 14:30:00 server01 sshd[1234]: Failed password for user from 192.168.1.1
```

**Current approach** (Data Splitter + XSLT):
1. Data Splitter parses → flat `<data name="date" value="Mar 18"/>`, `<data name="host" value="server01"/>`, etc.
2. XSLT transforms → restructured XML or JSON

**New approach** (all in the node graph):

```
 Regex ──→ Group ──→ ┌─────────────────────────────────────────┐
                     │ 🟣 Object "event"                       │
                     │                                         │
                     │  Field "timestamp" ←── Format("{0}T{1}") ←── ● date, ● time
                     │  Field "host"      ←── ● host            │
                     │  Object "auth"     ←──┐                  │
                     │    Field "action"  ←──│── ● action       │
                     │    Field "user"    ←──│── ● user         │
                     │    Field "source"  ←──│── ● ip           │
                     │  └─────────────────┘  │                  │
                     │                                         │
                     └─────────────────────────────────────────┘
```

**Output** (direct from engine, no XSLT):
```json
{
  "timestamp": "Mar 18T14:30:00",
  "host": "server01",
  "auth": {
    "action": "Failed password",
    "user": "user",
    "source": "192.168.1.1"
  }
}
```

### Output Formats

The structure tree is **format-agnostic** — the same structure nodes can emit different formats:

| Format | Writer | Output |
|--------|--------|--------|
| **JSON** | `JsonWriter` | `{"key": "value", ...}` |
| **XML (custom schema)** | `SchemaXmlWriter` | User-defined element/attribute mapping |
| **XML (records:2)** | `RecordsXmlWriter` | Legacy `records/record/data` format (backward-compat) |
| **CSV** | `CsvWriter` | Flat delimited output |
| **Key=Value** | `KvWriter` | `key=value` pairs |

The output format is selected at the **root node level** — one setting controls the serialization:

```rust
enum OutputFormat {
    Json,
    XmlRecords,     // legacy records:2 format
    XmlCustom,      // user-defined schema
    Csv,
    KeyValue,
}
```

### Data Model

```rust
/// Transform operations on matched values.
enum TransformNode {
    Concat { separator: Option<String> },
    Split { delimiter: String },
    Substring { start: usize, length: Option<usize> },
    Replace { pattern: String, replacement: String, is_regex: bool },
    Format { template: String },    // "{0} - {1}" style
    Map { entries: Vec<(String, String)>, default: Option<String> },
    Coalesce,
    Lowercase,
    Uppercase,
    Trim,
    ToNumber,
    Conditional { 
        condition: ConditionExpr, 
    },
}

/// Conditions for conditional transforms/structures.
enum ConditionExpr {
    Equals(String),
    NotEquals(String),
    Matches(String),            // regex match
    NotEmpty,
    Contains(String),
    StartsWith(String),
    EndsWith(String),
    And(Box<ConditionExpr>, Box<ConditionExpr>),
    Or(Box<ConditionExpr>, Box<ConditionExpr>),
    Not(Box<ConditionExpr>),
}

/// Structure nodes defining output shape.
enum StructureNode {
    Object { name: String },
    Array { name: String },
    Field { name: String },             // leaf — wired to a value source
    Literal { name: String, value: String },
    Iterate { name: String },           // repeats for each match
    ConditionalBlock { condition: ConditionExpr },
}
```

### Wiring: Matcher → Transform → Structure

The three node categories connect via typed ports:

| Port Type | From | To | Wire Colour |
|-----------|------|----|-------------|
| **Match output** | Matcher/Combinator labeled output | Transform input or Field input | Blue |
| **Transform output** | Transform node | Another Transform or Field input | Orange |
| **Structure child** | Structure node | Nested Structure node | Purple |

> [!TIP]
> For the simple case (no transforms needed), a matcher output wires **directly** to a Field input — transforms are optional. This keeps the simple case simple: `Regex ● name → Field "name"` works without any intermediate nodes.

### Label References (Virtual Wiring)

Complex graphs with many wires become hard to read. **Label References** allow nodes to be connected without visible wires — a publish/subscribe model for named values:

#### Label Output (Publisher)

Any output port can be assigned a **label name**. This publishes the value to a named channel:

```
┌──────────────────────┐
│ ⚡ Regex (headers)    │
├──────────────────────┤
│ pattern: ^[^\n]+     │  ● $1 → 🏷️ "heading"
└──────────────────────┘
```

#### Label Input (Subscriber)

A **Label Input** node receives the value from a named label, making it available locally without a wire:

```
┌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌┐
╎ 🏷️ "heading"         ╎  ● value →  (connects to Field, Transform, etc.)
└╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌┘
```

#### How It Works

| Feature | Behaviour |
|---------|-----------|
| **Scope** | Labels are global within a config — any label input can reference any label output |
| **Multiple subscribers** | Many label inputs can read from the same label output |
| **Visual indicator** | Label outputs show a 🏷️ icon; label inputs show the same icon with a dashed border |
| **Colour coding** | Label outputs and their corresponding inputs share the same colour (auto-assigned) |
| **Click to navigate** | Clicking a label input highlights and scrolls to the corresponding label output |
| **Fallback** | Labels can have a fallback value if the source hasn't produced output yet |

#### Use Cases

- **Header vars** → label the first-row split output as `"heading"`, then use `🏷️ heading` in data rows to reference column names — no cross-record wires needed
- **Shared transforms** → label a formatted timestamp as `"iso_timestamp"`, reference it from multiple structure nodes
- **Cross-group references** → replace the current `$varName$` syntax with visual label references

> [!NOTE]
> Labels are **syntactic sugar over the existing Var mechanism** — they compile down to the same var-store read/write at execution time. The difference is purely visual: labels are named reference points, not wires.

```rust
/// A named label for virtual wiring.
struct LabelOutput {
    name: String,
    source_node: Uuid,
    source_port: PortId,
}

struct LabelInput {
    name: String,
    fallback: Option<String>,
}
```

### Dead Output Elimination

During virtual compilation, the engine performs a **reachability analysis** on all capture groups and labeled outputs. Any output that is not referenced by a downstream consumer (Label Input, Data node, Transform input, Field, etc.) is **pruned from the execution graph** — no storage is allocated and no memory is used at runtime.

#### How it works

1. **Scan downstream references** — walk the wired graph and collect every group/label that is actually consumed
2. **Mark live outputs** — only outputs that appear in a downstream reference are marked as "live"
3. **Prune dead outputs** — during node function construction, the engine configures matchers to skip storage for dead groups

#### Example

A regex `^(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(.*)$` produces 5 capture groups. If only `$1` (timestamp) and `$5` (message) are wired to downstream nodes:

```
Before pruning:  groups stored = [$0, $1, $2, $3, $4, $5]  →  6 allocations per match
After pruning:   groups stored = [$1, $5]                   →  2 allocations per match
```

#### Scope

| What gets pruned | Description |
|-----------------|-------------|
| **Unused regex groups** | Capture groups not referenced by any `$N` or label |
| **Unused label outputs** | Labels with no corresponding Label Input nodes |
| **Unused var stores** | Var nodes whose stored values are never read |
| **Unused transform outputs** | Transform outputs not wired to any structure node |

> [!TIP]
> The editor can show pruned outputs as **greyed out** ports, helping users see which outputs are unused. This also serves as a hint to simplify complex patterns — if most groups are greyed out, the regex may be over-capturing.

### Backward Compatibility

When no Structure nodes are present in a config, the engine falls back to the **existing flat `records/record/data` output** — Data nodes continue to work exactly as they do today. Structure nodes are purely additive.

---

## Live Data Preview

During construction in the node editor, a **resizable preview panel** appears below the canvas showing the sample input data the user is working with.

### Layout

```
┌──────────────────────────────────────────────────────────────┐
│                    Node Editor Canvas                        │
│                                                              │
│  [Regex] ──→ [Group] ──→ [Format] ──→ [Object "event"]      │
│                                                              │
├──────────────────────────────────────────────────────────────┤
│  📄 Data Preview                                     ▼ ▲    │
│                                                              │
│  Mar 18 14:30:00 server01 sshd[1234]: Failed password for   │
│  ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓ ════════ ══════════════════════════════   │
│     (date)        (host)    (message)                        │
│                                                              │
│  Output Preview:                                             │
│  { "timestamp": "Mar 18T14:30:00", "host": "server01", ... } │
└──────────────────────────────────────────────────────────────┘
```

### Behaviour

| Feature | Description |
|---------|-------------|
| **Data source** | User loads sample data (paste, file, or live stream sample) |
| **Highlighting** | When a node/atom/pattern is selected, the preview highlights the portion of input that node matches |
| **Colour coding** | Each selected node's match region gets a distinct colour matching its header colour (teal for atoms, grey for combinators, orange for transforms) |
| **Multi-atom highlighting** | When a **combinator** is selected (or expanded), each child atom's match region gets a **distinct colour**, so the user can see which atom matched which slice of the input (see below) |
| **Output preview** | Below the input, shows the current output that would be produced by the selected structure nodes |
| **Live update** | As nodes are added/modified, the preview updates in real-time against the sample data |
| **Multi-record** | For multi-record inputs, shows record boundaries with separators |
| **Hover preview** | Hovering over any node briefly highlights its match region without requiring a click |

#### Multi-Atom Preview Example

When editing a Sequence combinator that parses a log line, each atom is highlighted in its own colour:

```
📄 Data Preview — Editing: Sequence (Log Line Parser)

  "2024-03-18" 14:30:00 server01 ERROR Connection failed
  ████████████ ░░░░░░░░ ▓▓▓▓▓▓▓▓ ════════ ∎∎∎∎∎∎∎∎∎∎∎∎∎∎∎∎∎∎∎
   Tag('"')     TakeUntil  Tag(' ')  TakeWhile   TakeUntil('\n')
   + TakeUntil  (space)              (alpha)
   + Tag('"')

  Legend: ████ Atom 1 (blue)  ░░░░ Atom 2 (green)  ▓▓▓▓ Atom 3 (amber)
          ════ Atom 4 (red)   ∎∎∎∎ Atom 5 (purple)
```

The colours match the corresponding atom nodes in the canvas, creating a direct visual link between the graph and the data. Clicking a highlighted region in the preview selects the corresponding atom node above.

### I/O Capture via Decorators

The preview panel uses the same **construction-time decorator wiring** as timing. When the preview panel is open, the engine wires in `Captured` decorators that record each node's input span and output value:

```
Captured(Regex::execute) → Captured(Group::process) → Captured(Format::transform) → Object::emit
```

Each `Captured` decorator records:
- **Input span** — the byte offset and length of the input region the node consumed
- **Output value** — what the node produced (match groups, transformed text, structured output)
- **Node identity** — which node produced this capture

This data feeds directly into the preview panel's highlighting and output display.

```rust
// Captured decorator — wired in during construction for preview mode
fn captured(inner: NodeFn, node_id: String) -> NodeFn {
    Box::new(move |input, ctx| {
        let input_span = (ctx.current_offset(), input.len());
        let result = inner(input, ctx);
        ctx.record_capture(&node_id, input_span, &result);
        result
    })
}
```

### Performance Isolation

> [!CAUTION]
> The preview engine runs **only within the editor context** and must NOT impact execution performance:
> - **Decorator-based** — `Captured` decorators are only wired in when the preview panel is open; production execution has no capture code in the chain
> - Preview runs in a **separate thread/task** with its own timeout
> - Operates on a **fixed-size sample** (first N bytes/records, configurable)
> - **Debounced** — waits 200ms after the last edit before re-evaluating
> - No preview state is serialized into the DS3 config

---

## Execution Timing & Instrumentation

Optional performance instrumentation for profiling and debugging node execution.

### Concept

Any node can have a **timing probe** attached. When enabled, the engine captures execution statistics for that node without affecting the logic:

```
┌──────────────────────┐
│ ⚡ Regex (parse_date) │
├──────────────────────┤
│ pattern: ...         │  ● $1 →
│ ⏱️ 0.3ms avg (1.2k)  │
└──────────────────────┘
```

### Statistics Captured

| Metric | Description |
|--------|-------------|
| **Total time** | Cumulative time spent in this node |
| **Avg time** | Average per-invocation time |
| **Invocation count** | Number of times the node was executed |
| **Match rate** | % of invocations that produced a match (for matcher nodes) |
| **Output size** | Average output size in bytes (for transform/structure nodes) |

### Wiring Model — Construction-Time Decorators

## Construction-Time Decorator Wiring

Both preview I/O capture and execution timing use the same **decorator wiring** mechanism. During the virtual compilation step (config → execution graph), the engine optionally wraps node functions with decorator layers. **If a decorator is not requested, it's simply not wired in** — zero overhead, no runtime branches.

### Decorator Types

| Decorator | Purpose | Records |
|-----------|---------|----------|
| **Captured** | Preview I/O capture | Input span (offset + length), output value, node identity |
| **Timed** | Execution profiling | Start/end timestamps, invocation count, match success/failure |

### Composability

Decorators are **stackable** — the wiring step can compose multiple decorators around the same inner function:

| Context | Wiring | Overhead |
|---------|--------|----------|
| **Production** | `inner` | Zero |
| **Preview only** | `Captured(inner)` | I/O recording only |
| **Profile Run** | `Timed(inner)` | Timing only |
| **Preview + Profiling** | `Timed(Captured(inner))` | Both |

```
Production:   Regex::execute → Group::process → Format::transform
Preview:      Captured(Regex) → Captured(Group) → Captured(Format)
Profile:      Timed(Regex)    → Timed(Group)    → Timed(Format)
Preview+Prof: Timed(Captured(Regex)) → Timed(Captured(Group)) → ...
```

#### Implementation sketch

```rust
type NodeFn = Box<dyn Fn(&str, &mut Context) -> MatchResult>;

fn wire_node(node: &NodeConfig, mode: &WiringMode) -> NodeFn {
    let inner = match node {
        NodeConfig::Regex { .. } => build_regex_fn(node),
        NodeConfig::Split { .. } => build_split_fn(node),
        // ...
    };

    let node_id = node.id().to_string();

    // Layer decorators based on wiring mode
    let wrapped = if mode.capture_enabled {
        captured(inner, node_id.clone())
    } else {
        inner
    };

    if mode.timing_enabled_for(&node_id) {
        timed(wrapped, node_id)
    } else {
        wrapped
    }
}
```

After `wire_node` returns, the decorator decision is baked in. The execution loop calls whatever function is in the chain — it has no awareness of whether timing or capture are active.

### Timing Statistics

When timing decorators are wired in, they capture:

| Mode | Behaviour |
|------|-----------|
| **Normal execution** (default) | No decorators wired — zero overhead, identical to today |
| **Preview** (panel open) | `Captured` decorators wired — I/O capture for highlighting |
| **Profile Run** (editor menu) | `Timed` decorators wired — full execution statistics |
| **Preview + Profile** | Both `Captured` and `Timed` stacked — I/O capture plus timing |
| **Per-node toggle** (right-click → "Enable timing") | Only selected nodes get `Timed` decorators — fine-grained profiling |

### Results Display

After a profile run, timing results appear as:
- **Node overlay** — each node shows its avg time and invocation count inline
- **Heatmap mode** — nodes are colour-coded by time (green → yellow → red)
- **Table view** — sortable table of all instrumented nodes with full statistics
- **Bottleneck detection** — nodes consuming >10% of total time are flagged with a ⚠️ icon

---

## AI Chat Assistant

An AI-powered chat panel integrated into the node editor to help users construct and debug their processing flows.

### Layout

The chat panel appears as a **collapsible side panel on the right edge** of the editor, similar to the Antigravity chat interface:

```
┌───────────────────────────────────────┬──────────────────────┐
│           Node Editor Canvas          │  🤖 AI Assistant     │
│                                       │                      │
│  [Regex] ──→ [Group] ──→ [Object]    │  "I have syslog      │
│                                       │   data and want to   │
│                                       │   extract fields     │
│                                       │   into JSON"         │
│                                       │                      │
│                                       │  ──────────────────  │
│                                       │  I'll create a flow  │
│                                       │  that parses syslog  │
│                                       │  priority, timestamp │
│                                       │  host, and message   │
│                                       │  into a structured   │
│                                       │  JSON output...      │
│                                       │                      │
│                                       │  [Apply to Canvas]   │
├───────────────────────────────────────┤                      │
│  📄 Data Preview                      │  ╭──────────────╮    │
│  <134>Mar 18 14:30:00 server01...     │  │ 💬 Type here │    │
│                                       │  ╰──────────────╯    │
└───────────────────────────────────────┴──────────────────────┘
```

### Capabilities

| Feature | Description |
|---------|-------------|
| **Describe → Generate** | User describes input format and desired output; AI generates the node graph |
| **Sample-driven** | AI reads the loaded sample data from the preview panel to understand the format |
| **Explain nodes** | User selects a node and asks "what does this do?" — AI explains its role in the pipeline |
| **Debug help** | "Why isn't this matching?" — AI examines the selected node, sample data, and suggests fixes |
| **Pattern suggestions** | AI suggests patterns from the library that match the input data |
| **Iterative refinement** | User can say "also extract the PID" and AI modifies the existing graph |
| **Apply to Canvas** | AI-generated nodes appear on the canvas with a single click, pre-wired and positioned |

### Interaction Model

1. **User loads sample data** into the preview panel
2. **User describes the goal** in natural language: *"Parse this nginx access log into JSON with separate fields for IP, timestamp, method, URL, status code, and response size"*
3. **AI analyses the sample data** and proposes a node graph
4. **User clicks "Apply to Canvas"** — nodes are created and wired on the canvas
5. **Preview updates** immediately showing the parsed output
6. **User refines** — *"The timestamp should be ISO 8601 format"* — AI adds a Format transform node

### Context Available to AI

- Current sample data from the preview panel
- All nodes currently on the canvas and their wiring
- The Pattern Library (built-in and user-defined)
- The current output format setting
- Any error messages from the preview engine

> [!NOTE]
> The AI assistant is a **construction-time tool only** — it does not run during production data splitting. It connects to the Stroom AI service (or configurable LLM endpoint) and requires network access only during editor sessions.

---

## Existing Nodes as Shorthand

Split, Regex, and All nodes remain as **convenience shortcuts** — specific compositions:

- `Split(delimiter=",")` = `Separated(TakeUntil(","), Tag(","))`
- [Regex(pattern="...")](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/node.rs#31-35) = [Regex("...")](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/node.rs#31-35) atom
- `All` = `TakeWhile(any)` atom

These can be added from a "Quick Add" menu for common tasks. Power users can expand them to see/edit the underlying atom composition.

---

## Phased Implementation

### Phase 1: Matcher Atoms *(~2 days)*

- Rename "primitives" to "atoms" throughout
- Add `MatcherNode` enum to [node.rs](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/node.rs)
- Implement core atoms: **Regex**, **Tag**, **TakeWhile**, **TakeUntil**, **TakeN**, **AnyChar**
- Add atom nodes to the editor palette (teal header)
- Support custom charset predicates with mini-expression language

### Phase 2: Combinator Nodes *(~3 days)*

- Implement **Sequence**, **Choice**, **Optional**, **Repeat**
- Implement **labeled outputs** on combinators
- Support nested composition (Sequence→Choice and deeper)
- Engine: [try_match](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/engine.rs#250-290) dispatches to composed matcher graph walker
- Add expand/collapse UI (Level 0 ↔ Level 1)

### Phase 3: Pattern Library *(~3 days)*

- Add `PatternTemplate` struct and library storage
- Implement library side panel with categories and search
- **Separate modal** for editing template definitions
- Click-to-use vs edit-icon-to-modify distinction
- Ship built-in patterns (Digits, Word, IP Address, ISO Date, etc.)
- Add `PatternRef` node type for the canvas

### Phase 4: Advanced Combinators + Detail View *(~3 days)*

- Add **Delimited**, **Separated**, **Peek**, **Not**, **Reverse** as standalone atom
- Implement Level 2 detail modal (full atom graph editing)
- Add template import/export (.ds3lib files)

### Phase 5: Migration Layer *(~2 days)*

- Implement auto-conversion mapping from legacy DS3 XML
- Transparent migration at load time
- Optional "upgrade to new format" action
- Preserve backward compatibility with existing configs

### Phase 6: Binary/Encoding Layer *(~3 days)*

- Refactor [DS3Reader](file:///home/stroomdev66/stroomworks_work/datasplitter-rs/src/reader.rs#18-49) to operate on `&[u8]`
- Add encoding cascade resolution
- **Auto-detection** via BOM sniffing
- Add byte atoms: **TakeBytes**, **MatchByte**, **ReadShort**, **ReadInt**, **ReadFloat**, **ReadDouble**, **ReadLong**
- Add `encoding_rs` crate

### Phase 7: Output Transform Layer *(~5 days)*

- Add `TransformNode` and `StructureNode` enums
- Implement transform nodes: **Concat**, **Split**, **Replace**, **Format**, **Map**, **Conditional**
- Implement structure nodes: **Object**, **Array**, **Field**, **Literal**, **Iterate**
- Add `JsonWriter` as the primary structured output writer
- Add `OutputFormat` selector to the root node settings
- Wire matcher labeled outputs → transforms → structure nodes
- Add orange (transform) and purple (structure) node headers to the editor palette
- Implement **Label References** (virtual wiring) — label output/input nodes for spaghetti-free graphs
- Backward compat: fall back to `records/record/data` when no Structure nodes present
- Stretch: add `CsvWriter`, `KvWriter`, and `SchemaXmlWriter`

### Phase 8: Live Data Preview *(~4 days)*

- Add resizable preview panel below the node editor canvas
- Implement sample data loading (paste, file upload, live stream sample)
- Match highlighting: selected node highlights its input region with colour coding
- Output preview: show current structured output below the input
- Debounced live update (200ms delay after last edit)
- Preview engine runs in separate thread with timeout
- Preview engine lives in the editor crate, wrapping the core engine — zero production impact

### Phase 9: Execution Timing & Instrumentation *(~2 days)*

- Implement `Timed` decorator wrapper for node functions
- Construction-time wiring: timing decorators injected during config → execution graph step
- Capture per-node stats: total time, avg time, invocation count, match rate
- "Profile Run" mode: wire all nodes with timing decorators
- Per-node timing toggle via right-click menu
- Editor overlay: inline timing display on nodes
- Heatmap mode: colour-code nodes by execution time
- Table view: sortable list of all instrumented nodes
- Bottleneck detection flagging (>10% of total time)

### Phase 10: AI Chat Assistant *(~5 days)*

- Add collapsible chat panel on right edge of editor
- Connect to Stroom AI service / configurable LLM endpoint
- Context injection: sample data, current canvas state, pattern library, errors
- "Describe → Generate" flow: natural language → node graph
- "Apply to Canvas" action: place and wire AI-generated nodes
- Iterative refinement: modify existing graph via follow-up prompts
- Explain and debug modes: selected node explanation, match failure diagnosis

> [!IMPORTANT]
> Each phase is independently valuable. Phases 1–2 deliver composable matchers. Phase 3 adds reuse. Phase 4 adds detail editing. Phase 5 handles legacy. Phase 6 adds binary/encoding. Phase 7 replaces XSLT. Phase 8 adds the live preview authoring experience. Phase 9 adds performance visibility. Phase 10 adds AI-assisted construction.

---

## Design Decisions (Resolved)

| Question | Decision |
|----------|----------|
| Naming: "primitives" or "atoms"? | **Atoms** |
| Template editing location? | **Separate modal** — click to use, edit icon to modify |
| Capture groups / output references? | **Labeled outputs** on combinators, wired visually to Groups |
| Legacy migration? | **Auto-convert** via mapping layer at load time |
| Custom predicates? | **Yes** — mini-expression language `[a-zA-Z_]` |
| Encoding detection? | **Auto-detect** via BOM sniffing as an option |
| Reverse as property or node? | **Standalone atom** (composable, not embedded in Group) |
| Byte types beyond int? | **Yes** — ReadShort, ReadFloat, ReadDouble, ReadLong |
| Replace XSLT with in-graph transforms? | **Yes** — Output Transform Layer with Transform + Structure nodes |
| Spaghetti wiring in complex graphs? | **Label References** — publish/subscribe virtual wiring |
| Reuse of transform/structure groups? | **Yes** — save any node group as a template, including transform-only and full pipeline templates |
| Preview data during construction? | **Live Data Preview** panel with match highlighting, output preview, isolated from production engine |
| Execution profiling? | **Construction-time decorator wiring** — timing wrappers injected during virtual compilation, zero overhead when not wired in |
| AI-assisted construction? | **AI Chat Assistant** panel with describe→generate, iterative refinement, and debug help |
