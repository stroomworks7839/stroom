package stroom.shapeshifter.regex;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The line idiom {@code ((?:[^\n]*\n)*?)lit} compiles to the tree's iterative {@code RunLoop}
 * (design 07 Phase 2) instead of the recursive {@code Loop}. These pin that the two agree with
 * each other and with the JDK — spans and captures — on the shapes that distinguish a lazy unit
 * loop from anything else, and that a 64 KiB region no longer trips the depth guard.
 */
class RunLoopTest {

    private static final String IDIOM = "  <batch id=\"([^\"]*)\">\\n((?:[^\\n]*\\n)*?)  </batch>\\n";

    private static byte[] batch(final int lines) {
        final StringBuilder sb = new StringBuilder("  <batch id=\"b-1\">\n");
        for (int i = 0; i < lines; i++) {
            sb.append("    <entry seq=\"").append(i).append("\">payload ").append(i).append("</entry>\n");
        }
        return sb.append("  </batch>\n").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void agreeWithJdk(final String pattern, final byte[] input) {
        final String text = new String(input, StandardCharsets.UTF_8);
        final Matcher jdk = Pattern.compile(pattern).matcher(text);
        for (final Engine engine : new Engine[]{Engine.TREE, Engine.SIMULATE}) {
            final ByteMatcher ours = BytePattern
                    .compileForcing(engine, pattern, EnumSet.noneOf(Flag.class)).matcher();
            final boolean found = ours.find(input);
            assertThat(found)
                    .as("%s finds like the JDK: %s", engine, pattern)
                    .isEqualTo(jdk.find());
            if (found) {
                for (int g = 0; g <= jdk.groupCount(); g++) {
                    // The JDK reports char offsets over the String; ours are byte offsets.
                    assertThat(ours.start(g)).as("%s group %d start", engine, g)
                            .isEqualTo(byteOffset(text, jdk.start(g)));
                    assertThat(ours.end(g)).as("%s group %d end", engine, g)
                            .isEqualTo(byteOffset(text, jdk.end(g)));
                }
            }
            jdk.reset();
        }
    }

    private static int byteOffset(final String text, final int charOffset) {
        return charOffset < 0 ? -1 : text.substring(0, charOffset).getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    void theIdiomAgreesWithTheJdkAtEntrySize() {
        agreeWithJdk(IDIOM, batch(2));
    }

    @Test
    void sixtyFourKilobytesNoLongerTripsTheDepthGuard() {
        final byte[] input = batch(1_700); // ~1,700 lines: well past LOOP_DEPTH_LIMIT's 1,024
        assertThat(input.length).isGreaterThan(64 * 1024);
        final BytePattern tree = BytePattern.compileForcing(Engine.TREE, IDIOM, EnumSet.noneOf(Flag.class));
        final ByteMatcher m = tree.matcher();
        assertThat(m.find(input)).as("the tree finishes the region itself").isTrue();
        assertThat(m.group(2).length())
                .isEqualTo(input.length - "  <batch id=\"b-1\">\n".length() - "  </batch>\n".length());
        // The oracle here is the simulation, not the JDK: java.util.regex recurses once per
        // iteration of a lazy loop too, and overflows a default stack on this input
        // (Pattern$LazyLoop, 1,024 frames deep, 2026-09-03). The tree used to fail the same way
        // one level up, by its own depth guard; now neither engine of ours does.
        final ByteMatcher sim = BytePattern
                .compileForcing(Engine.SIMULATE, IDIOM, EnumSet.noneOf(Flag.class)).matcher();
        assertThat(sim.find(input)).isTrue();
        for (int g = 0; g <= 2; g++) {
            assertThat(m.start(g)).as("group %d start", g).isEqualTo(sim.start(g));
            assertThat(m.end(g)).as("group %d end", g).isEqualTo(sim.end(g));
        }
    }

    @Test
    void lazinessIsKept() {
        // Two terminators are present; the lazy loop must stop at the first the continuation accepts.
        agreeWithJdk("((?:[^\\n]*\\n)*?)END\\n", "a\nb\nEND\nc\nEND\n".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void missingTerminatorFailsTheUnitNotTheMatch() {
        // The last line has no newline: the loop cannot consume it as a unit, so the continuation
        // must match before it or the whole thing fails — exactly as the recursive loop behaves.
        agreeWithJdk("((?:[^\\n]*\\n)*?)tail", "one\ntwo\ntail".getBytes(StandardCharsets.UTF_8));
        agreeWithJdk("((?:[^\\n]*\\n)*?)tail", "one\ntwo\nthree".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void unitWhoseClassAcceptsTheTerminatorIsNotThisShape() {
        // (?s).*\n: the run would swallow the newline, so the boundary is ambiguous and the general
        // loop must handle it — an iterative unit scan would eat every line and never see the
        // terminator. Under default flags . excludes \n and the guard is not even asked, which is
        // why the first draft of this test let the guard's mutant live.
        agreeWithJdk("(?s)((?:.*\\n)*?)END", "a\nb\nEND".getBytes(StandardCharsets.UTF_8));
        agreeWithJdk("(?s)((?:.*\\n)*?)END", "a\nb\nEND\nc\n".getBytes(StandardCharsets.UTF_8));
        agreeWithJdk("((?:.*\\n)*?)END", "a\nb\nEND".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void nonAsciiInsideTheRunIsWalkedNotStepped() {
        agreeWithJdk("((?:[^\\n]*\\n)*?)fin\\n", "café\nnaïve — ok\nfin\n".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void emptyLinesAreUnits() {
        agreeWithJdk("((?:[^\\n]*\\n)*?)x", "\n\n\nx".getBytes(StandardCharsets.UTF_8));
    }

    // ---- audit, 2026-09-03: the questions an adversarial read of RunLoop asks ----

    private static byte[] linesWithoutEnd(final int lines) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            sb.append("line ").append(i).append(" of a region that never says the word\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void neverMatchingContinuationStillCompletesOnTheAutoPath() {
        // Before RunLoop the general Loop hit the depth guard, bailed, and the simulation finished
        // in linear time. RunLoop never trips that guard, so from every candidate start it walks
        // to the region's end: O(n^2) steps until the step budget says stop. The budget is the
        // contract here; what must not change is that the auto path still completes and answers.
        final byte[] input = linesWithoutEnd(6_000); // ~256 KiB
        final ByteMatcher auto = BytePattern.compile("((?:[^\\n]*\\n)*?)END").matcher();
        assertThat(auto.find(input)).as("the auto path answers, via the simulation if it must").isFalse();
        final ByteMatcher sim = BytePattern
                .compileForcing(Engine.SIMULATE, "((?:[^\\n]*\\n)*?)END", EnumSet.noneOf(Flag.class)).matcher();
        assertThat(sim.find(input)).isFalse();
    }

    @Test
    void pinnedTreeOnTheNeverMatchingRegionIsRefusedNotHung() {
        final byte[] input = linesWithoutEnd(6_000);
        final ByteMatcher tree = BytePattern
                .compileForcing(Engine.TREE, "((?:[^\\n]*\\n)*?)END", EnumSet.noneOf(Flag.class)).matcher();
        // Pinned, the tree used to throw when the depth guard tripped; now it throws when the
        // step budget does. Either way it is a refusal with a reason, in bounded time.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tree.find(input))
                .isInstanceOf(MatchLimitException.class);
    }

    @Test
    void captureInsideTheUnitIsNotThisShape() {
        agreeWithJdk("((?:([^\\n]*)\\n)*?)END", "a\nbb\nEND".getBytes(StandardCharsets.UTF_8));
        agreeWithJdk("((?:([^\\n]*)\\n)*?)END", "a\nbb\nccc".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void unitWithAMinimumIsNotThisShape() {
        // [^\n]+\n: an empty line is not a unit, so "\n\nEND" must not match from 0 the way the
        // star form would. Pins the min() != 0 guard in unitOf.
        agreeWithJdk("((?:[^\\n]+\\n)*?)END", "\n\nEND".getBytes(StandardCharsets.UTF_8));
        agreeWithJdk("((?:[^\\n]+\\n)*?)END", "a\nb\nEND".getBytes(StandardCharsets.UTF_8));
        // {2,} keeps its minimum as a Repeat where + may be rewritten as X X*; both must decline.
        agreeWithJdk("((?:[^\\n]{2,}\\n)*?)END", "a\nbb\nEND".getBytes(StandardCharsets.UTF_8));
        agreeWithJdk("((?:[^\\n]{2,}\\n)*?)END", "\n\nEND".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void nestedNonCapturingGroupsAreLookedThrough() {
        agreeWithJdk("((?:(?:[^\\n]*\\n))*?)END", "a\nb\nEND".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void precedingLazyRunLosesOnlyItsSkipNotItsAnswer() {
        // RunLoop answers leadingByte() with -1, so a lazy .*? before it cannot filter its offers.
        // That is a missed optimisation, never a missed match.
        agreeWithJdk("x(.*?)((?:[^\\n]*\\n)*?)END", "xab\ncd\nEND".getBytes(StandardCharsets.UTF_8));
        agreeWithJdk("x(.*?)((?:[^\\n]*\\n)*?)END", "xab\ncd\nEN".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theContinuationIsOfferedBeforeTheFirstUnit() {
        // Zero iterations must be a legal answer: END at the very start.
        agreeWithJdk("((?:[^\\n]*\\n)*?)END", "END\nmore\n".getBytes(StandardCharsets.UTF_8));
    }
}
