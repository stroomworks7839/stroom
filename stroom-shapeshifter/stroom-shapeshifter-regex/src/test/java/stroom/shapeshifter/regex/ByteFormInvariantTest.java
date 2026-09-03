package stroom.shapeshifter.regex;

import stroom.shapeshifter.regex.internal.ByteForm;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the equivalence {@code ByteMatcher.splitsCharacter} rests on.
 * <p>That gate runs once per candidate start position, so it is spelled as a static call guarded
 * by the form's own {@code singleByte()} fact rather than as {@code form.splitsCharacter(...)} —
 * the interface call does not inline there, and the polymorphic spelling cost 5.5× on
 * {@code AnchoredSearchBenchmark} line_miss (design 06 §1, measured 2026-09-03). The two are the
 * same answer only because of what {@link ByteForm} permits: every single-byte form answers
 * {@code false} unconditionally, and the sole multi-byte form is UTF-8.
 * <p>Both halves are pinned here because a fourth form would break the gate silently — it would
 * still compile, still pass every encoding test, and simply stop asking about character interiors
 * on inputs that have them. If this test fails, {@code ByteMatcher.splitsCharacter} needs revisiting
 * before the new form ships.
 */
class ByteFormInvariantTest {

    /** windows-1252's own mapping, so the table under test is a real one rather than a fixture. */
    private static Encoding.Table windows1252() {
        final int[] byteToCodePoint = new int[256];
        for (int b = 0; b < 256; b++) {
            final String decoded = new String(new byte[]{(byte) b}, java.nio.charset.Charset.forName("windows-1252"));
            byteToCodePoint[b] = decoded.length() == 1 && decoded.charAt(0) != '�'
                    ? decoded.charAt(0)
                    : -1;
        }
        byteToCodePoint[0x81] = -1;
        return new Encoding.Table("windows-1252", byteToCodePoint);
    }

    @Test
    void everySingleByteFormHasNoCharacterInteriorToSplit() {
        final byte[] anyBytes = "café".getBytes(StandardCharsets.UTF_8);
        for (final ByteForm form : new ByteForm[]{ByteForm.RAW, ByteForm.of(windows1252())}) {
            assertThat(form.singleByte()).as("%s is single byte", form).isTrue();
            for (int at = 0; at <= anyBytes.length; at++) {
                assertThat(form.splitsCharacter(anyBytes, at, anyBytes.length))
                        .as("%s splits at %d", form, at)
                        .isFalse();
            }
        }
    }

    @Test
    void theOnlyMultiByteFormIsUtf8() {
        assertThat(ByteForm.UTF8.singleByte()).isFalse();
        assertThat(ByteForm.of(new Encoding.Utf8())).isSameAs(ByteForm.UTF8);
    }

    @Test
    void addingAFormWouldBreakTheGate() {
        // ByteForm permits exactly Utf8Form, TableForm and RawForm. The gate's static spelling is
        // equivalent to the polymorphic one only while that holds, so adding a form is a decision
        // that has to visit ByteMatcher rather than a change this suite can absorb quietly.
        assertThat(ByteForm.class.getPermittedSubclasses())
                .as("a new ByteForm must revisit ByteMatcher.splitsCharacter")
                .hasSize(3);
    }
}
