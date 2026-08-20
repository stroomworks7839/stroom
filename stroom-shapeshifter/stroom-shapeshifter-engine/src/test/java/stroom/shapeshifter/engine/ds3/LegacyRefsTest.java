/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.shapeshifter.engine.ds3;

import stroom.shapeshifter.engine.config.RefExpression;
import stroom.shapeshifter.engine.config.RefExpression.MatchIndex;
import stroom.shapeshifter.engine.config.RefExpression.RefPart;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DS3's reference syntax, ported from the Rust crate's own suite.
 *
 * <p>The fixtures reach this parser, but only through whatever the eighteen DS3 configurations
 * happen to write — which is a fraction of what the syntax allows. The {@code @}-form in
 * particular appears in no fixture at all.
 *
 * <p>It is also the only text parser left in the engine, and the only one that should be: modern
 * configurations build their expressions structurally.
 */
class LegacyRefsTest {

    private static List<RefPart> parts(final String reference) {
        return LegacyRefs.parse(reference).parts();
    }

    private static RefPart.Capture capture(final String reference) {
        return (RefPart.Capture) parts(reference).getFirst();
    }

    // -----------------------------------------------------------------------------------
    // The $ form
    // -----------------------------------------------------------------------------------

    @Test
    void readsGroupReferences() {
        assertThat(capture("$1")).isEqualTo(new RefPart.Capture(null, 1, null));
        assertThat(capture("$0")).isEqualTo(new RefPart.Capture(null, 0, null));
        // A bare dollar is the whole match.
        assertThat(capture("$")).isEqualTo(new RefPart.Capture(null, 0, null));
    }

    @Test
    void readsVariableReferences() {
        assertThat(capture("$heading$1")).isEqualTo(new RefPart.Capture("heading", 1, null));
        assertThat(capture("$heading$0")).isEqualTo(new RefPart.Capture("heading", 0, null));
    }

    @Test
    void readsMatchIndices() {
        assertThat(capture("$heading$1[+1]"))
                .isEqualTo(new RefPart.Capture("heading", 1, new MatchIndex(1, true, false, null)));
        assertThat(capture("$heading$1[3]"))
                .isEqualTo(new RefPart.Capture("heading", 1, new MatchIndex(3, false, false, null)));
        assertThat(capture("$1[-2]"))
                .isEqualTo(new RefPart.Capture(null, 1, new MatchIndex(-2, true, false, null)));
    }

    // -----------------------------------------------------------------------------------
    // Literals
    // -----------------------------------------------------------------------------------

    @Test
    void anythingNotStartingWithASigilIsALiteral() {
        assertThat(parts("hello")).containsExactly(new RefPart.Text("hello"));
        // Which is why <data name="user"> needs no quoting.
        assertThat(parts("user name")).containsExactly(new RefPart.Text("user name"));
    }

    @Test
    void readsQuotedLiterals() {
        assertThat(parts("'hello'")).containsExactly(new RefPart.Text("hello"));
        // A doubled quote is one quote — the only escape the syntax has.
        assertThat(parts("'it''s'")).containsExactly(new RefPart.Text("it's"));
    }

    @Test
    void readsNothingFromNothing() {
        assertThat(parts("")).isEmpty();
        assertThat(parts(null)).isEmpty();
    }

    // -----------------------------------------------------------------------------------
    // Composition
    // -----------------------------------------------------------------------------------

    @Test
    void joinsPartsWithPlus() {
        assertThat(parts("$1+'/'+$2")).containsExactly(
                new RefPart.Capture(null, 1, null),
                new RefPart.Text("/"),
                new RefPart.Capture(null, 2, null));
    }

    @Test
    void plusInsideQuotesOrBracketsIsNotASeparator() {
        // Otherwise "$1[+1]" would split in the middle of its own match index, and a literal
        // plus could never be written.
        assertThat(parts("$1[+1]")).hasSize(1);
        assertThat(parts("'a+b'")).containsExactly(new RefPart.Text("a+b"));
    }

    // -----------------------------------------------------------------------------------
    // The @ form
    // -----------------------------------------------------------------------------------

    @Test
    void readsTheAtFormOfVariableReferences() {
        assertThat(capture("@foo")).isEqualTo(new RefPart.Capture("foo", 0, null));
        assertThat(capture("@foo.2")).isEqualTo(new RefPart.Capture("foo", 2, null));
        assertThat(capture("@foo.0")).isEqualTo(new RefPart.Capture("foo", 0, null));
        assertThat(capture("@foo.2[+1]"))
                .isEqualTo(new RefPart.Capture("foo", 2, new MatchIndex(1, true, false, null)));
        assertThat(capture("@foo.2[5]"))
                .isEqualTo(new RefPart.Capture("foo", 2, new MatchIndex(5, false, false, null)));
        assertThat(capture("@foo.1[-1]"))
                .isEqualTo(new RefPart.Capture("foo", 1, new MatchIndex(-1, true, false, null)));
        assertThat(capture("@my_var")).isEqualTo(new RefPart.Capture("my_var", 0, null));
    }

    @Test
    void theTwoFormsMeanTheSameThing() {
        // Two spellings, one meaning — which is the only reason to keep both.
        assertThat(capture("@foo.2")).isEqualTo(capture("$foo$2"));
        assertThat(capture("@foo")).isEqualTo(capture("$foo$0"));
        assertThat(parts("@a.1+'/'+$b$2")).containsExactly(
                new RefPart.Capture("a", 1, null),
                new RefPart.Text("/"),
                new RefPart.Capture("b", 2, null));
    }

    // -----------------------------------------------------------------------------------
    // The structural constructors agree with the parser
    // -----------------------------------------------------------------------------------

    @Test
    void buildingAnExpressionGivesWhatParsingOneWould() {
        // Modern configurations build expressions rather than writing them, and the two paths
        // have to agree or an imported configuration would behave differently from an authored
        // one that says the same thing.
        assertThat(RefExpression.group(1).parts()).isEqualTo(parts("$1"));
        assertThat(RefExpression.group(0).parts()).isEqualTo(parts("$0"));
        assertThat(RefExpression.text("hello").parts()).isEqualTo(parts("hello"));
    }

    @Test
    void knowsWhenAnExpressionIsJustText() {
        assertThat(RefExpression.text("x").isText()).isTrue();
        assertThat(RefExpression.group(1).isText()).isFalse();
        assertThat(LegacyRefs.parse("$1+'x'").isText()).isFalse();
    }
}
