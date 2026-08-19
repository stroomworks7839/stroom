# Oniguruma test corpus

`test_utf8.c` is the Oniguruma project's UTF-8 test suite, taken unaltered from the snapshot
the Rust `fancy-regex` crate vendors for the same purpose — testing a backtracking layer over
an RE2-style core, which is this engine's architecture too ([D27](../../../../../design/00-decisions.md)).

  Original: https://github.com/kkos/oniguruma/blob/master/test/test_utf8.c
  Via:      https://github.com/fancy-regex/fancy-regex/tree/main/tests/oniguruma
  Licence:  BSD-2-Clause (below)

Converted by `tools/convert-oniguruma-corpus.py` into `oniguruma.cases`, the line-based form
read by `OnigurumaCorpusTest`. What the Rust corpus cannot provide, this one can: Oniguruma is
a backtracking engine, so its suite is dense in exactly the constructs of the fancy tier —
backreferences, lookaround, atomic groups — with expected spans as **byte offsets over
UTF-8**, which is this engine's native coordinate system.

The dialect is Ruby's, so the conversion compiles every case under MULTILINE (`^` and `$` are
always line anchors there) and drops, with a printed tally, only the cases whose *spelling*
this parser would silently misread — Oniguruma-only escapes such as `\O`, `\h`, `\g<...>` and
`\c`, which this dialect reads as literals. Cases the engine merely refuses stay in, and
`OnigurumaCorpusTest` counts the refusals by reason, so the scope boundary is reported rather
than filtered away. Cases that compile but disagree for a *documented dialect reason* are
listed in `ignore`, one line each with the reason — the fancy-regex project maintains the same
file for the same corpus.

## Oniguruma LICENSE

Copyright (c) 2002-2019  K.Kosako  <kkosako0@gmail.com>
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:
1. Redistributions of source code must retain the above copyright
   notice, this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright
   notice, this list of conditions and the following disclaimer in the
   documentation and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE AUTHOR AND CONTRIBUTORS ``AS IS'' AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED.  IN NO EVENT SHALL THE AUTHOR OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
SUCH DAMAGE.
