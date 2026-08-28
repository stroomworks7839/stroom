package stroom.shapeshifter.engine.text;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The transcoder's surrogate hold-back, pinned against a reader that splits pairs on
 * purpose — the phase-6 audit found the end-to-end pin could not reach it, because
 * {@code InputStreamReader} never splits a pair across reads. The {@code Reader} contract
 * makes no such promise, so the hold-back stays, and this is what can kill its mutant.
 */
class TranscodeTest {

    /** Returns one char per read: every surrogate pair is split across two calls. */
    private static final class OneCharReader extends Reader {

        private final String text;
        private int at;

        OneCharReader(final String text) {
            this.text = text;
        }

        @Override
        public int read(final char[] into, final int off, final int len) {
            if (at >= text.length()) {
                return -1;
            }
            into[off] = text.charAt(at++);
            return 1;
        }

        @Override
        public void close() {
        }
    }

    @Test
    void pairSplitAcrossReadsComesOutWhole() throws IOException {
        final String text = "a𐍈b";
        final InputStream in = Transcode.wrap(new OneCharReader(text),
                StandardCharsets.UTF_16LE);
        assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(text);
    }

    @Test
    void streamEndingOnALoneHighSurrogateReplaces() throws IOException {
        final InputStream in = Transcode.wrap(new OneCharReader("a\uD800"),
                StandardCharsets.UTF_16LE);
        assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("a�");
    }
}
