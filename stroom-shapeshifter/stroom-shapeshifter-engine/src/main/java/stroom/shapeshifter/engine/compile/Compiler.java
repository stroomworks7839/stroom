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

package stroom.shapeshifter.engine.compile;

import stroom.shapeshifter.engine.Message;
import stroom.shapeshifter.engine.config.CaptureBinding;
import stroom.shapeshifter.engine.config.ConfigException;
import stroom.shapeshifter.engine.config.Project;
import stroom.shapeshifter.engine.config.Template;
import stroom.shapeshifter.engine.function.FunctionRegistry;
import stroom.shapeshifter.engine.text.Encoding;
import stroom.shapeshifter.engine.text.RegexEncodings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Turns an authored configuration into one that can run.
 *
 * <p>Compilation is where a configuration's mistakes are found. A pattern that will not compile
 * is an error now, with the template's name attached, rather than a surprise on the ten
 * thousandth record — which is the whole reason this is a separate pass rather than something
 * the match loop does lazily.
 *
 * <p>This class is the pipeline; the passes are their own classes. In order: the source
 * encoding is settled; each template is refused for what a template alone can be wrong about,
 * its match compiled by {@link MatchCompiler} and its body by {@link CompiledOp#compile}; the
 * names templates call and apply to are collected once by {@link TemplateUses}, resolved, and
 * read by D36's dispatch lint against the compiled matches; and the body checks —
 * {@link ReferenceCheck} and {@link StructureCheck}, zipped per template — run last, the
 * reference refusals judged once every template has been seen. The order is observable, in
 * which error a doubly faulty configuration reports and in which warning comes first, and it
 * is kept exactly.
 */
public final class Compiler {

    private Compiler() {
    }

    /**
     * Compile a configuration.
     *
     * @throws ConfigException if anything in it cannot be compiled
     */
    public static CompiledProject compile(final Project project) {
        return compile(project, FunctionRegistry.EMPTY);
    }

    /**
     * Compile a configuration against the functions it may call (design 26 §3): an unknown name
     * or a wrong arity is a {@link ConfigException}, by name.
     */
    public static CompiledProject compile(final Project project, final FunctionRegistry registry) {
        final Functions functions = new Functions(registry, new LinkedHashMap<>());
        final Encoding sourceEncoding = encoding(project.source().encoding());
        // A transcode-family source (design 19 phase 6) is decoded whole to UTF-8 before the
        // window machinery sees it, so everything below compiles as a UTF-8 feed: delimiters,
        // steps, regexes, capture decoding. Spans are offsets into the transcoded bytes —
        // §4.0's accepted trade for the encodings that never preserved offsets anyway.
        final Encoding transcodeFrom = RegexEncodings.needsTranscode(sourceEncoding)
                ? sourceEncoding
                : null;
        final Encoding encoding = transcodeFrom != null
                ? Encoding.UTF_8
                : sourceEncoding;
        final MatchCompiler matches = new MatchCompiler(project);
        final List<CompiledTemplate> templates = new ArrayList<>(project.templates().size());
        final List<Message> warnings = new ArrayList<>();

        for (final Template template : project.templates()) {
            refuseCaptures(template);
            final Encoding declared = declaredEncoding(template, transcodeFrom);
            final Encoding matchEncoding = declared == null ? encoding : declared;
            // The match first: it interns the patterns the body's compiled form resolves against.
            final CompiledMatch match = matches.compile(template, matchEncoding);
            templates.add(new CompiledTemplate(template, match,
                    CompiledOp.compile(template.body(), matches.patterns(), project, functions),
                    declared));
        }
        final List<TemplateUses> uses = TemplateUses.of(project);
        TemplateUses.resolveNames(project, uses);
        TemplateUses.lintDispatch(project, templates, uses, warnings);
        final boolean structured = bodyChecks(project, warnings);
        return new CompiledProject(project, templates, matches.patterns(), encoding, transcodeFrom,
                warnings, List.copyOf(functions.used().values()), structured);
    }

    /** What a template's captures alone can be wrong about. */
    private static void refuseCaptures(final Template template) {
        // An eater's matches do not count, so its captures would have no index to bind
        // at — and a binding would trip the first-match store clearing (D36, §8b).
        if (template.consume() && !template.captures().isEmpty()) {
            throw new ConfigException("Template '" + template.name()
                                      + "' is marked consume but declares captures: an eater's"
                                      + " matches do not count, so there is no index to bind them at");
        }
        // A field capture source is read by the model and bound by nothing; until it is
        // defined it is refused by name rather than binding an absence (design 27, ruling 10).
        for (final CaptureBinding capture : template.captures()) {
            if (capture.select() instanceof CaptureBinding.CaptureSource.Field) {
                throw ConfigException.notYet(template.name(), "a field capture source");
            }
        }
    }

    /**
     * E3: a template's declared encoding overrides the source's — for the byte form of its
     * delimiters at compile time, and for reading its captures at run time.
     *
     * @return the override, or null when the template reads the source's encoding
     */
    private static Encoding declaredEncoding(final Template template, final Encoding transcodeFrom) {
        if (template.encoding() == null) {
            return null;
        }
        Encoding declared = Encoding.fromLabel(template.encoding());
        if (declared == null) {
            throw new ConfigException("Template '" + template.name()
                                      + "' declares an unknown encoding: " + template.encoding());
        }
        if (!declared.isAvailable()) {
            // The same refusal the source encoding gets: a charset this runtime lacks is
            // refused by name, never quietly approximated by a neighbour (E22).
            throw new ConfigException("Template '" + template.name() + "' declares "
                                      + declared.label() + ", and this build has no charset for it");
        }
        if (declared == Encoding.AUTO) {
            declared = null;
        }
        if (declared != null && RegexEncodings.needsTranscode(declared)) {
            // A template shares the source's byte stream, so there is nothing it
            // could transcode alone; the stage is whole-source (design 19 phase 6).
            throw new ConfigException("Template '" + template.name() + "' declares "
                    + declared.label() + ", which is served by transcoding — declare"
                    + " it on the source, where the stream can be transcoded whole");
        }
        if (declared != null && transcodeFrom != null) {
            // E3's per-template encodings describe rows of a mixed byte stream, and a
            // transcoded source has no mixed stream left — every template sees the decoder's
            // UTF-8, so an override would compile a machine for bytes the template can never see.
            throw new ConfigException("Template '" + template.name() + "' declares "
                    + declared.label() + ", but the " + transcodeFrom.label()
                    + " source is transcoded whole to UTF-8, so no template sees "
                    + declared.label() + " bytes; remove the template encoding");
        }
        return declared;
    }

    /**
     * Every check that reads a template body, in one walk each (E27): the reference check's
     * walk collects and lints as it goes, the structure check's judges as it goes, zipped per
     * template so that which error a doubly faulty template reports is decided by their order
     * within the template, as it always was; the reference refusals are judged last, once every
     * template has been seen.
     *
     * @return whether any template writes structure (design 20 §7)
     */
    private static boolean bodyChecks(final Project project, final List<Message> warnings) {
        final ReferenceCheck references = new ReferenceCheck(project, warnings);
        boolean structured = false;
        for (final Template template : project.templates()) {
            references.template(template);
            structured |= StructureCheck.check(template);
        }
        references.report();
        return structured;
    }

    /**
     * The encoding a configuration's label names.
     *
     * <p>An unknown name is a configuration error, and so is a known name this build has no
     * charset for — better to say so now than to read a stream as something it is not.
     */
    private static Encoding encoding(final String label) {
        if (label == null || label.isBlank()) {
            return Encoding.AUTO;
        }
        final Encoding encoding = Encoding.fromLabel(label);
        if (encoding == null) {
            throw new ConfigException("Unknown encoding: " + label);
        }
        if (!encoding.isAvailable()) {
            throw new ConfigException("This build has no charset for " + encoding.label());
        }
        return encoding;
    }
}
