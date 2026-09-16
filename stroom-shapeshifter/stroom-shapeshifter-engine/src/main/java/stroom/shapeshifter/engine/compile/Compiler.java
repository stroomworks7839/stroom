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
import stroom.shapeshifter.engine.config.Declaration;
import stroom.shapeshifter.engine.config.MatchExpression;
import stroom.shapeshifter.engine.config.Project;
import stroom.shapeshifter.engine.config.Template;
import stroom.shapeshifter.engine.function.FunctionRegistry;
import stroom.shapeshifter.engine.graph.CompiledCapture;
import stroom.shapeshifter.engine.graph.CompiledCondition;
import stroom.shapeshifter.engine.graph.CompiledMatch;
import stroom.shapeshifter.engine.graph.CompiledOp;
import stroom.shapeshifter.engine.graph.CompiledProject;
import stroom.shapeshifter.engine.graph.CompiledTemplate;
import stroom.shapeshifter.engine.graph.VarName;
import stroom.shapeshifter.engine.text.Encoding;
import stroom.shapeshifter.engine.text.RegexEncodings;
import stroom.shapeshifter.engine.value.TypedValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
 * its match compiled by {@link MatchCompiler} and its body by {@link BodyCompiler#compile}; the
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
     *
     * <p>The source's declared encoding is used, and {@code auto} resolves to UTF-8 — because
     * there is no input here to look at, and design 32 §6 ruled UTF-8 the default. A caller that
     * <em>has</em> the input should settle the encoding itself and use
     * {@link #compile(Project, FunctionRegistry, Encoding)}.
     */
    public static CompiledProject compile(final Project project, final FunctionRegistry registry) {
        final Encoding declared = encoding(project.source().encoding());
        return compile(project, registry, declared == Encoding.AUTO ? Encoding.UTF_8 : declared);
    }

    /**
     * Compile a configuration <b>for a reading</b> (design 32).
     *
     * <p>A compiled model is compiled for one encoding: its patterns are interned under keys that
     * carry it, its delimiters are pre-encoded, its steps are compiled for a decoding. So the
     * encoding is settled before anything here runs, and this refuses {@link Encoding#AUTO}
     * rather than accepting an instruction where a reading belongs.
     *
     * <p>That refusal is the invariant, not a formality: {@code AUTO} reaching a compiler is how
     * a graph ends up unable to say what it was built for, which is the defect design 32 exists
     * to remove. The orchestration resolves it — from the declaration, or by sniffing the input —
     * and pools a model per reading it needs.
     *
     * @param source the reading the input is in; never {@code AUTO}
     */
    public static CompiledProject compile(final Project project,
                                          final FunctionRegistry registry,
                                          final Encoding source) {
        if (source == Encoding.AUTO) {
            throw new ConfigException("A configuration is compiled for a reading, and 'auto' is "
                                      + "an instruction rather than one: settle the encoding "
                                      + "against the input before compiling (design 32)");
        }
        final Functions functions = new Functions(registry);
        // A transcode-family source (design 19 phase 6) is decoded whole to UTF-8 before the
        // window machinery sees it, so everything below compiles as a UTF-8 feed: delimiters,
        // steps, regexes, capture decoding. Spans are offsets into the transcoded bytes —
        // design 19 §4.0's accepted trade for the encodings that never preserved offsets anyway.
        final Encoding transcodeFrom = RegexEncodings.needsTranscode(source)
                ? source
                : null;
        final Encoding encoding = transcodeFrom != null
                ? Encoding.UTF_8
                : source;
        // Names are interned as the graph is built, by whatever compiles a node that names a
        // variable — the same shape the match compiler interns patterns with, and no second
        // walk to keep in step with the first (design 30 §5.5).
        final Interner names = new Interner();
        // Every declaration first (design 35 §5): what a name holds decides how a capture or a
        // bind anywhere writes it, and a template's captures may be declared by a template
        // compiled after it.
        for (final Template template : project.templates()) {
            for (final Declaration declaration : template.declarations()) {
                names.declare(declaration);
            }
        }
        final MatchCompiler matches = new MatchCompiler(project);
        final List<CompiledTemplate> templates = new ArrayList<>(project.templates().size());
        final List<Message> warnings = new ArrayList<>();

        final BodyCompiler bodies = new BodyCompiler(matches.patterns(), project, functions, names);
        for (final Template template : project.templates()) {
            refuseCaptures(template);
            final Encoding declared = declaredEncoding(template, transcodeFrom);
            final Encoding matchEncoding = declared == null ? encoding : declared;
            final CompiledMatch match = matches.compile(template, matchEncoding, names);
            templates.add(template(template, match,
                    bodies.compile(template.body()),
                    declared,
                    CaptureCompiler.compile(template.captures(), names),
                    // The guard's patterns were interned by the match compile above.
                    ConditionCompiler.compile(template.guard(), matches.patterns(), names),
                    names));
        }
        final List<TemplateUses> uses = TemplateUses.of(project);
        TemplateUses.resolveNames(project, uses);
        TemplateUses.lintDispatch(project, templates, uses, warnings);
        final boolean structured = bodyChecks(project, warnings);
        // The bodies were compiled before the templates they name existed; now they do. This
        // runs before the graph is built rather than after, because linking interns the last
        // names — a call's parameters — and the table handed to the graph has to be the finished
        // one. The old order took the table first and closed it afterwards with a flag.
        // One array, built once the last template is compiled, and handed to everything that
        // holds the compiled set: the linker, the project and the root plan (design 33 §11).
        final CompiledTemplate[] compiled = templates.toArray(new CompiledTemplate[0]);
        bodies.link(compiled);
        return new CompiledProject(project, compiled,
                encoding, transcodeFrom, warnings, functions.used(), structured, names.names(),
                RootPlanner.plan(project, compiled));
    }

    /** What a template's captures alone can be wrong about. */
    private static void refuseCaptures(final Template template) {
        // An eater's matches do not count, so its captures would have no index to bind
        // at — and a binding would trip the first-match restart of a list (D36, §8b).
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

    /**
     * The match indices whose bodies run, as a sorted {@code int[]}, or null for all of them.
     *
     * <p>The authored form is a {@code Set<Integer>} and stays one — the model stays the model
     * (D35) — but {@code Level} tests it on <b>every match</b>, up to 569,199 times per operation
     * on the strict Windows-event row, and a hash lookup that boxes an {@code Integer} to ask
     * "is this the second match?" is the wrong shape for that. Sorted so the array is
     * deterministic; a set's iteration order is not.
     */
    private static int[] onlyMatch(final Template template) {
        final Set<Integer> only = template.matchLimits().onlyMatch();
        if (only == null) {
            return null;
        }
        return only.stream().mapToInt(Integer::intValue).sorted().toArray();
    }

    /**
     * Compile a template, deciding here everything the match loop would otherwise ask the
     * authored {@link Template} for on every candidate, every match and every level entry
     * (design 29 §3.1, D51). The model stays the model, carried for names, identifiers and
     * messages; the loop reads the fields beside it.
     */
    private static CompiledTemplate template(final Template template,
                                             final CompiledMatch match,
                                             final CompiledOp[] body,
                                             final Encoding encoding,
                                             final CompiledCapture[] captures,
                                             final CompiledCondition guard,
                                             final Interner names) {
        // What the template declares is what its entry pushes and its exit restores (design 35
        // §4): the slots, settled here so the run never asks the model.
        final List<VarName> declared = new ArrayList<>();
        final List<TypedValue> initial = new ArrayList<>();
        for (final Declaration declaration : template.declarations()) {
            declared.add(names.intern(declaration.name()));
            // A map declared with entries starts as that table on every entry (design 35 §5,
            // what value-map used to be); everything else starts unset.
            TypedValue start = null;
            if (!declaration.entries().isEmpty()) {
                final TypedValue.Map table = new TypedValue.Map();
                for (final Declaration.Entry entry : declaration.entries()) {
                    if (!table.contains(TypedValue.of(entry.from()))) {
                        table.put(TypedValue.of(entry.from()), TypedValue.of(entry.to()));
                    }
                }
                start = table;
            }
            initial.add(start);
        }
        // The lists a capture fills restart at the template's first match of a sequence —
        // DS3's own rule (E19), kept as the capture's: a list declared for the run would
        // otherwise keep an earlier sequence's tail past this one's length. A scalar needs no
        // clearing, because a capture assigns it.
        final List<VarName> clear = new ArrayList<>();
        for (final CaptureBinding capture : template.captures()) {
            final VarName name = names.intern(capture.name());
            if (!(capture.select() instanceof CaptureBinding.CaptureSource.KeyValue)
                && names.typeOf(capture.name()) == Declaration.Type.LIST) {
                clear.add(name);
            }
        }
        return new CompiledTemplate(template, match, body, encoding, captures,
                template.matchLimits().maxMatch(),
                template.consume(),
                // A delimiter template's content is the field, group 1; every other template's
                // is the whole match. The group that carries the delimiter too is not it.
                template.match() instanceof MatchExpression.Delimiter ? 1 : 0,
                onlyMatch(template),
                guard,
                clear.toArray(VarName[]::new),
                declared.toArray(VarName[]::new),
                initial.toArray(TypedValue[]::new));
    }
}
