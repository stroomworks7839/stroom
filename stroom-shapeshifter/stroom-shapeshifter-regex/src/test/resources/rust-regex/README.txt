Test cases derived from the Rust `regex` crate's corpus.

  Source:  https://github.com/rust-lang/regex/tree/master/testdata
  Licence: MIT OR Apache-2.0 (this project uses them under Apache-2.0)

Converted by tools/convert-rust-corpus.py from the original TOML into the line-based form read
by RustCorpusTest, so that this module keeps its zero runtime and test dependencies. Nothing was
altered beyond the change of file format.

Only tests asking a question this engine can answer at all were converted; the exclusions are:

    37  byte-oriented mode over a non-ASCII haystack, where a match may split a character; this engine reaches that through the RAW encoding, not through (?-u)
    20  haystacks that are not UTF-8
    17  leftmost-longest match semantics
    10  a configurable line terminator
     9  earliest/overlapping search
     5  match limits
     5  patterns Rust refuses to compile, for Rust's own reasons
     3  regex sets (several patterns searched at once)

Tests this engine merely refuses to compile are NOT excluded here — RustCorpusTest counts those
in its report, so that the scope boundary stays visible rather than being filtered away.
