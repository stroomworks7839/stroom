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
import stroom.shapeshifter.engine.Severity;
import stroom.shapeshifter.engine.config.CaptureBinding;
import stroom.shapeshifter.engine.config.Codec;
import stroom.shapeshifter.engine.config.CombinatorPattern;
import stroom.shapeshifter.engine.config.Condition;
import stroom.shapeshifter.engine.config.ConfigException;
import stroom.shapeshifter.engine.config.Dispatch;
import stroom.shapeshifter.engine.config.MatchExpression;
import stroom.shapeshifter.engine.config.MatchStep;
import stroom.shapeshifter.engine.config.OutputNode;
import stroom.shapeshifter.engine.config.Project;
import stroom.shapeshifter.engine.config.RefExpression;
import stroom.shapeshifter.engine.config.Template;
import stroom.shapeshifter.engine.exec.Codecs;
import stroom.shapeshifter.engine.exec.EngineVars;
import stroom.shapeshifter.engine.text.Encoding;
import stroom.shapeshifter.regex.BytePattern;
import stroom.shapeshifter.regex.Flag;
import stroom.shapeshifter.regex.LeadingAnchor;
import stroom.shapeshifter.regex.PatternCompileException;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns an authored configuration into one that can run.
 *
 * <p>Compilation is where a configuration's mistakes are found. A pattern that will not compile
 * is an error now, with the template's name attached, rather than a surprise on the ten
 * thousandth record — which is the whole reason this is a separate pass rather than something
 * the match loop does lazily.
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
        final Encoding encoding = encoding(project.source().encoding());
        final Charset charset = encoding.isUtf8Compatible() || encoding == Encoding.RAW
                ? StandardCharsets.UTF_8
                : encoding.charset();
        final List<CompiledTemplate> templates = new ArrayList<>(project.templates().size());
        final List<Message> warnings = new ArrayList<>();
        final Map<String, BytePattern> patterns = new HashMap<>();

        for (final Template template : project.templates()) {
            // An eater's matches do not count, so its captures would have no index to bind
            // at — and a binding would trip the first-match store clearing (D36, §8b).
            if (template.consume() && !template.captures().isEmpty()) {
                throw new ConfigException("Template '" + template.name()
                                          + "' is marked consume but declares captures: an eater's"
                                          + " matches do not count, so there is no index to bind them at");
            }
            // Patterns first: a body's compiled form resolves its regex replaces against them.
            if (template.guard() != null) {
                collect(template.guard(), template, patterns);
            }
            collect(template.body(), template, patterns);
            if (template.match() instanceof MatchExpression.Progressive progressive) {
                steps(progressive.steps(), template, patterns);
            }
            // E3: a template's declared encoding overrides the source's — for the byte form
            // of its delimiters here at compile time, and for reading its captures at run time.
            Encoding declared = null;
            if (template.encoding() != null) {
                declared = Encoding.fromLabel(template.encoding());
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
            }
            final Encoding matchEncoding = declared == null ? encoding : declared;
            final Charset templateCharset =
                    matchEncoding.isUtf8Compatible() || matchEncoding == Encoding.RAW
                            ? StandardCharsets.UTF_8
                            : matchEncoding.charset();
            templates.add(new CompiledTemplate(template,
                    compileMatch(template, templateCharset, project),
                    CompiledOp.compile(template.body(), patterns, project),
                    declared));
        }
        resolveTemplateNames(project);
        dispatchChecks(project, templates, warnings);
        bodyChecks(project, warnings);
        return new CompiledProject(project, templates, patterns, encoding, warnings);
    }

    /**
     * Every check that reads a template body, in <b>one walk</b> (E27).
     *
     * <p>Three phases each added a check and each walked every body to run it — design/17
     * §8's comparison lint, §10's unknown-reference refusal, §7's substring bump warning —
     * which measured at −38% compile on the configurations whose compile is otherwise
     * trivial. Irrelevant in absolute terms (0.6 µs on a once-per-load cost) and filed for
     * its shape: three walks is where a fourth check becomes four.
     *
     * <p>The order the checks report in is preserved exactly, because it is observable: every
     * lint is emitted before any reference error is thrown, and the substring warning comes
     * last, after the refusal that can prevent it.
     */
    private static void bodyChecks(final Project project, final List<Message> warnings) {
        final BodyScan scan = new BodyScan(project, warnings);
        for (final Template template : project.templates()) {
            scan.template(template);
        }
        scan.report();
    }

    /**
     * One pass over a configuration's bodies, collecting what the three checks need.
     *
     * <p>Two of the three can decide as they go — a comparison lint and a substring count
     * need nothing but the node in front of them. The reference refusal cannot: a read in
     * the first template may name something the last one writes, so reads are collected and
     * judged against the finished write set in {@link #report()}. That deferral is what lets
     * one walk do the work of the two the refusal used to need on its own.
     *
     * <p>{@link #visit} is deliberately <b>exhaustive</b> — no {@code default} arm. An
     * instruction added to the vocabulary without being considered here is a compile error,
     * where before it was three separate switches that would each silently ignore it. That
     * is the real repair; the microseconds are incidental.
     */
    private static final class BodyScan {

        private record Read(String templateName, RefExpression ref) {

            Read(final String templateName, final String name) {
                this(templateName, new RefExpression(
                        List.of(new RefExpression.RefPart.Capture(name, 0, null))));
            }

            /** The single name this read is about, for the sequence checks. */
            String name() {
                return ((RefExpression.RefPart.Capture) ref.parts().getFirst()).varId();
            }
        }

        private final Project project;
        private final List<Message> warnings;
        private final Set<String> writable = new HashSet<>();
        private final List<Read> reads = new ArrayList<>();

        /**
         * A key-value capture binds names read out of the data itself, so the writable set is
         * not statically knowable and the refusal stands down for the whole configuration
         * rather than accusing every data-driven read.
         */
        private boolean referencesKnowable = true;
        private int explicitSubstringStarts;
        private String templateName;

        /** Sequence bookkeeping (design/16 §9): what is declared, what is captured, what is used. */
        private final Set<String> declaredSequences = new HashSet<>();
        private final Set<String> captureNames = new HashSet<>();
        private final List<Read> sequenceUses = new ArrayList<>();
        private final List<Read> appendTargets = new ArrayList<>();

        /** How many {@code for-each} bodies enclose the node being visited. */
        private int iterationDepth;

        BodyScan(final Project project, final List<Message> warnings) {
            this.project = project;
            this.warnings = warnings;
            // Every name the engine sets is writable by definition, named once in EngineVars
            // so that setting, reading and refusing cannot drift apart.
            writable.addAll(EngineVars.ALL);
        }

        void template(final Template template) {
            templateName = template.name();
            // Guard, then captures, then body — the order the three separate checks read in,
            // preserved because it decides which error a template with two unknown names
            // reports, and there is no reason for a merge to change that (E27 audit).
            if (template.guard() != null) {
                condition(template.guard());
            }
            for (final CaptureBinding capture : template.captures()) {
                writable.add(capture.name());
                captureNames.add(capture.name());
                switch (capture.select()) {
                    case CaptureBinding.CaptureSource.Select select -> read(select.select());
                    case CaptureBinding.CaptureSource.KeyValue keyValue -> {
                        referencesKnowable = false;
                        read(keyValue.keyRef());
                        read(keyValue.valueRef());
                    }
                    default -> {
                    }
                }
            }
            for (final Template.ParamDecl declared : template.param()) {
                writable.add(declared.name());
            }
            body(template.body());
        }

        private void body(final List<OutputNode> body) {
            for (final OutputNode node : body) {
                visit(node);
            }
        }

        private void visit(final OutputNode node) {
            switch (node) {
                case OutputNode.Text ignored -> {
                }
                case OutputNode.ValueOf value -> read(value.select());
                case OutputNode.EmitError value -> read(value.message());
                case OutputNode.If value -> {
                    condition(value.test());
                    body(value.then());
                }
                case OutputNode.Choose value -> {
                    for (final OutputNode.WhenBranch branch : value.when()) {
                        condition(branch.test());
                        body(branch.body());
                    }
                    body(value.otherwise());
                }
                case OutputNode.Switch value -> {
                    read(value.select());
                    for (final OutputNode.SwitchCase switchCase : value.cases()) {
                        body(switchCase.body());
                    }
                    body(value.defaultBody());
                }
                case OutputNode.ApplyTemplates value -> {
                    read(value.directive().select());
                    for (final OutputNode.Param param : value.directive().withParam()) {
                        writable.add(param.name());
                        read(param.value());
                    }
                }
                case OutputNode.CallTemplate value -> {
                    for (final OutputNode.Param param : value.withParam()) {
                        writable.add(param.name());
                        read(param.value());
                    }
                }
                case OutputNode.Variable value -> {
                    writable.add(value.name());
                    body(value.body());
                }
                case OutputNode.ValueMap value -> transform(List.of(value.select()), value.name());
                case OutputNode.Translate value -> transform(value.select(), value.name());
                case OutputNode.StringJoin value -> transform(value.select(), value.name());
                case OutputNode.Replace value -> transform(value.select(), value.name());
                case OutputNode.LowerCase value -> transform(value.select(), value.name());
                case OutputNode.UpperCase value -> transform(value.select(), value.name());
                case OutputNode.NormalizeSpace value -> transform(value.select(), value.name());
                case OutputNode.Trim value -> transform(value.select(), value.name());
                case OutputNode.Substring value -> {
                    // Only an explicit start moves at the version gate; an omitted one means
                    // "from the beginning" under either base (design/17 §7, phase 6 audit).
                    if (value.start() != null) {
                        explicitSubstringStarts++;
                    }
                    transform(value.select(), value.name());
                }
                case OutputNode.Tokenize value -> transform(value.select(), value.name());
                case OutputNode.Number value -> transform(value.select(), value.name());
                case OutputNode.Add value -> transform(value.select(), value.name());
                case OutputNode.Subtract value -> transform(value.select(), value.name());
                case OutputNode.Multiply value -> transform(value.select(), value.name());
                case OutputNode.Divide value -> transform(value.select(), value.name());
                case OutputNode.Mod value -> transform(value.select(), value.name());
                case OutputNode.Round value -> transform(value.select(), value.name());
                case OutputNode.Floor value -> transform(value.select(), value.name());
                case OutputNode.Ceiling value -> transform(value.select(), value.name());
                case OutputNode.Abs value -> transform(value.select(), value.name());
                case OutputNode.StringLength value -> transform(value.select(), value.name());
                case OutputNode.SubstringBefore value -> transform(value.select(), value.name());
                case OutputNode.SubstringAfter value -> transform(value.select(), value.name());
                case OutputNode.StartsWith value -> transform(value.select(), value.name());
                case OutputNode.EndsWith value -> transform(value.select(), value.name());
                case OutputNode.Contains value -> transform(value.select(), value.name());
                case OutputNode.FormatNumber value -> transform(value.select(), value.name());
                case OutputNode.ParseDate value -> {
                    transform(value.select(), value.name());
                    read(value.reference());
                }
                case OutputNode.FormatDate value -> transform(value.select(), value.name());
                case OutputNode.Sequence value -> {
                    declaredSequences.add(value.name());
                    writable.add(value.name());
                }
                case OutputNode.Append value -> {
                    appendTargets.add(new Read(templateName, value.name()));
                    writable.add(value.name());
                    read(value.select());
                }
                case OutputNode.ForEach value -> {
                    sequenceUses.add(new Read(templateName, value.select()));
                    if (value.as() != null) {
                        writable.add(value.as());
                    }
                    iterationDepth++;
                    body(value.body());
                    iterationDepth--;
                }
            }
        }

        /** The shape almost every instruction has: some selects read, an optional name bound. */
        private void transform(final List<RefExpression> select, final String name) {
            select.forEach(this::read);
            if (name != null) {
                writable.add(name);
            }
        }

        /**
         * Design/17 §8's lint, decided in place: a typed literal compared against an uncast
         * reference is the strict rule's one foot-gun — captures are text, so the comparison
         * is false on every record, silently — and it is statically visible, so it draws a
         * warning (D36's tier: warnings until a lint can prove confusion rather than suspect
         * it). Conditions also carry reads, which are collected on the same visit.
         */
        private void condition(final Condition condition) {
            switch (condition) {
                case Condition.Compare value -> {
                    if (value.left().ref() != null) {
                        read(value.left().ref());
                    }
                    if (value.right().ref() != null) {
                        read(value.right().ref());
                    }
                    if (mismatch(value.left(), value.right()) || mismatch(value.right(), value.left())) {
                        warnings.add(new Message(Severity.WARNING, "Template '" + templateName
                                + "' compares a typed literal against an uncast reference:"
                                + " captures are text, so this is false on every record."
                                + " Add as: \"number\" (or the intended cast) to the reference"
                                + " if a typed comparison is meant."));
                    }
                }
                case Condition.Matches value -> read(value.select());
                case Condition.Contains value -> read(value.select());
                case Condition.StartsWith value -> read(value.select());
                case Condition.Exists value -> read(value.select());
                case Condition.And value -> value.conditions().forEach(this::condition);
                case Condition.Or value -> value.conditions().forEach(this::condition);
                case Condition.Not value -> condition(value.condition());
                case Condition.IsFirst ignored -> positional("is-first");
                case Condition.IsLast ignored -> positional("is-last");
            }
        }

        /**
         * E21's hazard, caught rather than rediscovered: outside an iteration nothing sets
         * {@code __position}, so these read false on every record — which is what got them
         * deleted the first time. Inside one they are exact.
         */
        private void positional(final String spelling) {
            if (iterationDepth == 0) {
                warnings.add(new Message(Severity.WARNING, "Template '" + templateName
                        + "' tests " + spelling + " outside any for-each: nothing sets a"
                        + " position there, so it is false on every record."));
            }
        }

        /** A typed literal on one side, an uncast reference on the other. */
        private static boolean mismatch(final Condition.Operand literalSide,
                                        final Condition.Operand refSide) {
            return literalSide.literal() != null
                   && !(literalSide.literal() instanceof Condition.Literal.Text)
                   && literalSide.as() == null
                   && refSide.ref() != null
                   && refSide.as() == null;
        }

        private void read(final RefExpression ref) {
            if (ref != null) {
                reads.add(new Read(templateName, ref));
            }
        }

        /**
         * What could only be decided once the whole configuration had been seen. The order is
         * the one the three separate checks had: the reference refusal can throw, and the
         * substring warning is after it because it was after it before.
         */
        void report() {
            if (referencesKnowable) {
                for (final Read read : reads) {
                    checkRead(read);
                }
            }
            // Design/16 §9's two checks. An append to a name no sequence declares would
            // create the store in the innermost scope and lose it on the way out — a
            // configuration that appears to work and accumulates nothing.
            for (final Read append : appendTargets) {
                if (!declaredSequences.contains(append.name())) {
                    throw new ConfigException("Template '" + append.templateName()
                            + "' appends to '" + append.name() + "', which no sequence"
                            + " declares. Declare it where the accumulation should live.");
                }
            }
            // A sequence sharing a capture's name would be emptied mid-run by that
            // template's first-match clearing (E19), which is not a thing an author can see.
            for (final String declared : declaredSequences) {
                if (captureNames.contains(declared)) {
                    throw new ConfigException("Sequence '" + declared + "' has the same name"
                            + " as a capture. A template's first match clears its captures,"
                            + " which would empty the sequence underneath it mid-run.");
                }
            }
            for (final Read use : sequenceUses) {
                if (!writable.contains(use.name())) {
                    throw new ConfigException("Template '" + use.templateName()
                            + "' walks '" + use.name() + "', which nothing writes — no"
                            + " sequence declares it and no capture binds it.");
                }
            }
            if (project.version() < 5 && explicitSubstringStarts > 0) {
                warnings.add(new Message(Severity.WARNING, "This configuration's "
                        + explicitSubstringStarts + " substring instruction"
                        + (explicitSubstringStarts == 1 ? " reads" : "s read")
                        + " start as 0-based; version 5 reads it as 1-based. Add 1 to each start"
                        + " when bumping the version."));
            }
        }

        /**
         * Design/17 §10's unknown-reference refusal: a read of a name that nothing writes — no
         * capture, no {@code variable}, no transform bind, no parameter — is a compile-time
         * error naming the reference and its template. This is where typos actually are, and
         * it costs nothing at run time; the alternative is a configuration that appears to
         * work and quietly reads absence for ever.
         */
        private void checkRead(final Read read) {
            for (final RefExpression.RefPart part : read.ref().parts()) {
                if (part instanceof RefExpression.RefPart.Capture capture) {
                    if (capture.varId() != null && !writable.contains(capture.varId())) {
                        throw new ConfigException("Template '" + read.templateName()
                                + "' reads '" + capture.varId() + "', which nothing writes —"
                                + " no capture, variable, transform bind or parameter has that"
                                + " name. A misspelt name would otherwise read as absent for"
                                + " ever.");
                    }
                    if (capture.matchIndex() != null && capture.matchIndex().varRef() != null
                        && !writable.contains(capture.matchIndex().varRef())) {
                        throw new ConfigException("Template '" + read.templateName()
                                + "' indexes by '" + capture.matchIndex().varRef()
                                + "', which nothing writes.");
                    }
                }
            }
        }
    }

    /**
     * D36's dispatch lint: a line-anchored pattern in a strict or lexer level draws a warning —
     * the anchored question means it matches at the cursor only, and a line anchor does not
     * make it search line starts.
     */
    private static void dispatchChecks(final Project project,
                                       final List<CompiledTemplate> templates,
                                       final List<Message> warnings) {
        final List<OutputNode.ApplyDirective> applies = new ArrayList<>();
        for (final Template template : project.templates()) {
            collectApplies(template.body(), applies);
        }
        final Set<String> strictModes = new HashSet<>();
        for (final OutputNode.ApplyDirective directive : applies) {
            final Dispatch effective = Dispatch.effective(directive.dispatch(), project);
            if (effective == Dispatch.STRICT || effective == Dispatch.LEXER) {
                strictModes.add(directive.templateRef() != null
                        ? "__rec_" + directive.templateRef()
                        : directive.mode());
            }
        }
        for (final CompiledTemplate compiledTemplate : templates) {
            if (strictModes.contains(compiledTemplate.template().mode())
                && compiledTemplate.match() instanceof CompiledMatch.Regex regex
                && regex.pattern().leadingAnchor() == LeadingAnchor.LINE) {
                warnings.add(new Message(Severity.WARNING, "Template '"
                        + compiledTemplate.template().name()
                        + "' uses a line-anchored pattern in a strict level: it matches at the"
                        + " cursor only, and the line anchor does not make it search line"
                        + " starts. If line iteration is intended, add a line eater."));
            }
        }
    }

    /**
     * Resolve every name that points at a template — a {@code call-template}'s target and an
     * {@code apply-templates}' template reference — against the templates that exist.
     *
     * <p>Compilation is where a configuration's mistakes are found. Left to run time, a name
     * with a typo in it finds nothing, and finding nothing is spelt the same as a template that
     * legitimately wrote nothing: the run completes, the output is short, and the configuration
     * looks correct.
     */
    private static void resolveTemplateNames(final Project project) {
        final Set<String> names = new HashSet<>();
        for (final Template template : project.templates()) {
            names.add(template.name());
        }
        for (final Template template : project.templates()) {
            final List<String> referenced = new ArrayList<>();
            collectCalls(template.body(), referenced);
            final List<OutputNode.ApplyDirective> applies = new ArrayList<>();
            collectApplies(template.body(), applies);
            for (final OutputNode.ApplyDirective directive : applies) {
                if (directive.templateRef() != null) {
                    referenced.add(directive.templateRef());
                }
            }
            for (final String name : referenced) {
                if (!names.contains(name)) {
                    throw new ConfigException("Template '" + template.name() + "' refers to a"
                                              + " template named '" + name + "', which does not exist");
                }
            }
        }
    }

    private static void collectCalls(final List<OutputNode> body, final List<String> names) {
        for (final OutputNode node : body) {
            switch (node) {
                case OutputNode.CallTemplate value -> names.add(value.name());
                case OutputNode.If value -> collectCalls(value.then(), names);
                case OutputNode.Choose value -> {
                    value.when().forEach(branch -> collectCalls(branch.body(), names));
                    collectCalls(value.otherwise(), names);
                }
                case OutputNode.Switch value -> {
                    value.cases().forEach(c -> collectCalls(c.body(), names));
                    collectCalls(value.defaultBody(), names);
                }
                case OutputNode.Variable value -> collectCalls(value.body(), names);
                default -> {
                    // Leaves as far as calls are concerned.
                }
            }
        }
    }

    private static void collectApplies(final List<OutputNode> body,
                                       final List<OutputNode.ApplyDirective> applies) {
        for (final OutputNode node : body) {
            switch (node) {
                case OutputNode.ApplyTemplates apply -> applies.add(apply.directive());
                case OutputNode.If value -> collectApplies(value.then(), applies);
                case OutputNode.Choose value -> {
                    value.when().forEach(branch -> collectApplies(branch.body(), applies));
                    collectApplies(value.otherwise(), applies);
                }
                case OutputNode.Switch value -> {
                    value.cases().forEach(c -> collectApplies(c.body(), applies));
                    collectApplies(value.defaultBody(), applies);
                }
                case OutputNode.Variable value -> collectApplies(value.body(), applies);
                default -> {
                    // Leaves as far as dispatch is concerned.
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------
    // Patterns used outside a template's own match
    // -----------------------------------------------------------------------------------

    /**
     * Compile the patterns hiding inside bodies and conditions.
     *
     * <p>A template's own pattern is obvious; these are the ones in a {@code matches} test or a
     * regex {@code replace}, nested arbitrarily deep in a {@code choose} inside a
     * {@code variable}. They are just as capable of being wrong, and finding out at compile time
     * is the difference between a configuration that is rejected and one that fails on a record.
     */
    private static void collect(final List<OutputNode> body,
                                final Template template,
                                final Map<String, BytePattern> patterns) {
        for (final OutputNode node : body) {
            switch (node) {
                case OutputNode.Replace replace -> {
                    if (replace.isRegex()) {
                        intern(replace.pattern(), template, patterns);
                    }
                }
                case OutputNode.If value -> {
                    collect(value.test(), template, patterns);
                    collect(value.then(), template, patterns);
                }
                case OutputNode.Choose value -> {
                    value.when().forEach(branch -> {
                        collect(branch.test(), template, patterns);
                        collect(branch.body(), template, patterns);
                    });
                    collect(value.otherwise(), template, patterns);
                }
                case OutputNode.Switch value -> {
                    value.cases().forEach(switchCase -> collect(switchCase.body(), template, patterns));
                    collect(value.defaultBody(), template, patterns);
                }
                case OutputNode.Variable value -> collect(value.body(), template, patterns);
                default -> {
                    // Every other instruction is a leaf as far as patterns are concerned.
                }
            }
        }
    }

    private static void collect(final Condition condition,
                                final Template template,
                                final Map<String, BytePattern> patterns) {
        switch (condition) {
            case Condition.Matches matches -> intern(matches.pattern(), template, patterns);
            case Condition.And value -> value.conditions()
                    .forEach(child -> collect(child, template, patterns));
            case Condition.Or value -> value.conditions()
                    .forEach(child -> collect(child, template, patterns));
            case Condition.Not value -> collect(value.condition(), template, patterns);
            default -> {
                // Everything else compares values rather than matching patterns.
            }
        }
    }

    private static void intern(final String pattern,
                               final Template template,
                               final Map<String, BytePattern> patterns) {
        patterns.computeIfAbsent(pattern, text -> {
            try {
                return BytePattern.compile(text);
            } catch (final PatternCompileException e) {
                throw new ConfigException("Template '" + template.name() + "' has an invalid pattern '"
                                          + text + "': " + e.getMessage(), e);
            }
        });
    }

    /**
     * Walk a step sequence for the patterns it uses and the codecs it needs.
     *
     * <p>A codec this build cannot apply is refused here rather than returning nothing at match
     * time, because "no match" and "cannot do that" are different answers and only one of them
     * is the configuration's fault.
     */
    private static void steps(final List<MatchStep> steps,
                              final Template template,
                              final Map<String, BytePattern> patterns) {
        for (final MatchStep step : steps) {
            switch (step) {
                case MatchStep.Regex regex -> intern(regex.pattern(), template, patterns);
                case MatchStep.Decode decode -> requireCodec(decode.codec(), template);
                case MatchStep.Encode encode -> requireCodec(encode.codec(), template);
                case MatchStep.Choice choice ->
                        choice.alternatives().forEach(alternative -> steps(alternative, template, patterns));
                case MatchStep.Optional optional -> steps(optional.steps(), template, patterns);
                case MatchStep.Repeat repeat -> steps(repeat.steps(), template, patterns);
                case MatchStep.Sequence sequence -> steps(sequence.steps(), template, patterns);
                case MatchStep.Peek peek -> steps(peek.steps(), template, patterns);
                case MatchStep.Not not -> steps(not.steps(), template, patterns);
                default -> {
                    // The remaining atoms need nothing compiled.
                }
            }
        }
    }

    private static void requireCodec(final Codec codec, final Template template) {
        if (!Codecs.isSupported(codec)) {
            throw notYet(template, codec.name().toLowerCase(Locale.ROOT) + " coding");
        }
    }

    /**
     * Inline the named patterns a sequence refers to.
     *
     * <p>Composition is an authoring convenience; by the time anything runs there are no
     * references left, only the steps they stood for (D8).
     *
     * <p>{@code inProgress} is what stops a pattern that refers to itself, directly or round a
     * longer loop, from inlining for ever. It is unwound on the way out rather than accumulated,
     * so a pattern used twice in different branches is fine — only a pattern reached from inside
     * itself is a cycle.
     */
    private static List<MatchStep> resolve(final List<MatchStep> steps,
                                           final Project project,
                                           final Set<UUID> inProgress) {
        final List<MatchStep> resolved = new ArrayList<>(steps.size());
        for (final MatchStep step : steps) {
            final MatchStep inlined = switch (step) {
                case MatchStep.PatternRef reference -> {
                    if (!inProgress.add(reference.pattern())) {
                        throw new ConfigException(
                                "Pattern " + reference.pattern() + " refers to itself");
                    }
                    final CombinatorPattern named = project.patterns().stream()
                            .filter(candidate -> candidate.id().equals(reference.pattern()))
                            .findFirst()
                            .orElseThrow(() -> new ConfigException(
                                    "No pattern with id " + reference.pattern()));
                    final List<MatchStep> inner = resolve(named.steps(), project, inProgress);
                    inProgress.remove(reference.pattern());
                    yield new MatchStep.Sequence(inner);
                }
                case MatchStep.Choice choice -> new MatchStep.Choice(
                        choice.alternatives().stream().map(a -> resolve(a, project, inProgress)).toList());
                case MatchStep.Optional optional ->
                        new MatchStep.Optional(resolve(optional.steps(), project, inProgress));
                case MatchStep.Repeat repeat -> new MatchStep.Repeat(
                        resolve(repeat.steps(), project, inProgress), repeat.min(), repeat.max());
                case MatchStep.Sequence sequence ->
                        new MatchStep.Sequence(resolve(sequence.steps(), project, inProgress));
                case MatchStep.Peek peek -> new MatchStep.Peek(resolve(peek.steps(), project, inProgress));
                case MatchStep.Not not -> new MatchStep.Not(resolve(not.steps(), project, inProgress));
                default -> step;
            };
            resolved.add(inlined);
        }
        return resolved;
    }

    private static CompiledMatch compileMatch(final Template template,
                                              final Charset charset,
                                              final Project project) {
        return switch (template.match()) {
            case MatchExpression.Regex regex -> {
                final Set<Flag> flags = EnumSet.noneOf(Flag.class);
                if (regex.flags().caseInsensitive()) {
                    flags.add(Flag.CASE_INSENSITIVE);
                }
                if (regex.flags().dotAll()) {
                    flags.add(Flag.DOT_ALL);
                }
                final BytePattern pattern;
                try {
                    pattern = BytePattern.compile(regex.pattern(), flags);
                } catch (final PatternCompileException e) {
                    throw new ConfigException(
                            "Template '" + template.name() + "' has an invalid pattern '"
                            + regex.pattern() + "': " + e.getMessage(), e);
                }
                if (regex.advance() > pattern.groupCount()) {
                    throw new ConfigException(
                            "Template '" + template.name() + "' advances to group " + regex.advance()
                            + ", but its pattern has only " + pattern.groupCount() + " groups");
                }
                yield new CompiledMatch.Regex(pattern, regex.advance());
            }
            case MatchExpression.Delimiter delimiter -> new CompiledMatch.Delimiter(
                    encode(delimiter.delimiter(), charset),
                    encode(delimiter.escape(), charset),
                    encode(delimiter.containerStart(), charset),
                    encode(delimiter.containerEnd(), charset));
            case MatchExpression.All ignored -> new CompiledMatch.All();
            case MatchExpression.Source ignored -> new CompiledMatch.Source();
            case MatchExpression.Named ignored -> new CompiledMatch.Named();
            case MatchExpression.Progressive progressive -> new CompiledMatch.Progressive(
                    resolve(progressive.steps(), project, new HashSet<>()));
            case MatchExpression.Avro ignored -> throw notYet(template, "Avro decoding");
            case MatchExpression.Parquet ignored -> throw notYet(template, "Parquet decoding");
            case MatchExpression.Protobuf ignored -> throw notYet(template, "Protobuf decoding");
        };
    }

    /**
     * Refuse clearly rather than fail obscurely.
     *
     * <p>The callers are the binary format matches — Avro, Parquet, Protobuf — deferred by
     * decision (D33), and the compression codecs the JDK does not carry. In both cases a
     * configuration that names them should be told so at compile time — not run and produce
     * nothing.
     */
    private static ConfigException notYet(final Template template, final String what) {
        return new ConfigException(
                "Template '" + template.name() + "' needs " + what + ", which this build does not support");
    }

    private static byte[] encode(final String text, final Charset charset) {
        return text == null ? null : text.getBytes(charset);
    }

    /**
     * The encoding a configuration's label names.
     *
     * <p>An unknown name is a configuration error, and so is a known name this build has no
     * charset for — better to say so now than to read a stream as something it is not.
     */
    public static Encoding encoding(final String label) {
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
