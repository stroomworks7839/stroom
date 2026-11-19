/*
 * Copyright 2026 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.shapeshifter.client.model;

import stroom.shapeshifter.config.Cast;
import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.EngineVars;
import stroom.shapeshifter.config.OutputNode;
import stroom.shapeshifter.config.OutputNode.ApplyTemplates;
import stroom.shapeshifter.config.OutputNode.Attribute;
import stroom.shapeshifter.config.OutputNode.CallTemplate;
import stroom.shapeshifter.config.OutputNode.Choose;
import stroom.shapeshifter.config.OutputNode.Element;
import stroom.shapeshifter.config.OutputNode.EmitError;
import stroom.shapeshifter.config.OutputNode.ForEach;
import stroom.shapeshifter.config.OutputNode.ForEachGroup;
import stroom.shapeshifter.config.OutputNode.If;
import stroom.shapeshifter.config.OutputNode.Namespace;
import stroom.shapeshifter.config.OutputNode.Switch;
import stroom.shapeshifter.config.OutputNode.Text;
import stroom.shapeshifter.config.OutputNode.Transform;
import stroom.shapeshifter.config.OutputNode.ValueOf;
import stroom.shapeshifter.config.OutputNode.Variable;
import stroom.shapeshifter.config.RefExpression;
import stroom.shapeshifter.config.RefExpression.MatchIndex;
import stroom.shapeshifter.config.RefExpression.RefPart;
import stroom.shapeshifter.config.json.JsonObject;
import stroom.shapeshifter.config.json.JsonText;
import stroom.shapeshifter.config.json.JsonValue;
import stroom.shapeshifter.config.json.ProjectJson;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// What a card says about its instruction: the kind, spelt as the wire format spells it; a
/// one-line summary; and the category the add menu groups it under (design 18 §5.6: output,
/// invoke, control, transform, collection).
public final class Instructions {

    /// Nine digits always fit an int, so a group number of at most that many never overflows.
    private static final int GROUP_DIGITS = 9;

    /// What a subscript calls the last populated entry, the one index rule that is not a value.
    private static final String LAST_ENTRY = "last";

    /// What a call wears after it: `max(xs) as number`, `get(m, "k") or "-"`.
    private static final String CAST_KEYWORD = "as";
    private static final String DEFAULT_KEYWORD = "or";

    public enum Category {
        OUTPUT("output"),
        INVOKE("invoke"),
        CONTROL("control"),
        TRANSFORM("transform"),
        COLLECTION("collection");

        private final String label;

        Category(final String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /// The kinds an author writes constantly, each with a form of its own in the card editor.
    public static final List<String> FORM_KINDS = List.of(
            "text", "value-of", "element", "attribute", "namespace", "emit-error",
            "apply-templates", "call-template",
            "if", "choose", "switch", "variable", "for-each", "for-each-group",
            "append", "insert", "put", "remove", "clear",
            // Every transform has a form (design 18 Q20, carried through 2026-11-12).
            // `translate` has one of its own for its replacement lists; fifteen are one shape —
            // selects and an optional name, shaped by the arity the kind takes; eight are a
            // select, one word and a name; and the rest have a form each.
            "translate",
            "trim", "lower-case", "upper-case", "normalize-space",
            "add", "divide", "round", "string-length",
            "number", "subtract", "multiply", "mod", "floor", "ceiling", "abs",
            "string-join", "tokenize", "substring-before", "substring-after",
            "starts-with", "ends-with", "contains", "format-number",
            "replace", "substring", "format-date", "parse-date", "decode", "call");

    /// The transforms with no form of their own, edited as their wire form and listed for the
    /// add menu. **Empty**, since 2026-11-12: every transform in the vocabulary has a form, so
    /// the wire form is the editor's escape for a construct the model grows and the forms have
    /// not caught up with, rather than somewhere an author is sent for ordinary work.
    ///
    /// <p>Nothing here may also be in [#FORM_KINDS]: the add menu is the two lists
    /// concatenated, so a kind in both is offered twice. `BodiesTest` holds them disjoint.
    public static final List<String> TRANSFORM_KINDS = List.of();

    private Instructions() {
    }

    /// The kind, from the wire form's own tag: the one reading of the vocabulary.
    public static String kind(final OutputNode node) {
        final JsonValue wire = ProjectJson.writeOutput(node);
        if (wire.isString()) {
            return wire.asString();
        }
        for (final Map.Entry<String, JsonValue> entry : ((JsonObject) wire).entries()) {
            return entry.getKey();
        }
        return "?";
    }

    public static Category category(final String kind) {
        switch (kind) {
            case "text":
            case "value-of":
            case "element":
            case "attribute":
            case "namespace":
            case "emit-error":
                return Category.OUTPUT;
            case "apply-templates":
            case "call-template":
                return Category.INVOKE;
            case "if":
            case "choose":
            case "switch":
            case "variable":
            case "for-each":
            case "for-each-group":
                return Category.CONTROL;
            case "append":
            case "insert":
            case "put":
            case "remove":
            case "clear":
                return Category.COLLECTION;
            default:
                return Category.TRANSFORM;
        }
    }

    /// The one line a card shows beside its kind.
    public static String describe(final OutputNode node) {
        if (node instanceof Text t) {
            return quote(t.value());
        } else if (node instanceof ValueOf v) {
            return ref(v.select());
        } else if (node instanceof Element e) {
            return "<" + e.name() + ">" + (e.omitIfEmpty()
                    ? " (omit if empty)"
                    : "");
        } else if (node instanceof Attribute a) {
            return a.name() + "=…" + (a.omitIfEmpty()
                    ? " (omit if empty)"
                    : "");
        } else if (node instanceof Namespace n) {
            return (n.prefix() == null
                    ? "xmlns"
                    : "xmlns:" + n.prefix()) + "=" + quote(n.uri());
        } else if (node instanceof EmitError e) {
            return e.severity().name().toLowerCase(Locale.ROOT) + ": " + ref(e.message());
        } else if (node instanceof ApplyTemplates a) {
            return "select " + ref(a.directive().select()) + " in mode " + Modes.label(a.directive().mode());
        } else if (node instanceof CallTemplate c) {
            return c.name();
        } else if (node instanceof If i) {
            return GuardClause.describe(i.test());
        } else if (node instanceof Choose c) {
            return c.when().size() + (c.when().size() == 1
                    ? " branch"
                    : " branches") + (c.otherwise().isEmpty()
                    ? ""
                    : " and otherwise");
        } else if (node instanceof Switch s) {
            return "on " + ref(s.select()) + ", " + s.cases().size() + " cases";
        } else if (node instanceof Variable v) {
            return v.name();
        } else if (node instanceof ForEach f) {
            return ref(f.select()) + " as " + f.as();
        } else if (node instanceof ForEachGroup g) {
            return ref(g.select()) + " by " + ref(g.groupBy());
        } else if (node instanceof Transform t) {
            final StringBuilder sb = new StringBuilder();
            for (final RefExpression select : t.select()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(ref(select));
            }
            return sb + (t.name() == null
                    ? ""
                    : " → " + t.name());
        }
        // The collection ops and anything new: the wire form, compact and cut.
        final String wire = JsonText.print(ProjectJson.writeOutput(node));
        return wire.length() > 80
                ? wire.substring(0, 77) + "…"
                : wire;
    }

    /// A reference as a field spells it, or its wire form when it has no spelling.
    public static String ref(final RefExpression ref) {
        if (ref == null) {
            // An optional reference the configuration leaves out — a put into a set has no key,
            // a for-each-group by the entry's own value has no group-by — is a blank field, not
            // a document the form refuses to show (design 44 §5x).
            return "";
        }
        final String text = spell(ref);
        return text != null
                ? text
                : JsonText.print(ProjectJson.writeRefOrNameWire(ref));
    }

    /// The form's spelling of a reference, or null where the form cannot show it and the wire has
    /// to (design 44 §5u).
    ///
    /// The editor spells a capture group `$1`, as the variables pane has always spelt a
    /// name `$k`: a dollar introduces a value. The wire has no short form for a numbered
    /// group — `group()` is already one of design 35 §6's counters, so it could not have one
    /// — and reading the wire's silence as "the form cannot show this" sent every reference to a
    /// numbered group to the raw JSON, which is what a migrated `apply-templates` always
    /// selects.
    public static String spell(final RefExpression ref) {
        if (ref == null) {
            // An absent reference has no spelling. The wire's refOrName dereferences it, so
            // asking about a node with an optional reference threw rather than answering.
            return null;
        }
        if (ref.parts().size() == 1) {
            return spellPart(ref.parts().get(0), ref);
        }
        // Several parts, juxtaposed in order (§5w). One part the form cannot spell makes the
        // whole expression unspellable: a reference shown with a piece missing would be a lie.
        final StringBuilder out = new StringBuilder();
        for (final RefPart part : ref.parts()) {
            final String spelt = spellPart(part, null);
            if (spelt == null) {
                return null;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(spelt);
        }
        return out.toString();
    }

    /// One part. `whole` is the expression it came from where it is the only part, so a
    /// bare name and a counter keep the wire's own spelling of themselves; null within a
    /// sequence, where the wire has nothing to say about a part on its own.
    private static String spellPart(final RefPart part, final RefExpression whole) {
        if (part instanceof final RefPart.Text text) {
            return quoteLiteral(text.value());
        }
        if (part instanceof final RefPart.Capture capture) {
            // A subscript says which match to read: bytes[i], heading[matchCount()] (design
            // 44 §5aa). Nothing without an index rule carries one.
            final String index = capture.matchIndex() == null
                    ? ""
                    : spellIndex(capture.matchIndex());
            if (index == null) {
                return null;
            }
            if (capture.label() != null) {
                // A group the pattern named. The dollar says "a group of this match" either way:
                // digits are its number, a word is its label (design 44 §5y) — so a label of
                // digits has no spelling of its own, and the wire keeps it. Nor does a label
                // with a subscript: the document cannot hold one (ReferenceJson: "a capture
                // reference by label names nothing else"), so the form must not offer it.
                return capture.varId() == null && capture.group() == 0
                       && capture.matchIndex() == null
                       && spellableName(capture.label()) && !isDigits(capture.label())
                        ? "$" + capture.label()
                        : null;
            }
            if (capture.varId() == null) {
                return "$" + capture.group() + index;
            }
            if (capture.group() == 0) {
                // A bare modifier word binds to the call before it, so a variable of that name
                // could not be told from a call wearing one. Only the bare spelling collides:
                // $as is a label and as[i] wears a subscript, and neither is a modifier.
                return spellableName(capture.varId())
                       && !(index.isEmpty() && isModifier(capture.varId()))
                        ? capture.varId() + index
                        : null;
            }
            return null;
        }
        if (part instanceof final RefPart.Accessor accessor) {
            // A function of a collection, as the counters are functions of the match: size(xs),
            // get(m, "k") (design 44 §5z), wearing what the call has no room for after it:
            // get(m, "k") or "-", max(xs) as number (design 44 §5ab).
            final String of = spell(accessor.of());
            if (of == null) {
                return null;
            }
            final String key = accessor.key() == null
                    ? null
                    : spell(accessor.key());
            if (accessor.key() != null && key == null) {
                return null;
            }
            final String call = accessor.kind().spelling() + "(" + of + (key == null
                    ? ""
                    : ", " + key) + ")";
            if (accessor.as() != null) {
                return call + " " + CAST_KEYWORD + " " + castName(accessor.as());
            }
            if (accessor.orElse() != null) {
                // The default is one word, because a modifier binds to the word after it: a
                // default of several parts would be read back as a default and then a sequence.
                final String orElse = spell(accessor.orElse());
                return orElse == null || !isOneToken(orElse)
                        ? null
                        : call + " " + DEFAULT_KEYWORD + " " + orElse;
            }
            return call;
        }
        if (part instanceof final RefPart.Counter counter) {
            final String index = counter.matchIndex() == null
                    ? ""
                    : spellIndex(counter.matchIndex());
            if (index != null) {
                return counter.counter().spelling() + index;
            }
        }
        return whole == null
                ? null
                : ProjectJson.refOrName(whole);
    }

    /// An index rule as a subscript: `[3]` the third, `[+1]` and `[-1]` relative
    /// to this match, `[last]` the last populated entry, `[i]` an index a variable
    /// holds and `[matchCount()]` one a function answers (design 44 §5aa).
    ///
    /// Null where the rule cannot be said without losing part of itself: an index the engine
    /// ignores, a negative absolute, or a variable named for the keyword.
    private static String spellIndex(final MatchIndex index) {
        final boolean plain = index.index() == 0 && !index.isOffset();
        if (index.varRef() != null) {
            // [last] is the keyword, so a variable of that name has no subscript to be spelt in.
            return plain && !index.isLast() && spellableName(index.varRef())
                   && !LAST_ENTRY.equals(index.varRef())
                    ? "[" + index.varRef() + "]"
                    : null;
        }
        if (index.counter() != null) {
            return plain && !index.isLast()
                    ? "[" + index.counter().spelling() + "]"
                    : null;
        }
        if (index.isLast()) {
            // The engine ignores the index here, but dropping it would be a lossy round trip.
            return plain
                    ? "[" + LAST_ENTRY + "]"
                    : null;
        }
        if (index.isOffset()) {
            return "[" + (index.index() < 0
                    ? ""
                    : "+") + index.index() + "]";
        }
        return index.index() < 0
                ? null
                : "[" + index.index() + "]";
    }

    /// A line typed into a card list, as the cards it describes (design 47 A, ruled 2026-11-17).
    ///
    /// Text is a `text` card and a capture reference — `$1`, `$label` — is a `value-of` card. A
    /// line that mixes them is **split into the sequence**: `<pair key="$1">$2</pair>` is five
    /// cards, text, value-of, text, value-of, text, exactly as if they had been added one at a
    /// time. The templated line is a way of typing and nothing more. What is stored, shown and
    /// traced is separate instructions, because the trace is per instruction: the output pane
    /// colours each card's output in the card's own hue, and a line written by one card would be
    /// one mark.
    ///
    /// The rules, all of them:
    ///
    /// - `$` and digits is a numbered group, `$` and a word is a labelled one, read by [#read]
    ///   so that a reference means here what it means in a Select field.
    /// - `${…}` is a reference of any kind (design 47 H, ruled 2026-11-19): what is inside the
    ///   braces is a Select field's spelling, read by [#read] — `${record_body}`, a declared
    ///   name, or `${heading[matchCount()]}`. It is not a guess, because the author has said
    ///   "reference". A spelling that is not one is refused, saying why: a modifier that did not
    ///   bind, as the Select field refuses it, and a name no author could mean — one holding the
    ///   grammar's own punctuation, `heading[matchCount()`, or only digits, `1`. A `}` inside a
    ///   quoted literal does not end it.
    /// - **Always a reference**, whether or not the template's match has that capture. The same
    ///   line makes the same cards whatever the pattern holds at the time, including when the
    ///   output is written before the pattern is.
    /// - `$$` writes a dollar, so `$${HOME}` is the text `${HOME}`. Any other `$` followed by
    ///   neither a digit, a letter nor `{` is itself.
    /// - A label here is letters, digits and underscores, and ends at the first character that
    ///   is none of those — so `$key">` is the label `key` and then text. A label spelt with
    ///   anything else is written through the Select field, whose grammar can hold it.
    /// - **A bare word is text**, never a declared name. This field does not guess; names and
    ///   functions go through the kind menu.
    /// - Text keeps its spaces, and spells a line break `\n` ([ControlEscapes]), because Enter
    ///   is what adds the card.
    ///
    /// @param line The line as typed.
    /// @return The cards, in order; none for an empty line.
    /// @throws ConfigException Saying why, where a `${…}` is unclosed, empty, or not a reference.
    public static List<OutputNode> cards(final String line) {
        final List<OutputNode> cards = new ArrayList<>();
        if (line == null) {
            return cards;
        }
        final StringBuilder text = new StringBuilder();
        int at = 0;
        while (at < line.length()) {
            final char c = line.charAt(at);
            if (c != '$') {
                text.append(c);
                at++;
            } else if (at + 1 < line.length() && line.charAt(at + 1) == '$') {
                text.append('$');
                at += 2;
            } else if (at + 1 < line.length() && line.charAt(at + 1) == '{') {
                final int close = closingBrace(line, at + 2);
                flushText(text, cards);
                cards.add(new ValueOf(braced(close < 0
                        ? null
                        : line.substring(at + 2, close))));
                at = close + 1;
            } else {
                final int end = referenceEnd(line, at + 1);
                final String token = line.substring(at, end);
                if (isGroup(token) || isLabel(token)) {
                    flushText(text, cards);
                    cards.add(new ValueOf(read(token)));
                    at = end;
                } else {
                    text.append('$');
                    at++;
                }
            }
        }
        flushText(text, cards);
        return cards;
    }

    // What is inside a ${…}, as the reference it spells: refused, saying why, where the braces
    // are unclosed (null) or empty, or hold what a Select field would not take.
    private static RefExpression braced(final String inner) {
        if (inner == null) {
            throw new ConfigException("A ${ needs its closing }. $$ writes a dollar.");
        }
        final String spelling = inner.trim();
        if (spelling.isEmpty()) {
            throw new ConfigException("${} needs a reference inside it, such as ${name}");
        }
        final RefExpression ref = readChecked(spelling);
        // What the reader could make nothing of, it reads as one name, whole; a name holding
        // the grammar's punctuation is a spelling that went wrong, not a name anyone declared
        if (ref.parts().size() == 1 && ref.parts().get(0) instanceof final RefPart.Capture capture
            && capture.varId() != null && capture.group() == 0 && capture.matchIndex() == null
            && (!spellableName(capture.varId()) || isDigits(capture.varId()))) {
            throw new ConfigException("${" + spelling + "} is not a reference: "
                                      + (isDigits(capture.varId())
                    ? "a capture group is $" + capture.varId()
                    : "check its brackets and quotes"));
        }
        return ref;
    }

    /// A spelling as a reference, refused where the grammar could not finish reading it: a
    /// modifier that did not bind is the one fault it can name (design 44 §5ad). For whatever
    /// takes a reference typed by an author — a Select field, a `${…}` in a line.
    ///
    /// @param text The spelling.
    /// @return The reference.
    /// @throws ConfigException Saying what is wrong.
    public static RefExpression readChecked(final String text) {
        final RefExpression ref = read(text);
        final String fault = fault(ref);
        if (fault != null) {
            throw new ConfigException(fault);
        }
        return ref;
    }

    // The } that closes a ${ whose spelling starts at from, or -1 for none. A quoted literal in
    // the spelling may hold a }, and a \" inside one does not end it; a quote left open is
    // refused as that, since the } the author typed is inside it.
    private static int closingBrace(final String line, final int from) {
        boolean quoted = false;
        for (int i = from; i < line.length(); i++) {
            final char c = line.charAt(i);
            if (quoted && c == '\\') {
                i++;
            } else if (c == '"') {
                quoted = !quoted;
            } else if (c == '}' && !quoted) {
                return i;
            }
        }
        if (quoted) {
            throw new ConfigException("A quote inside ${…} is not closed");
        }
        return -1;
    }

    // Where a reference that starts after a dollar ends: a run of digits, or a run of the
    // characters a label typed inline may hold.
    private static int referenceEnd(final String line, final int from) {
        int end = from;
        if (end < line.length() && isDigit(line.charAt(end))) {
            while (end < line.length() && isDigit(line.charAt(end))) {
                end++;
            }
        } else {
            while (end < line.length() && isLabelChar(line.charAt(end))) {
                end++;
            }
        }
        return end;
    }

    private static boolean isDigit(final char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isLabelChar(final char c) {
        return c == '_' || isDigit(c) || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }

    private static void flushText(final StringBuilder text, final List<OutputNode> cards) {
        if (text.length() > 0) {
            cards.add(new Text(ControlEscapes.unescape(text.toString())));
            text.setLength(0);
        }
    }

    /// A reference as the form writes it: a sequence of parts, juxtaposed (design 44 §5w).
    ///
    /// `"on " $1 " at " when` is four parts — a literal, this match's group 1, a literal,
    /// and the name `when` — concatenated in that order, which is what a `value-of`
    /// of several parts is. A lone part spells as itself, so the simple cases read as they always
    /// did: `$1`, `when`, `index()`.
    public static RefExpression read(final String text) {
        final List<RefPart> parts = parts(text == null
                ? ""
                : text);
        if (parts == null) {
            return ProjectJson.readRefOrName(text == null
                    ? ""
                    : text.trim());
        }
        return new RefExpression(parts);
    }

    /// The tokens of a spelling as parts, or null where it is one bare token the wire can read.
    private static List<RefPart> parts(final String text) {
        final List<String> tokens = tokenise(text);
        if (tokens == null) {
            return null;
        }
        final List<RefPart> parts = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            final String token = tokens.get(i);
            // A modifier is not a part of its own: it binds to the call before it, taking the
            // word after it with it (design 44 §5ab).
            final RefPart.Accessor modified = i + 1 < tokens.size() && !parts.isEmpty()
                                              && parts.get(parts.size() - 1) instanceof final RefPart.Accessor before
                    ? modified(before, token, tokens.get(i + 1))
                    : null;
            if (modified != null) {
                parts.set(parts.size() - 1, modified);
                i++;
                continue;
            }
            // Once per token: a call's arguments are read by this same method, so reading one
            // twice to ask what it is would cost twice as much again at every level of nesting.
            final RefPart part = partOf(token);
            if (part == null) {
                if (tokens.size() == 1) {
                    // One name or one function: the wire's own reading, unchanged.
                    return null;
                }
                parts.addAll(ProjectJson.readRefOrName(token).parts());
            } else {
                parts.add(part);
            }
        }
        return parts;
    }

    /// A call wearing a modifier — `max(xs) as number`, `get(m, "k") or "-"` — or null
    /// where the two words after it are not one. `as` is for `min` and `max` and
    /// `or` is for `get`; anywhere else the words are parts in their own right.
    private static RefPart.Accessor modified(final RefPart.Accessor accessor, final String keyword,
                                             final String argument) {
        try {
            if (CAST_KEYWORD.equals(keyword) && accessor.as() == null) {
                final Cast cast = castOf(argument);
                return cast == null
                        ? null
                        : new RefPart.Accessor(accessor.kind(), accessor.of(), accessor.key(),
                                accessor.orElse(), cast);
            }
            if (DEFAULT_KEYWORD.equals(keyword) && accessor.orElse() == null) {
                final RefExpression orElse = oneWord(argument);
                return orElse == null
                        ? null
                        : new RefPart.Accessor(accessor.kind(), accessor.of(), accessor.key(),
                                orElse, accessor.as());
            }
        } catch (final RuntimeException e) {
            // The kind takes no such modifier, so those were two ordinary words after all.
            return null;
        }
        return null;
    }

    /// One token as an expression of its own, or null where the form could not spell it back.
    private static RefExpression oneWord(final String token) {
        final RefPart part = partOf(token);
        if (part != null) {
            return new RefExpression(List.of(part));
        }
        // A name and a counter are the two the wire reads and this method does not: without
        // them a default of index() would be spelt and then not read back, which is the one
        // thing the form must never do (design 44 §5u).
        return spellableName(token) || isCounter(token)
                ? ProjectJson.readRefOrName(token)
                : null;
    }

    /// Whether a token is one of the engine's functions, spelt `index()`.
    private static boolean isCounter(final String token) {
        return token.endsWith("()")
               && EngineVars.byName(token.substring(0, token.length() - 2)) != null;
    }

    /// The two words a modifier is spelt with, which no bare name may be spelt as.
    private static boolean isModifier(final String word) {
        return CAST_KEYWORD.equals(word) || DEFAULT_KEYWORD.equals(word);
    }

    /// What is wrong with a spelling the author has typed, in words they can act on, or null
    /// where there is nothing to say. A modifier that did not bind is the one fault the grammar
    /// can name: it is a reserved word, so it is never the name it would otherwise be read as,
    /// and saying so beats saving a call and two undeclared reads (design 44 §5ad).
    public static String fault(final String text) {
        return fault(read(text));
    }

    // What is wrong with a reference already read, or null
    private static String fault(final RefExpression ref) {
        for (final RefPart part : ref.parts()) {
            if (part instanceof final RefPart.Capture capture && isModifier(capture.varId())) {
                return CAST_KEYWORD.equals(capture.varId())
                        ? "'as' comes after min or max and takes a cast: " + casts()
                        : "'or' comes after get and takes a default after it, such as or \"-\"";
            }
        }
        return null;
    }

    private static String casts() {
        final StringBuilder out = new StringBuilder();
        for (final Cast cast : Cast.values()) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(castName(cast));
        }
        return out.toString();
    }

    /// Whether a spelling is one word, so that a modifier can carry it.
    private static boolean isOneToken(final String spelling) {
        final List<String> tokens = tokenise(spelling);
        return tokens != null && tokens.size() == 1;
    }

    /// A cast by the name the wire format spells it with, or null where there is no such cast.
    private static Cast castOf(final String name) {
        for (final Cast cast : Cast.values()) {
            if (castName(cast).equals(name)) {
                return cast;
            }
        }
        return null;
    }

    private static String castName(final Cast cast) {
        return cast.name().toLowerCase(Locale.ROOT);
    }

    /// One token as the part it spells, or null where only the wire can read it.
    private static RefPart partOf(final String token) {
        if (isQuoted(token)) {
            return new RefPart.Text(unquote(token));
        }
        // Before the group and the label: $1[+1] wears a subscript, it is not a label whose name
        // happens to start with a digit.
        final RefPart subscript = subscripted(token);
        if (subscript != null) {
            return subscript;
        }
        if (isGroup(token)) {
            return new RefPart.Capture(null, Integer.parseInt(token.substring(1)), null);
        }
        if (isLabel(token)) {
            return RefPart.Capture.label(token.substring(1));
        }
        return accessorOf(token);
    }

    /// The spelling split into tokens: quoted literals whole, everything else on whitespace. Null
    /// where the quoting does not close, so an author mid-edit is not told their text is a name.
    private static List<String> tokenise(final String text) {
        final List<String> tokens = new ArrayList<>();
        final StringBuilder token = new StringBuilder();
        boolean quoted = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (quoted) {
                // Inside a literal nothing is punctuation: a bracket does not nest and a space
                // does not divide.
                token.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    quoted = false;
                    if (depth == 0) {
                        tokens.add(token.toString());
                        token.setLength(0);
                    }
                }
            } else if (c == '"') {
                if (depth == 0 && token.length() > 0) {
                    tokens.add(token.toString());
                    token.setLength(0);
                }
                quoted = true;
                token.append(c);
            } else if ((c == ' ' || c == '\t' || c == '\n') && depth == 0) {
                if (token.length() > 0) {
                    tokens.add(token.toString());
                    token.setLength(0);
                }
            } else {
                // A call is one token however its arguments are spaced: get(m, "k") is not three.
                // A subscript nests the same way, so bytes[matchCount()] is one token too.
                if (c == '(' || c == '[') {
                    depth++;
                } else if (c == ')' || c == ']') {
                    depth--;
                }
                token.append(c);
            }
        }
        if (quoted || depth != 0) {
            return null;
        }
        if (token.length() > 0) {
            tokens.add(token.toString());
        }
        return tokens.isEmpty()
                ? null
                : tokens;
    }

    /// A token wearing a subscript — `bytes[i]`, `$1[+1]`, `matchCount()[2]` —
    /// as the part it is, or null where it wears none or the base is not something that can carry
    /// one (design 44 §5aa).
    private static RefPart subscripted(final String token) {
        if (!token.endsWith("]")) {
            return null;
        }
        final int open = token.lastIndexOf('[');
        if (open <= 0) {
            return null;
        }
        final MatchIndex index = matchIndexOf(token.substring(open + 1, token.length() - 1));
        if (index == null) {
            return null;
        }
        final String base = token.substring(0, open);
        if (isGroup(base)) {
            return new RefPart.Capture(null, Integer.parseInt(base.substring(1)), index);
        }
        if (isLabel(base)) {
            // The document cannot hold a labelled capture with an index, so neither does the
            // form: $when[i] is not a reference, and the text stands as the wire reads it.
            return null;
        }
        if (base.endsWith("()")) {
            final EngineVars counter = EngineVars.byName(base.substring(0, base.length() - 2));
            return counter == null
                    ? null
                    : new RefPart.Counter(counter, index);
        }
        return spellableName(base)
                ? new RefPart.Capture(base, 0, index)
                : null;
    }

    /// What is inside a subscript as an index rule, or null where it is not one.
    private static MatchIndex matchIndexOf(final String inner) {
        if (inner.isEmpty()) {
            return null;
        }
        if (LAST_ENTRY.equals(inner)) {
            return new MatchIndex(0, false, true, null, null);
        }
        final char first = inner.charAt(0);
        final boolean relative = first == '+' || first == '-';
        final String digits = relative
                ? inner.substring(1)
                : inner;
        if (relative || isDigits(digits)) {
            // A sign with nothing after it is half-written, not an index of nothing.
            return isDigits(digits) && digits.length() <= GROUP_DIGITS
                    ? new MatchIndex(first == '-'
                            ? -Integer.parseInt(digits)
                            : Integer.parseInt(digits), relative, false, null, null)
                    : null;
        }
        if (inner.endsWith("()")) {
            final EngineVars counter = EngineVars.byName(inner.substring(0, inner.length() - 2));
            return counter == null
                    ? null
                    : new MatchIndex(0, false, false, null, counter);
        }
        return spellableName(inner)
                ? new MatchIndex(0, false, false, inner, null)
                : null;
    }

    /// A token as an accessor — `size(xs)`, `get(m, "k")` — or null where it is not
    /// one. The arguments are spellings in their own right, so a collection may be a name, a
    /// group, or another accessor.
    private static RefPart.Accessor accessorOf(final String token) {
        final int open = token.indexOf('(');
        if (open <= 0 || !token.endsWith(")")) {
            return null;
        }
        final RefPart.Accessor.Kind kind = kindOf(token.substring(0, open));
        if (kind == null) {
            return null;
        }
        final List<String> args = arguments(token.substring(open + 1, token.length() - 1));
        if (args == null || args.isEmpty() || args.size() > 2 || args.contains("")) {
            // An argument left blank is half-written: get(, "k") is not a call over a variable
            // with no name, and saving it as one would put the author's text beyond the form.
            return null;
        }
        final RefExpression of = read(args.get(0));
        final RefExpression key = args.size() > 1
                ? read(args.get(1))
                : null;
        try {
            return new RefPart.Accessor(kind, of, key, null, null);
        } catch (final RuntimeException e) {
            // get and contains need a key and the rest take none; a spelling that disagrees is
            // not an accessor, and the wire form says so rather than the form inventing one.
            return null;
        }
    }

    private static RefPart.Accessor.Kind kindOf(final String name) {
        for (final RefPart.Accessor.Kind kind : RefPart.Accessor.Kind.values()) {
            if (kind.spelling().equals(name)) {
                return kind;
            }
        }
        return null;
    }

    /// The arguments of a call, split on the commas that are not inside quotes or a nested call.
    private static List<String> arguments(final String inner) {
        final List<String> args = new ArrayList<>();
        final StringBuilder arg = new StringBuilder();
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < inner.length(); i++) {
            final char c = inner.charAt(i);
            if (quoted) {
                arg.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    quoted = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> {
                    quoted = true;
                    arg.append(c);
                }
                case '(' -> {
                    depth++;
                    arg.append(c);
                }
                case ')' -> {
                    depth--;
                    arg.append(c);
                }
                case ',' -> {
                    if (depth == 0) {
                        args.add(arg.toString().trim());
                        arg.setLength(0);
                    } else {
                        arg.append(c);
                    }
                }
                default -> arg.append(c);
            }
        }
        if (quoted || depth != 0) {
            return null;
        }
        if (arg.length() > 0) {
            args.add(arg.toString().trim());
        }
        return args;
    }

    /// A dollar and a word: the group a pattern named, as `$1` is the group it numbered.
    private static boolean isLabel(final String token) {
        return token.length() > 1 && token.charAt(0) == '$' && !isDigits(token.substring(1))
               && spellableName(token.substring(1));
    }

    private static boolean isQuoted(final String token) {
        return token.length() >= 2 && token.charAt(0) == '"' && token.charAt(token.length() - 1) == '"';
    }

    /// More digits than a group number can hold is not a group, so the reading never overflows.
    private static boolean isGroup(final String token) {
        return token.length() > 1 && token.length() <= GROUP_DIGITS + 1 && token.charAt(0) == '$'
               && isDigits(token.substring(1));
    }

    private static String unquote(final String token) {
        final String inner = token.substring(1, token.length() - 1);
        final StringBuilder out = new StringBuilder(inner.length());
        for (int i = 0; i < inner.length(); i++) {
            final char c = inner.charAt(i);
            if (c == '\\' && i + 1 < inner.length()) {
                final char next = inner.charAt(++i);
                switch (next) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    default -> out.append(next);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String quoteLiteral(final String value) {
        final StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

    /// Whether a name survives being written into the field and read back. Whitespace would split
    /// it into two parts, a quote would open a literal, and a trailing `()` would read as a
    /// counter. A name that cannot be spelt faithfully is not spelt at all, and the wire form
    /// takes it.
    private static boolean spellableName(final String name) {
        if (name.isEmpty() || name.endsWith("()") || name.charAt(0) == '$') {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            // The grammar's own punctuation: a name wearing any of it would be read back as
            // something else — "a,b" inside get() as two arguments, "a[1]" as a subscript.
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '"' || c == '\\'
                || c == '(' || c == ')' || c == '[' || c == ']' || c == ',') {
                return false;
            }
        }
        return true;
    }

    private static boolean isDigits(final String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                return false;
            }
        }
        return !text.isEmpty();
    }

    private static String quote(final String text) {
        final String shown = text.length() > 60
                ? text.substring(0, 57) + "…"
                : text;
        return "\"" + shown.replace("\n", "⏎") + "\"";
    }
}
