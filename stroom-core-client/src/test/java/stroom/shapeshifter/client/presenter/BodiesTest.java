/*
 * Copyright 2016 Crown Copyright
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

package stroom.shapeshifter.client.presenter;

import stroom.shapeshifter.config.Cast;
import stroom.shapeshifter.config.Condition;
import stroom.shapeshifter.config.EngineVars;
import stroom.shapeshifter.config.OutputNode;
import stroom.shapeshifter.config.OutputNode.Choose;
import stroom.shapeshifter.config.OutputNode.Element;
import stroom.shapeshifter.config.OutputNode.ForEach;
import stroom.shapeshifter.config.OutputNode.If;
import stroom.shapeshifter.config.OutputNode.Order;
import stroom.shapeshifter.config.OutputNode.Sort;
import stroom.shapeshifter.config.OutputNode.Switch;
import stroom.shapeshifter.config.OutputNode.SwitchCase;
import stroom.shapeshifter.config.OutputNode.Text;
import stroom.shapeshifter.config.OutputNode.ValueOf;
import stroom.shapeshifter.config.OutputNode.WhenBranch;
import stroom.shapeshifter.config.RefExpression;
import stroom.shapeshifter.config.RefExpression.MatchIndex;
import stroom.shapeshifter.config.RefExpression.RefPart;
import stroom.shapeshifter.config.RefExpression.RefPart.Accessor.Kind;
import stroom.shapeshifter.config.json.ProjectJson;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The body editor's rewrites: paths into branches, holders keeping their heads, every result readable. */
class BodiesTest {

    private static final Text A = new Text("a");
    private static final Text B = new Text("b");
    private static final Text C = new Text("c");
    private static final Condition FIRST = new Condition.IsFirst();
    private static final List<OutputNode> BODY = List.of(
            A,
            new If(FIRST, List.of(B)),
            new Choose(List.of(new WhenBranch(FIRST, List.of(C))), List.of(new Element("e", null, false, List.of(A)))));

    @Test
    void pathsAddressCardsAndLists() {
        assertThat(Bodies.get(BODY, Bodies.path("0"))).isEqualTo(A);
        assertThat(Bodies.get(BODY, Bodies.path("1.0.0"))).isEqualTo(B);
        assertThat(Bodies.get(BODY, Bodies.path("2.0.0"))).isEqualTo(C);
        assertThat(Bodies.get(BODY, Bodies.path("2.1.0.0.0"))).isEqualTo(A);
        assertThat(Bodies.list(BODY, Bodies.path("2.1"))).hasSize(1);
        assertThat(Bodies.list(BODY, Bodies.path("0.0"))).as("a leaf has no branches").isNull();
        assertThat(Bodies.get(BODY, Bodies.path("9"))).isNull();
        assertThat(Bodies.path(Bodies.branch(Bodies.path("2"), 1, 0))).isEqualTo("2.1.0");
    }

    @Test
    void rewritesReachBranchesAndKeepHeads() {
        List<OutputNode> next = Bodies.insert(BODY, Bodies.path("1.0"), 0, C);
        assertThat(((If) next.get(1)).then()).containsExactly(C, B);
        assertThat(((If) next.get(1)).test()).isEqualTo(FIRST);
        next = Bodies.replace(next, Bodies.path("2.1.0.0.0"), new ValueOf(ProjectJson.readRefOrName("x")));
        final Element e = (Element) ((Choose) next.get(2)).otherwise().get(0);
        assertThat(e.name()).isEqualTo("e");
        assertThat(e.body().get(0)).isInstanceOf(ValueOf.class);
        next = Bodies.remove(next, Bodies.path("0"));
        assertThat(next.get(0)).isInstanceOf(If.class);
        next = Bodies.move(next, Bodies.path("0"), 1);
        assertThat(next.get(1)).isInstanceOf(If.class);
        assertThat(Bodies.move(next, Bodies.path("1"), 1)).isSameAs(next);
        assertThat(BODY.get(1)).as("the original is untouched").isEqualTo(new If(FIRST, List.of(B)));
    }

    @Test
    void branchesAreOwnedByChooseAndSwitchOnly() {
        final Choose choose = (Choose) BODY.get(2);
        assertThat(Bodies.branchLabel(choose, 0)).isEqualTo("when is-first");
        assertThat(Bodies.branchLabel(choose, 1)).isEqualTo("otherwise");
        assertThat(Bodies.branchIsOwn(choose, 0)).isTrue();
        assertThat(Bodies.branchIsOwn(choose, 1)).isFalse();
        assertThat(Bodies.branchLabel((If) BODY.get(1), 0)).isEqualTo("then");
        final Choose more = Bodies.addWhen(choose, new Condition.IsLast());
        assertThat(more.when()).hasSize(2);
        assertThat(Bodies.removeWhen(more, 0).when().get(0).test()).isEqualTo(new Condition.IsLast());
        final Switch s = new Switch(ProjectJson.readRefOrName("k"), List.of(new SwitchCase("1", List.of(A))),
                List.of());
        assertThat(Bodies.branchLabel(s, 0)).isEqualTo("case 1");
        assertThat(Bodies.withCase(s, 0, "2").cases().get(0).body()).containsExactly(A);
        assertThat(Bodies.addCase(s, "3").cases()).hasSize(2);
    }

    @Test
    void draggingACardLandsItWhereTheIndicatorSaid() {
        // Down the same list: lifting the card shifts what follows, so an index past the old
        // position is one too many once it is out. Dropping "a" after the if means index 2,
        // and it must land second, not third.
        assertThat(Bodies.moveTo(BODY, Bodies.path("0"), Bodies.path(""), 2))
                .containsExactly(BODY.get(1), A, BODY.get(2));
        // Up the same list needs no adjustment.
        assertThat(Bodies.moveTo(BODY, Bodies.path("1"), Bodies.path(""), 0))
                .containsExactly(BODY.get(1), A, BODY.get(2));
        // Into another list: out of the top level and into the if's body, before b.
        final List<OutputNode> into = Bodies.moveTo(BODY, Bodies.path("0"), Bodies.path("1.0"), 0);
        assertThat(into).hasSize(2);
        assertThat(Bodies.list(into, Bodies.path("0.0"))).containsExactly(A, B);
    }

    @Test
    void cardsCannotBeDroppedInsideThemselves() {
        // The choose holds the element that would receive it. A holder inside what it contains
        // is not a body any more, so the paths refuse it before any list is touched.
        assertThat(Bodies.moveTo(BODY, Bodies.path("2"), Bodies.path("2.0"), 0)).isSameAs(BODY);
        assertThat(Bodies.moveTo(BODY, Bodies.path("2"), Bodies.path("2.1.0.0"), 0)).isSameAs(BODY);
        // Its neighbour is not inside it, so that one is allowed.
        assertThat(Bodies.moveTo(BODY, Bodies.path("0"), Bodies.path("2.0"), 0)).isNotSameAs(BODY);
    }

    @Test
    void captureGroupsAreSpeltAndReadBack() {
        // The case a migrated apply-templates always has, and the one that used to fall to raw
        // JSON: the wire has no short form for a numbered group, because group() is already a
        // counter, so the form has its own (design 44 §5u).
        assertThat(Instructions.spell(RefExpression.group(1))).isEqualTo("$1");
        assertThat(Instructions.spell(RefExpression.group(0))).isEqualTo("$0");
        assertThat(Instructions.read("$1")).isEqualTo(RefExpression.group(1));
        assertThat(Instructions.read(" $12 ")).isEqualTo(RefExpression.group(12));

        // A name is still a name, and one that merely starts with a dollar is not a group.
        assertThat(Instructions.spell(ProjectJson.readRefOrName("k"))).isEqualTo("k");
        assertThat(Instructions.read("k")).isEqualTo(ProjectJson.readRefOrName("k"));
        // $k is the group the pattern labelled k, not a variable called "$k" (§5y).
        assertThat(Instructions.read("$k")).isEqualTo(
                new RefExpression(List.of(RefPart.Capture.label("k"))));

        // What the form cannot spell, it says so about rather than guessing.
        assertThat(Instructions.spell(new RefExpression(List.of(
                new RefPart.Capture("v", 1, null))))).isNull();
    }

    @Test
    void severalPartsAreSpeltAsASequenceAndReadBack() {
        // What a value-of of several parts looks like: literals and references, juxtaposed
        // (design 44 §5w). 207 of the fixtures' unspellable value-ofs are exactly this shape.
        final RefExpression three = new RefExpression(List.of(
                new RefPart.Text("on "),
                new RefPart.Capture(null, 1, null),
                new RefPart.Text(" at")));
        assertThat(Instructions.spell(three)).isEqualTo("\"on \" $1 \" at\"");
        assertThat(Instructions.read("\"on \" $1 \" at\"")).isEqualTo(three);

        // A literal alone, which is 265 of them, and one that needs escaping.
        final RefExpression literal = new RefExpression(List.of(new RefPart.Text("plain")));
        assertThat(Instructions.spell(literal)).isEqualTo("\"plain\"");
        assertThat(Instructions.read("\"plain\"")).isEqualTo(literal);
        final RefExpression awkward = new RefExpression(List.of(
                new RefPart.Text("a \"b\"\nc\td")));
        assertThat(Instructions.read(Instructions.spell(awkward))).isEqualTo(awkward);

        // A name inside a sequence is a name; a name alone still spells bare.
        final RefExpression mixed = new RefExpression(List.of(
                new RefPart.Capture("when", 0, null),
                new RefPart.Text("!")));
        assertThat(Instructions.spell(mixed)).isEqualTo("when \"!\"");
        assertThat(Instructions.read("when \"!\"")).isEqualTo(mixed);

        // Unclosed quoting is not silently a name: the form keeps the wire while it is being typed.
        assertThat(Instructions.read("\"half")).isEqualTo(ProjectJson.readRefOrName("\"half"));

        // One part it cannot spell makes the whole unspellable, rather than showing a lie.
        assertThat(Instructions.spell(new RefExpression(List.of(
                new RefPart.Text("x"),
                new RefPart.Capture("v", 2, null))))).isNull();
    }

    @Test
    void spellingsTheFormCannotReadBackAreNotOffered() {
        // Two adjacent literals: the key and position reader used to test the first and last
        // character and call this one literal of `a" "b`.
        final RefExpression two = new RefExpression(List.of(
                new RefPart.Text("a"), new RefPart.Text("b")));
        assertThat(Instructions.read(Instructions.spell(two))).isEqualTo(two);

        // A name with a space in it would come back as two names, so it is not spelt at all.
        assertThat(Instructions.spell(new RefExpression(List.of(
                new RefPart.Capture("my name", 0, null))))).isNull();
        // Nor one that would read as a counter, or open a literal.
        assertThat(Instructions.spell(new RefExpression(List.of(
                new RefPart.Capture("index()", 0, null))))).isNull();
        assertThat(Instructions.spell(new RefExpression(List.of(
                new RefPart.Capture("a\"b", 0, null))))).isNull();

        // An ordinary name still spells bare. One that begins with a dollar would read as a
        // group of this match, so it is not spelt bare and the wire form takes it.
        assertThat(Instructions.spell(new RefExpression(List.of(
                new RefPart.Capture("when", 0, null))))).isEqualTo("when");
        assertThat(Instructions.spell(new RefExpression(List.of(
                new RefPart.Capture("$k", 0, null))))).isNull();
    }

    @Test
    void groupsThePatternNamedAreSpeltWithTheirName() {
        // The dollar says "a group of this match" either way: digits are its number, a word is
        // the label the pattern gave it (design 44 §5y).
        final RefExpression labelled = new RefExpression(List.of(RefPart.Capture.label("host")));
        assertThat(Instructions.spell(labelled)).isEqualTo("$host");
        assertThat(Instructions.read("$host")).isEqualTo(labelled);
        assertThat(Instructions.read(Instructions.spell(labelled))).isEqualTo(labelled);

        // Numbered and labelled sit side by side in a sequence.
        final RefExpression mixed = new RefExpression(List.of(
                RefPart.Capture.label("host"), new RefPart.Text(":"), new RefPart.Capture(null, 2, null)));
        assertThat(Instructions.spell(mixed)).isEqualTo("$host \":\" $2");
        assertThat(Instructions.read("$host \":\" $2")).isEqualTo(mixed);
    }

    @Test
    void accessorsAreSpeltAsCallsAndReadBack() {
        // A function of a collection, as a counter is a function of the match (design 44 §5z).
        final RefExpression size = new RefExpression(List.of(new RefPart.Accessor(
                RefPart.Accessor.Kind.SIZE, ProjectJson.readRefOrName("xs"), null, null, null)));
        assertThat(Instructions.spell(size)).isEqualTo("size(xs)");
        assertThat(Instructions.read("size(xs)")).isEqualTo(size);

        // get takes a key, and the arguments are spellings in their own right.
        final RefExpression get = new RefExpression(List.of(new RefPart.Accessor(
                RefPart.Accessor.Kind.GET, ProjectJson.readRefOrName("m"),
                RefExpression.text("k"), null, null)));
        assertThat(Instructions.spell(get)).isEqualTo("get(m, \"k\")");
        assertThat(Instructions.read("get(m, \"k\")")).isEqualTo(get);
        // A call is one token however its arguments are spaced.
        assertThat(Instructions.read("get(m,\"k\")")).isEqualTo(get);

        // Inside a sequence, which is where two thirds of the fixtures' accessors live.
        final RefExpression mixed = new RefExpression(List.of(
                new RefPart.Text("n="),
                new RefPart.Accessor(RefPart.Accessor.Kind.SIZE,
                        ProjectJson.readRefOrName("xs"), null, null, null)));
        assertThat(Instructions.spell(mixed)).isEqualTo("\"n=\" size(xs)");
        assertThat(Instructions.read("\"n=\" size(xs)")).isEqualTo(mixed);

        // One over another, which the arguments being spellings gives for nothing.
        final RefExpression nested = new RefExpression(List.of(new RefPart.Accessor(
                RefPart.Accessor.Kind.SIZE, get, null, null, null)));
        assertThat(Instructions.read(Instructions.spell(nested))).isEqualTo(nested);

        // A spelling the language refuses is not quietly made into something else.
        assertThat(Instructions.read("size(xs, 2)"))
                .isEqualTo(ProjectJson.readRefOrName("size(xs, 2)"));
    }

    @Test
    void theDefaultAndTheCastComeAfterTheCall() {
        // A call of two arguments has no room for them, so they bind to it from outside
        // (design 44 §5ab).
        final RefExpression cast = new RefExpression(List.of(new RefPart.Accessor(
                Kind.MAX, ProjectJson.readRefOrName("xs"), null, null, Cast.INTEGER)));
        assertThat(Instructions.spell(cast)).isEqualTo("max(xs) as integer");
        assertThat(Instructions.read("max(xs) as integer")).isEqualTo(cast);

        final RefExpression orElse = new RefExpression(List.of(new RefPart.Accessor(
                Kind.GET, ProjectJson.readRefOrName("m"), RefExpression.text("k"),
                RefExpression.text("-"), null)));
        assertThat(Instructions.spell(orElse)).isEqualTo("get(m, \"k\") or \"-\"");
        assertThat(Instructions.read("get(m, \"k\") or \"-\"")).isEqualTo(orElse);

        // The modifier takes the word after it, wherever the call sits in a sequence.
        assertThat(Instructions.read("\"n=\" max(xs) as number")).isEqualTo(new RefExpression(List.of(
                new RefPart.Text("n="),
                new RefPart.Accessor(Kind.MAX, ProjectJson.readRefOrName("xs"), null, null,
                        Cast.NUMBER))));

        // as is for min and max and or is for get. Anywhere else, and for a cast that is not one,
        // the words are ordinary parts — and since neither name can be spelt, the wire keeps them
        // rather than the form saving something the author did not write.
        for (final String text : new String[]{
                "size(xs) as number", "max(xs) or \"-\"", "max(xs) as wobble", "max(xs) as"}) {
            assertThat(Instructions.spell(Instructions.read(text)))
                    .describedAs("reading %s", text)
                    .isNull();
        }

        // A default of several parts would be read back as a default and then a sequence.
        assertThat(Instructions.spell(new RefExpression(List.of(new RefPart.Accessor(
                Kind.GET, ProjectJson.readRefOrName("m"), RefExpression.text("k"),
                new RefExpression(List.of(new RefPart.Text("a"), new RefPart.Text("b"))),
                null))))).isNull();
    }

    @Test
    void sortKeysAreSpeltAndReadBackAsRows() {
        // The last of the wire form's holdings, and the only one that was never a reference but
        // a list of its own: the fixture's key is bytes as it stood at i (design 44 §5ac).
        final Sort sort = new Sort(new RefExpression(List.of(new RefPart.Capture(
                "bytes", 0, new MatchIndex(0, false, false, "i", null)))),
                Order.DESCENDING, Cast.NUMBER);
        final SortKey row = SortKey.of(sort);
        assertThat(row.getBy()).isEqualTo("bytes[i]");
        assertThat(row.getOrder()).isEqualTo(Order.DESCENDING);
        assertThat(row.getAs()).isEqualTo(Cast.NUMBER);

        // What the row holds is what the dialog builds back.
        assertThat(new Sort(Instructions.read(row.getBy()), row.getOrder(), row.getAs()))
                .isEqualTo(sort);

        // An unsorted walk is a for-each with no rows, not a row with nothing in it.
        assertThat(new ForEach(ProjectJson.readRefOrName("xs"), "x", null, List.of(), List.of())
                .sort()).isEmpty();
    }

    @Test
    void everyShapeOfDefaultReadsBackToWhatItWas() {
        // Whatever the form spells as one word can be a default, so every shape is held to the
        // round trip rather than the literal alone. A default of matchCount() was spelt and then
        // not read back, which is the one thing the form must never do (design 44 §5u).
        final List<RefExpression> defaults = List.of(
                RefExpression.text("-"),
                ProjectJson.readRefOrName("fallback"),
                RefExpression.group(1),
                new RefExpression(List.of(RefPart.Capture.label("when"))),
                new RefExpression(List.of(new RefPart.Counter(EngineVars.MATCH_COUNT, null))),
                new RefExpression(List.of(new RefPart.Capture(
                        "bytes", 0, new MatchIndex(0, false, false, "i", null)))),
                new RefExpression(List.of(new RefPart.Accessor(
                        Kind.SIZE, ProjectJson.readRefOrName("xs"), null, null, null))));
        for (final RefExpression orElse : defaults) {
            final RefExpression ref = new RefExpression(List.of(new RefPart.Accessor(
                    Kind.GET, ProjectJson.readRefOrName("m"), RefExpression.text("k"),
                    orElse, null)));
            final String spelt = Instructions.spell(ref);
            assertThat(spelt).describedAs("spelling a default of %s", orElse).isNotNull();
            assertThat(Instructions.read(spelt)).describedAs("reading %s", spelt).isEqualTo(ref);
        }
    }

    @Test
    void onlyTheBareModifierWordIsReserved() {
        // A modifier is a bare word after a call, so only the bare name collides with one: $as is
        // a label and as[i] wears a subscript, and the form still spells and reads both.
        final RefExpression label = new RefExpression(List.of(RefPart.Capture.label("as")));
        assertThat(Instructions.spell(label)).isEqualTo("$as");
        assertThat(Instructions.read("$as")).isEqualTo(label);

        final RefExpression indexed = new RefExpression(List.of(new RefPart.Capture(
                "as", 0, new MatchIndex(0, false, false, "i", null))));
        assertThat(Instructions.spell(indexed)).isEqualTo("as[i]");
        assertThat(Instructions.read("as[i]")).isEqualTo(indexed);

        assertThat(Instructions.read("foo[as]")).isEqualTo(new RefExpression(List.of(
                new RefPart.Capture("foo", 0, new MatchIndex(0, false, false, "as", null)))));

        // The bare name itself, which a call wearing a modifier could not be told from.
        assertThat(Instructions.spell(ProjectJson.readRefOrName("as"))).isNull();
        assertThat(Instructions.spell(ProjectJson.readRefOrName("or"))).isNull();
    }

    @Test
    void theMatchIndexIsSpeltAsASubscriptAndReadBack() {
        // Which match to read, not just which group: bytes[i] and heading[matchCount()] are the
        // two the fixtures hold (design 44 §5aa).
        final RefExpression byVariable = new RefExpression(List.of(new RefPart.Capture(
                "bytes", 0, new MatchIndex(0, false, false, "i", null))));
        assertThat(Instructions.spell(byVariable)).isEqualTo("bytes[i]");
        assertThat(Instructions.read("bytes[i]")).isEqualTo(byVariable);

        final RefExpression byCounter = new RefExpression(List.of(new RefPart.Capture(
                "heading", 0, new MatchIndex(0, false, false, null, EngineVars.MATCH_COUNT))));
        assertThat(Instructions.spell(byCounter)).isEqualTo("heading[matchCount()]");
        assertThat(Instructions.read("heading[matchCount()]")).isEqualTo(byCounter);

        // The rest of the index rules, and a group wearing one rather than a name.
        assertThat(Instructions.read("v[3]")).isEqualTo(new RefExpression(List.of(
                new RefPart.Capture("v", 0, new MatchIndex(3, false, false, null, null)))));
        assertThat(Instructions.read("$1[+1]")).isEqualTo(new RefExpression(List.of(
                new RefPart.Capture(null, 1, new MatchIndex(1, true, false, null, null)))));
        assertThat(Instructions.read("$1[-1]")).isEqualTo(new RefExpression(List.of(
                new RefPart.Capture(null, 1, new MatchIndex(-1, true, false, null, null)))));
        assertThat(Instructions.read("v[last]")).isEqualTo(new RefExpression(List.of(
                new RefPart.Capture("v", 0, new MatchIndex(0, false, true, null, null)))));
        // The parens tell the keyword from the function of that name.
        assertThat(Instructions.read("v[last()]")).isEqualTo(new RefExpression(List.of(
                new RefPart.Capture("v", 0, new MatchIndex(0, false, false, null, EngineVars.LAST)))));

        // A variable named for the keyword has no subscript to be spelt in, so the wire keeps it.
        assertThat(Instructions.spell(new RefExpression(List.of(new RefPart.Capture(
                "v", 0, new MatchIndex(0, false, false, "last", null)))))).isNull();
    }

    @Test
    void theFormOffersOnlyWhatTheDocumentCanHold() {
        // The wire refuses a labelled capture that names anything else, so a subscript on one
        // could never be saved: the form does not offer it, and does not read it either
        // (design 44 §5ad).
        assertThat(Instructions.spell(new RefExpression(List.of(new RefPart.Capture(
                null, 0, new MatchIndex(0, false, false, "i", null), "when"))))).isNull();
        assertThat(Instructions.read("$when[i]"))
                .isEqualTo(ProjectJson.readRefOrName("$when[i]"));

        // $12 is group 12, so a label of digits has no spelling of its own to be read back by.
        assertThat(Instructions.spell(new RefExpression(List.of(RefPart.Capture.label("12")))))
                .isNull();
        assertThat(Instructions.read("$12")).isEqualTo(RefExpression.group(12));
    }

    @Test
    void anUnfinishedModifierIsSaidRatherThanSaved() {
        // A modifier that did not bind is a reserved word, never the name it would be read as,
        // so the dialog says what is wrong instead of saving a call and two undeclared reads.
        assertThat(Instructions.fault("max(xs) as"))
                .contains("'as' comes after min or max");
        assertThat(Instructions.fault("max(xs) as numbr"))
                .contains("'as' comes after min or max").contains("number");
        assertThat(Instructions.fault("get(m, \"k\") or"))
                .contains("'or' comes after get");

        // And nothing to say about a spelling that reads.
        for (final String good : new String[]{
                "max(xs) as number", "get(m, \"k\") or \"-\"", "$1 \" x\"", "bytes[i]"}) {
            assertThat(Instructions.fault(good)).describedAs("fault of %s", good).isNull();
        }
    }

    @Test
    void namesWearingTheGrammarsPunctuationAreNotSpelt() {
        // "a,b" inside a call would be read back as two arguments and "a[1]" as a subscript, so
        // neither is offered — the same rule as a name with a space (design 44 §5aa).
        for (final String name : new String[]{"a,b", "a[1]", "a(b", "a b"}) {
            assertThat(Instructions.spell(ProjectJson.readRefOrName(name)))
                    .describedAs("spelling the name %s", name)
                    .isNull();
        }
    }

    @Test
    void halfWrittenTextIsReadAsANameRatherThanRefused() {
        // Every one of these is something an author can leave in the box mid-edit. None may throw,
        // and none may be read as a part the author did not write (design 44 §5z audit).
        final String[] halfWritten = {
                "$99999999999999",  // more digits than a group number holds
                "size(xs",          // the call not yet closed
                "size()",           // the call with nothing in it
                "\"unclosed",       // the literal not yet closed
                ")x(",              // the parens the wrong way round
                "get(m,)",          // an argument not yet typed
                "bytes[]",          // the subscript with nothing in it
                "bytes[+]",         // a sign with no number after it
                "bytes[i",          // the subscript not yet closed
                "bytes[\"x\"]",      // a literal where an index rule goes
                "get(, \"k\")",       // the first argument not yet typed
                "get(m, )",         // the second not yet typed
                "size( )",          // the only one not yet typed
                "$",                // the dollar alone
                "",                 // nothing at all
        };
        for (final String text : halfWritten) {
            final RefExpression read = Instructions.read(text);
            assertThat(read)
                    .describedAs("reading %s", text)
                    .isEqualTo(ProjectJson.readRefOrName(text));
        }
    }

    @Test
    void deeplyNestedCallsAreReadOnceEach() {
        // A call's arguments are read by the same method that reads the call, so asking a token
        // what it is more than once costs that much again at every level: twenty deep was
        // billions of re-readings and a frozen tab. One reading per token keeps it linear.
        String spelling = "xs";
        for (int i = 0; i < 20; i++) {
            spelling = "get(" + spelling + ", \"k\")";
        }
        final RefExpression ref = Instructions.read(spelling);
        assertThat(Instructions.spell(ref)).isEqualTo(spelling);
    }

    @Test
    void whatTheFormSpellsItReadsBackTheSame() {
        // Spelling and reading are one grammar, so a spelling put back through both is unchanged.
        final String[] spellings = {
                "$0", "$1", "$name", "\"a\" \"b\"", "size(xs)", "get(m, \"k\")",
                "get(size(xs), $1)", "\"a\" get(m, \"k\") $2", "index()", "get(index(), \"k\")",
        };
        for (final String spelling : spellings) {
            assertThat(Instructions.spell(Instructions.read(spelling)))
                    .describedAs("spelling %s back", spelling)
                    .isEqualTo(spelling);
        }
    }

    @Test
    void kindsAndSummariesReadFromTheWireForm() {
        assertThat(Instructions.kind(A)).isEqualTo("text");
        assertThat(Instructions.kind(BODY.get(1))).isEqualTo("if");
        assertThat(Instructions.kind(BODY.get(2))).isEqualTo("choose");
        assertThat(Instructions.describe(A)).isEqualTo("\"a\"");
        assertThat(Instructions.describe(BODY.get(1))).isEqualTo("is-first");
        assertThat(Instructions.describe(BODY.get(2))).isEqualTo("1 branch and otherwise");
        assertThat(Instructions.category("text")).isEqualTo(Instructions.Category.OUTPUT);
        assertThat(Instructions.category("trim")).isEqualTo(Instructions.Category.TRANSFORM);
        for (final String kind : Instructions.TRANSFORM_KINDS) {
            assertThat(Instructions.category(kind)).isEqualTo(Instructions.Category.TRANSFORM);
        }
    }
}
