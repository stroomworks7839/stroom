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

package stroom.shapeshifter.engine.exec;

import stroom.shapeshifter.engine.Message;
import stroom.shapeshifter.engine.OutputSink;
import stroom.shapeshifter.engine.Severity;
import stroom.shapeshifter.engine.compile.CompiledMatch;
import stroom.shapeshifter.engine.compile.CompiledProject;
import stroom.shapeshifter.engine.compile.CompiledTemplate;
import stroom.shapeshifter.engine.config.CaptureBinding;
import stroom.shapeshifter.engine.config.MatchExpression;
import stroom.shapeshifter.engine.config.OutputNode;
import stroom.shapeshifter.engine.config.OutputNode.ApplyDirective;
import stroom.shapeshifter.engine.config.Template;
import stroom.shapeshifter.regex.Anchoring;
import stroom.shapeshifter.regex.ByteMatcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The runtime: reads the input, drives the templates, writes the output.
 *
 * <p>Input arrives in buffers, and <b>a match never spans two of them</b>. That is a real
 * limitation rather than an oversight — it is the Rust engine's behaviour, ported deliberately so
 * that golden parity means something (D33) — and it is why a configuration's buffer size is also
 * the largest record it can handle. The matching layer underneath can do better, and lifting this
 * is the first thing to decide once the port is green.
 *
 * <p>Within a buffer the shape is simple and recursive. The document template writes its prologue,
 * hands the buffer to the templates of its mode, and writes its epilogue at the end of the stream.
 * Each template matches repeatedly from where the last match ended, and each match runs a body
 * that can hand a captured group down to another set of templates. The cursor is shared between
 * sibling templates, so one consuming a header leaves the rest to the next.
 */
public final class Executor {

    /** The engine's own variable: how many times the current template has matched, 1-based. */
    private static final String MATCH_COUNT = "__match_count";

    /** The engine's own variable: the same count, 0-based, for the XSLT-shaped reading. */
    private static final String MATCH_INDEX = "__match_idx";

    private final CompiledProject compiled;
    private final OutputSink sink;
    private final List<Message> messages = new ArrayList<>();
    private final VarRegistry vars = new VarRegistry();

    private Executor(final CompiledProject compiled, final OutputSink sink) {
        this.compiled = compiled;
        this.sink = sink;
        this.messages.addAll(compiled.warnings());
    }

    /**
     * Run a compiled configuration over an input.
     *
     * @param wholeBuffer read the input as a single buffer rather than in chunks. The progressive
     *                    matches need it — an absolute seek is meaningless over a window — and it
     *                    also suppresses the warning about a template consuming a whole buffer,
     *                    which cannot indicate truncation when there is only one
     * @return everything the engine had to say, in the order it said it
     */
    public static List<Message> run(final CompiledProject compiled,
                                    final InputStream input,
                                    final OutputSink sink,
                                    final boolean wholeBuffer) {
        return new Executor(compiled, sink).execute(input, wholeBuffer);
    }

    // -----------------------------------------------------------------------------------
    // The stream
    // -----------------------------------------------------------------------------------

    private List<Message> execute(final InputStream input, final boolean wholeBuffer) {
        final CompiledTemplate source = compiled.templates().stream()
                .filter(t -> t.match() instanceof CompiledMatch.Source)
                .findFirst()
                .orElse(null);

        // The document template's body is split at its apply-templates: what comes before is
        // written once at the start, what comes after once at the end, and the apply-templates
        // itself is the loop over the input. Everything the loop dispatches to is the templates
        // of the mode it names.
        final String streamMode = source == null ? null : applyMode(source.template());
        final List<CompiledTemplate> roots = compiled.templates().stream()
                .filter(t -> !(t.match() instanceof CompiledMatch.Source))
                .filter(t -> java.util.Objects.equals(t.template().mode(), streamMode))
                .toList();

        final MatchResult nothing = MatchResult.empty();
        List<OutputNode> prologue = List.of();
        List<OutputNode> epilogue = List.of();
        if (source != null) {
            final List<OutputNode> body = source.template().body();
            final int apply = indexOfApply(body);
            prologue = apply < 0 ? body : body.subList(0, apply);
            epilogue = apply < 0 ? List.of() : body.subList(apply + 1, body.size());
        }

        for (final Template template : compiled.project().templates()) {
            for (final CaptureBinding capture : template.captures()) {
                vars.register(capture.name());
            }
        }

        if (!prologue.isEmpty()) {
            body(prologue, nothing, 0, vars, 0);
        }

        final int bufferSize = wholeBuffer
                ? Integer.MAX_VALUE
                : Math.max(1, compiled.project().source().bufferSize());
        boolean first = true;
        for (byte[] chunk = read(input, bufferSize); chunk != null; chunk = read(input, bufferSize)) {
            int from = 0;
            if (first) {
                first = false;
                from = byteOrderMarkLength(chunk);
            }
            if (from >= chunk.length) {
                continue;
            }

            int cursor = from;
            for (final CompiledTemplate root : roots) {
                if (cursor >= chunk.length) {
                    break;
                }
                final int consumed = template(root, chunk, cursor, chunk.length, 0);
                if (!wholeBuffer && consumed > 0 && cursor + consumed == chunk.length
                    && chunk.length - from == bufferSize) {
                    messages.add(new Message(Severity.WARNING, "Template '" + root.template().name()
                                                               + "' consumed entire buffer (" + consumed
                                                               + " bytes). If data is truncated, increase source "
                                                               + "buffer_size (currently " + bufferSize + ")."));
                }
                cursor += consumed;
            }
        }

        if (!epilogue.isEmpty()) {
            body(epilogue, nothing, 0, vars, 0);
        }
        return List.copyOf(messages);
    }

    private static String applyMode(final Template template) {
        for (final OutputNode node : template.body()) {
            if (node instanceof OutputNode.ApplyTemplates apply) {
                return apply.directive().mode();
            }
        }
        return null;
    }

    private static int indexOfApply(final List<OutputNode> body) {
        for (int i = 0; i < body.size(); i++) {
            if (body.get(i) instanceof OutputNode.ApplyTemplates) {
                return i;
            }
        }
        return -1;
    }

    // -----------------------------------------------------------------------------------
    // One template against one region
    // -----------------------------------------------------------------------------------

    /**
     * Match a template repeatedly over a region, running its body for each match.
     *
     * @return how many bytes it consumed, which is where its sibling starts
     */
    private int template(final CompiledTemplate compiledTemplate,
                         final byte[] data,
                         final int from,
                         final int to,
                         final int depth) {
        final Template template = compiledTemplate.template();
        final int minMatch = template.matchLimits().minMatch();
        final int maxMatch = template.matchLimits().maxMatch();

        int matchCount = 0;
        int offset = from;

        while (offset < to) {
            if (maxMatch >= 0 && matchCount >= maxMatch) {
                break;
            }
            final MatchResult match = match(compiledTemplate, data, offset, to);
            if (match == null) {
                break;
            }
            matchCount++;

            // The engine's own variables, readable by any reference: how many times this
            // template has matched. A child template's reference to a parent's multi-valued
            // capture uses this to pick the right one, which is how a header column lines up
            // with the data column beneath it.
            vars.store(MATCH_INDEX).set(1, new TypedValue.Int(matchCount - 1));
            vars.store(MATCH_COUNT).set(1, new TypedValue.Int(matchCount));

            final boolean wanted = template.matchLimits().onlyMatch() == null
                                   || template.matchLimits().onlyMatch().contains(matchCount);
            if (wanted) {
                // A delimiter match's content is the field, not the field plus its delimiter;
                // every other kind of match means the whole of what it matched.
                final int contentGroup = template.match() instanceof MatchExpression.Delimiter ? 1 : 0;
                final TypedValue content = match.group(contentGroup) != null
                        ? match.group(contentGroup)
                        : match.group(0);

                if (content == null || content.isEmpty()) {
                    if (match.advance() <= 0) {
                        break;
                    }
                    offset += match.advance();
                    continue;
                }
                bindCaptures(compiledTemplate, match, matchCount);
                body(template.body(), match, matchCount, vars, depth);
            }

            if (match.advance() <= 0) {
                break;
            }
            offset += match.advance();
        }

        if (minMatch > 0 && matchCount < minMatch) {
            messages.add(new Message(Severity.ERROR,
                    "Expected at least " + minMatch + " matches but got " + matchCount));
        }
        reportUnconsumed(template, data, offset, to);
        return offset - from;
    }

    /**
     * Say so when a template leaves content behind.
     *
     * <p>Blank remainders are not worth mentioning; anything else usually means the configuration
     * and the data have diverged, which is exactly the thing a person wants told about.
     */
    private void reportUnconsumed(final Template template, final byte[] data, final int offset, final int to) {
        if (template.ignoreErrors() || offset >= to) {
            return;
        }
        boolean anyContent = false;
        for (int i = offset; i < to; i++) {
            final byte b = data[i];
            if (b != ' ' && b != '\t' && b != '\n' && b != '\r' && b != '\f' && b != 0x0B) {
                anyContent = true;
                break;
            }
        }
        if (!anyContent) {
            return;
        }
        final int previewLength = Math.min(200, to - offset);
        final String preview = new String(data, offset, previewLength, StandardCharsets.UTF_8)
                .replace("\n", "\\n");
        messages.add(new Message(Severity.WARNING,
                "Template '" + template.name() + "' did not consume all content. Unmatched: ["
                + preview + (to - offset > 200 ? "...TRUNCATED..." : "") + "]"));
    }

    private MatchResult match(final CompiledTemplate compiledTemplate,
                              final byte[] data,
                              final int from,
                              final int to) {
        if (from >= to) {
            return null;
        }
        return switch (compiledTemplate.match()) {
            case CompiledMatch.Delimiter delimiter -> Splitter.split(data, from, to,
                    delimiter.delimiter(), delimiter.escape(),
                    delimiter.containerStart(), delimiter.containerEnd());
            case CompiledMatch.Regex regex -> regexMatch(regex, data, from, to);
            case CompiledMatch.All ignored -> new MatchResult(
                    new TypedValue[]{TypedValue.of(Arrays.copyOfRange(data, from, to))}, to - from, 0);
            case CompiledMatch.Source ignored -> null;
            case CompiledMatch.Named ignored -> null;
        };
    }

    private static MatchResult regexMatch(final CompiledMatch.Regex regex,
                                          final byte[] data,
                                          final int from,
                                          final int to) {
        final ByteMatcher matcher = regex.pattern().matcher();
        if (!matcher.match(data, from, to, Anchoring.UNANCHORED)) {
            return null;
        }
        final int groupCount = regex.pattern().groupCount() + 1;
        final TypedValue[] groups = new TypedValue[groupCount];
        for (int i = 0; i < groupCount; i++) {
            if (matcher.matchedGroup(i)) {
                groups[i] = TypedValue.of(matcher.groupBytes(i));
            }
        }

        // The cursor normally lands at the end of the match. A template can ask for the end of a
        // group instead, which is how a pattern looks further ahead than it consumes.
        int end = matcher.end();
        if (regex.advance() > 0 && regex.advance() < groupCount && matcher.matchedGroup(regex.advance())) {
            end = matcher.end(regex.advance());
        }
        return new MatchResult(groups, end - from, matcher.start() - from);
    }

    // -----------------------------------------------------------------------------------
    // Captures and body
    // -----------------------------------------------------------------------------------

    private void bindCaptures(final CompiledTemplate compiledTemplate,
                              final MatchResult match,
                              final int matchCount) {
        for (final CaptureBinding capture : compiledTemplate.template().captures()) {
            final TypedValue value = switch (capture.select()) {
                case CaptureBinding.CaptureSource.Group group -> match.group(group.group());
                case CaptureBinding.CaptureSource.Step step -> match.group(step.index() + 1);
                case CaptureBinding.CaptureSource.Select select -> {
                    final byte[] bytes = Refs.resolve(select.select(), match, matchCount, vars);
                    yield bytes == null ? null : TypedValue.of(bytes);
                }
                case CaptureBinding.CaptureSource.Field ignored -> null;
                case CaptureBinding.CaptureSource.KeyValue keyValue -> {
                    final String key = Refs.resolveText(keyValue.keyRef(), match, matchCount, vars);
                    if (key != null) {
                        final byte[] bytes = Refs.resolve(keyValue.valueRef(), match, matchCount, vars);
                        if (bytes != null) {
                            vars.store(key).set(matchCount, TypedValue.of(bytes));
                        }
                    }
                    yield null;
                }
            };
            if (capture.select() instanceof CaptureBinding.CaptureSource.KeyValue) {
                continue;
            }
            final Store store = vars.store(capture.name());
            if (value == null) {
                // An unmatched capture must read as empty, not as whatever the previous record
                // left there.
                store.remove(matchCount);
            } else {
                store.set(matchCount, value);
            }
        }
    }

    private void body(final List<OutputNode> nodes,
                      final MatchResult match,
                      final int matchCount,
                      final VarRegistry scope,
                      final int depth) {
        for (final OutputNode node : nodes) {
            switch (node) {
                case OutputNode.Text text -> sink.write(text.value());
                case OutputNode.ValueOf valueOf -> Refs.write(valueOf.select(), match, matchCount, scope, sink);
                case OutputNode.ApplyTemplates apply ->
                        apply(apply.directive(), match, matchCount, depth);
                default -> throw new UnsupportedOperationException(
                        node.getClass().getSimpleName() + " is not ported yet");
            }
        }
    }

    /**
     * Hand some content to another set of templates.
     *
     * <p>Which content is a reference, and it is usually a group of the match just made — that is
     * how a row is broken into fields, and a field into parts. The templates that get it are
     * those of the named mode, tried in order against a shared cursor, so one template consuming
     * a header leaves the remainder to the next.
     */
    private void apply(final ApplyDirective directive,
                       final MatchResult match,
                       final int matchCount,
                       final int depth) {
        if (depth >= directive.maxDepth()) {
            return;
        }
        final byte[] content = Refs.resolve(directive.select(), match, matchCount, vars);
        if (content == null || content.length == 0) {
            return;
        }

        final String mode = directive.templateRef() != null
                ? "__rec_" + directive.templateRef()
                : directive.mode();
        final List<CompiledTemplate> candidates = compiled.templates().stream()
                .filter(t -> java.util.Objects.equals(t.template().mode(), mode))
                .toList();

        // A recursive apply gets its own scope, so that a nested level's captures cannot leak
        // back into the level that invoked it — and so that they are released on the way out.
        final boolean recursive = directive.templateRef() != null
                                  || (directive.mode() != null && directive.mode().startsWith("__rec_"));
        if (recursive) {
            vars.push();
            candidates.forEach(candidate -> candidate.template().captures()
                    .forEach(capture -> vars.shadow(capture.name())));
        }

        int cursor = 0;
        for (final CompiledTemplate candidate : candidates) {
            if (cursor >= content.length) {
                break;
            }
            cursor += template(candidate, content, cursor, content.length, depth + 1);
        }

        if (recursive) {
            vars.pop();
        }
    }

    // -----------------------------------------------------------------------------------
    // Input
    // -----------------------------------------------------------------------------------

    /** Read one buffer, or null at the end of the input. */
    private static byte[] read(final InputStream input, final int size) {
        try {
            if (size == Integer.MAX_VALUE) {
                final byte[] all = input.readAllBytes();
                return all.length == 0 ? null : all;
            }
            final byte[] buffer = new byte[size];
            int total = 0;
            while (total < size) {
                final int read = input.read(buffer, total, size - total);
                if (read < 0) {
                    break;
                }
                total += read;
            }
            return total == 0 ? null : (total == size ? buffer : Arrays.copyOf(buffer, total));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** How many bytes of byte-order mark to skip. UTF-8 only for now; the rest is phase 6. */
    private static int byteOrderMarkLength(final byte[] chunk) {
        if (chunk.length >= 3
            && (chunk[0] & 0xFF) == 0xEF && (chunk[1] & 0xFF) == 0xBB && (chunk[2] & 0xFF) == 0xBF) {
            return 3;
        }
        return 0;
    }
}
