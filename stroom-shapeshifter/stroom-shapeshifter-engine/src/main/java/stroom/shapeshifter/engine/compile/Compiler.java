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
        comparisonChecks(project, warnings);
        referenceChecks(project);
        substringVersionCheck(project, warnings);
        return new CompiledProject(project, templates, patterns, encoding, warnings);
    }

    /**
     * Design/17 §10's unknown-reference refusal: a read of a name that nothing writes — no
     * capture, no {@code variable}, no transform bind, no parameter — is a compile-time
     * error naming the reference and its template. This is where typos actually are, and it
     * costs nothing at run time; the alternative is a configuration that appears to work and
     * quietly reads absence for ever.
     */
    private static void referenceChecks(final Project project) {
        // A key-value capture binds names read out of the data itself, so the writable set
        // is not statically knowable: the check stands down for the whole configuration
        // rather than accusing every data-driven read. The common shape keeps the error.
        for (final Template template : project.templates()) {
            for (final CaptureBinding capture : template.captures()) {
                if (capture.select() instanceof CaptureBinding.CaptureSource.KeyValue) {
                    return;
                }
            }
        }
        final Set<String> writable = new HashSet<>();
        writable.add("__match_count");
        writable.add("__match_idx");
        for (final Template template : project.templates()) {
            for (final CaptureBinding capture : template.captures()) {
                writable.add(capture.name());
            }
            for (final Template.ParamDecl declared : template.param()) {
                writable.add(declared.name());
            }
            collectWrites(template.body(), writable);
        }
        for (final Template template : project.templates()) {
            final List<RefExpression> reads = new ArrayList<>();
            if (template.guard() != null) {
                collectConditionRefs(template.guard(), reads);
            }
            for (final CaptureBinding capture : template.captures()) {
                switch (capture.select()) {
                    case CaptureBinding.CaptureSource.Select select -> reads.add(select.select());
                    case CaptureBinding.CaptureSource.KeyValue keyValue -> {
                        reads.add(keyValue.keyRef());
                        reads.add(keyValue.valueRef());
                    }
                    default -> {
                    }
                }
            }
            collectReads(template.body(), reads);
            for (final RefExpression read : reads) {
                checkRead(read, writable, template.name());
            }
        }
    }

    /** Every name a body can write: variable binds, transform binds, call arguments. */
    private static void collectWrites(final List<OutputNode> body, final Set<String> writable) {
        for (final OutputNode node : body) {
            switch (node) {
                case OutputNode.Variable value -> {
                    writable.add(value.name());
                    collectWrites(value.body(), writable);
                }
                case OutputNode.If value -> collectWrites(value.then(), writable);
                case OutputNode.Choose value -> {
                    value.when().forEach(branch -> collectWrites(branch.body(), writable));
                    collectWrites(value.otherwise(), writable);
                }
                case OutputNode.Switch value -> {
                    value.cases().forEach(c -> collectWrites(c.body(), writable));
                    collectWrites(value.defaultBody(), writable);
                }
                case OutputNode.ApplyTemplates value ->
                        value.directive().withParam().forEach(param -> writable.add(param.name()));
                case OutputNode.CallTemplate value ->
                        value.withParam().forEach(param -> writable.add(param.name()));
                case OutputNode.ValueMap value -> addBind(value.name(), writable);
                case OutputNode.Translate value -> addBind(value.name(), writable);
                case OutputNode.StringJoin value -> addBind(value.name(), writable);
                case OutputNode.Replace value -> addBind(value.name(), writable);
                case OutputNode.LowerCase value -> addBind(value.name(), writable);
                case OutputNode.UpperCase value -> addBind(value.name(), writable);
                case OutputNode.NormalizeSpace value -> addBind(value.name(), writable);
                case OutputNode.Trim value -> addBind(value.name(), writable);
                case OutputNode.Substring value -> addBind(value.name(), writable);
                case OutputNode.Tokenize value -> addBind(value.name(), writable);
                case OutputNode.Number value -> addBind(value.name(), writable);
                case OutputNode.Add value -> addBind(value.name(), writable);
                case OutputNode.Subtract value -> addBind(value.name(), writable);
                case OutputNode.Multiply value -> addBind(value.name(), writable);
                case OutputNode.Divide value -> addBind(value.name(), writable);
                case OutputNode.Mod value -> addBind(value.name(), writable);
                case OutputNode.Round value -> addBind(value.name(), writable);
                case OutputNode.Floor value -> addBind(value.name(), writable);
                case OutputNode.Ceiling value -> addBind(value.name(), writable);
                case OutputNode.Abs value -> addBind(value.name(), writable);
                case OutputNode.StringLength value -> addBind(value.name(), writable);
                case OutputNode.SubstringBefore value -> addBind(value.name(), writable);
                case OutputNode.SubstringAfter value -> addBind(value.name(), writable);
                case OutputNode.StartsWith value -> addBind(value.name(), writable);
                case OutputNode.EndsWith value -> addBind(value.name(), writable);
                case OutputNode.Contains value -> addBind(value.name(), writable);
                case OutputNode.FormatNumber value -> addBind(value.name(), writable);
                case OutputNode.ParseDate value -> addBind(value.name(), writable);
                case OutputNode.FormatDate value -> addBind(value.name(), writable);
                default -> {
                }
            }
        }
    }

    private static void addBind(final String name, final Set<String> writable) {
        if (name != null) {
            writable.add(name);
        }
    }

    /** Every expression a body can read. */
    private static void collectReads(final List<OutputNode> body, final List<RefExpression> reads) {
        for (final OutputNode node : body) {
            switch (node) {
                case OutputNode.Text ignored -> {
                }
                case OutputNode.ValueOf value -> reads.add(value.select());
                case OutputNode.EmitError value -> reads.add(value.message());
                case OutputNode.If value -> {
                    collectConditionRefs(value.test(), reads);
                    collectReads(value.then(), reads);
                }
                case OutputNode.Choose value -> {
                    for (final OutputNode.WhenBranch branch : value.when()) {
                        collectConditionRefs(branch.test(), reads);
                        collectReads(branch.body(), reads);
                    }
                    collectReads(value.otherwise(), reads);
                }
                case OutputNode.Switch value -> {
                    reads.add(value.select());
                    value.cases().forEach(c -> collectReads(c.body(), reads));
                    collectReads(value.defaultBody(), reads);
                }
                case OutputNode.ApplyTemplates value -> {
                    reads.add(value.directive().select());
                    value.directive().withParam().forEach(param -> reads.add(param.value()));
                }
                case OutputNode.CallTemplate value ->
                        value.withParam().forEach(param -> reads.add(param.value()));
                case OutputNode.Variable value -> collectReads(value.body(), reads);
                case OutputNode.ValueMap value -> reads.add(value.select());
                case OutputNode.Translate value -> reads.addAll(value.select());
                case OutputNode.StringJoin value -> reads.addAll(value.select());
                case OutputNode.Replace value -> reads.addAll(value.select());
                case OutputNode.LowerCase value -> reads.addAll(value.select());
                case OutputNode.UpperCase value -> reads.addAll(value.select());
                case OutputNode.NormalizeSpace value -> reads.addAll(value.select());
                case OutputNode.Trim value -> reads.addAll(value.select());
                case OutputNode.Substring value -> reads.addAll(value.select());
                case OutputNode.Tokenize value -> reads.addAll(value.select());
                case OutputNode.Number value -> reads.addAll(value.select());
                case OutputNode.Add value -> reads.addAll(value.select());
                case OutputNode.Subtract value -> reads.addAll(value.select());
                case OutputNode.Multiply value -> reads.addAll(value.select());
                case OutputNode.Divide value -> reads.addAll(value.select());
                case OutputNode.Mod value -> reads.addAll(value.select());
                case OutputNode.Round value -> reads.addAll(value.select());
                case OutputNode.Floor value -> reads.addAll(value.select());
                case OutputNode.Ceiling value -> reads.addAll(value.select());
                case OutputNode.Abs value -> reads.addAll(value.select());
                case OutputNode.StringLength value -> reads.addAll(value.select());
                case OutputNode.SubstringBefore value -> reads.addAll(value.select());
                case OutputNode.SubstringAfter value -> reads.addAll(value.select());
                case OutputNode.StartsWith value -> reads.addAll(value.select());
                case OutputNode.EndsWith value -> reads.addAll(value.select());
                case OutputNode.Contains value -> reads.addAll(value.select());
                case OutputNode.FormatNumber value -> reads.addAll(value.select());
                case OutputNode.ParseDate value -> {
                    reads.addAll(value.select());
                    if (value.reference() != null) {
                        reads.add(value.reference());
                    }
                }
                case OutputNode.FormatDate value -> reads.addAll(value.select());
            }
        }
    }

    private static void collectConditionRefs(final Condition condition, final List<RefExpression> reads) {
        switch (condition) {
            case Condition.Compare value -> {
                if (value.left().ref() != null) {
                    reads.add(value.left().ref());
                }
                if (value.right().ref() != null) {
                    reads.add(value.right().ref());
                }
            }
            case Condition.Matches value -> reads.add(value.select());
            case Condition.Contains value -> reads.add(value.select());
            case Condition.StartsWith value -> reads.add(value.select());
            case Condition.Exists value -> reads.add(value.select());
            case Condition.And value -> value.conditions().forEach(c -> collectConditionRefs(c, reads));
            case Condition.Or value -> value.conditions().forEach(c -> collectConditionRefs(c, reads));
            case Condition.Not value -> collectConditionRefs(value.condition(), reads);
        }
    }

    private static void checkRead(final RefExpression read,
                                  final Set<String> writable,
                                  final String templateName) {
        if (read == null) {
            return;
        }
        for (final RefExpression.RefPart part : read.parts()) {
            if (part instanceof RefExpression.RefPart.Capture capture) {
                if (capture.varId() != null && !writable.contains(capture.varId())) {
                    throw new ConfigException("Template '" + templateName
                            + "' reads '" + capture.varId() + "', which nothing writes —"
                            + " no capture, variable, transform bind or parameter has that"
                            + " name. A misspelt name would otherwise read as absent for"
                            + " ever.");
                }
                if (capture.matchIndex() != null && capture.matchIndex().varRef() != null
                    && !writable.contains(capture.matchIndex().varRef())) {
                    throw new ConfigException("Template '" + templateName
                            + "' indexes by '" + capture.matchIndex().varRef()
                            + "', which nothing writes.");
                }
            }
        }
    }

    /**
     * Design/17 §7's bump warning, once per configuration: below version 5 a
     * {@code substring} start is 0-based, from version 5 it is 1-based, and a version bump
     * silently changes every <b>explicit</b> one — so the configurations a bump would change
     * are told so while they still say the old thing. An omitted start means "from the
     * beginning" under either base and is bump-safe, so it earns no warning: the phase 6
     * migration proved that with four byte-identical goldens, and a warning that cries on
     * safe configurations teaches authors to ignore it.
     */
    private static void substringVersionCheck(final Project project, final List<Message> warnings) {
        if (project.version() >= 5) {
            return;
        }
        int count = 0;
        for (final Template template : project.templates()) {
            count += countSubstrings(template.body());
        }
        if (count > 0) {
            warnings.add(new Message(Severity.WARNING, "This configuration's " + count
                    + " substring instruction" + (count == 1 ? " reads" : "s read")
                    + " start as 0-based; version 5 reads it as 1-based. Add 1 to each start"
                    + " when bumping the version."));
        }
    }

    private static int countSubstrings(final List<OutputNode> body) {
        int count = 0;
        for (final OutputNode node : body) {
            count += switch (node) {
                case OutputNode.Substring value -> value.start() != null ? 1 : 0;
                case OutputNode.If value -> countSubstrings(value.then());
                case OutputNode.Choose value -> value.when().stream()
                        .mapToInt(branch -> countSubstrings(branch.body())).sum()
                        + countSubstrings(value.otherwise());
                case OutputNode.Switch value -> value.cases().stream()
                        .mapToInt(c -> countSubstrings(c.body())).sum()
                        + countSubstrings(value.defaultBody());
                case OutputNode.Variable value -> countSubstrings(value.body());
                default -> 0;
            };
        }
        return count;
    }

    /**
     * Design/17 §8's comparison checks. The lint: a typed literal compared against an uncast
     * reference is the strict rule's one foot-gun — captures are text, so the comparison is
     * false on every record, silently — and it is statically visible, so it draws a warning
     * (D36's tier: warnings until a lint can prove confusion rather than suspect it).
     */
    private static void comparisonChecks(final Project project, final List<Message> warnings) {
        for (final Template template : project.templates()) {
            if (template.guard() != null) {
                checkCondition(template.guard(), template.name(), warnings);
            }
            collectConditions(template.body(), template.name(), warnings);
        }
    }

    private static void collectConditions(final List<OutputNode> body,
                                          final String templateName,
                                          final List<Message> warnings) {
        for (final OutputNode node : body) {
            switch (node) {
                case OutputNode.If value -> {
                    checkCondition(value.test(), templateName, warnings);
                    collectConditions(value.then(), templateName, warnings);
                }
                case OutputNode.Choose value -> {
                    for (final OutputNode.WhenBranch branch : value.when()) {
                        checkCondition(branch.test(), templateName, warnings);
                        collectConditions(branch.body(), templateName, warnings);
                    }
                    collectConditions(value.otherwise(), templateName, warnings);
                }
                case OutputNode.Switch value -> {
                    for (final OutputNode.SwitchCase switchCase : value.cases()) {
                        collectConditions(switchCase.body(), templateName, warnings);
                    }
                    collectConditions(value.defaultBody(), templateName, warnings);
                }
                case OutputNode.Variable value -> collectConditions(value.body(), templateName, warnings);
                default -> {
                }
            }
        }
    }

    private static void checkCondition(final Condition condition,
                                       final String templateName,
                                       final List<Message> warnings) {
        switch (condition) {
            case Condition.Compare value -> {
                if (mismatch(value.left(), value.right()) || mismatch(value.right(), value.left())) {
                    warnings.add(new Message(Severity.WARNING, "Template '" + templateName
                            + "' compares a typed literal against an uncast reference:"
                            + " captures are text, so this is false on every record."
                            + " Add as: \"number\" (or the intended cast) to the reference"
                            + " if a typed comparison is meant."));
                }
            }
            case Condition.And value ->
                    value.conditions().forEach(child -> checkCondition(child, templateName, warnings));
            case Condition.Or value ->
                    value.conditions().forEach(child -> checkCondition(child, templateName, warnings));
            case Condition.Not value -> checkCondition(value.condition(), templateName, warnings);
            default -> {
            }
        }
    }

    /** A typed literal on one side, an uncast reference on the other. */
    private static boolean mismatch(final Condition.Operand literalSide, final Condition.Operand refSide) {
        return literalSide.literal() != null
               && !(literalSide.literal() instanceof Condition.Literal.Text)
               && literalSide.as() == null
               && refSide.ref() != null
               && refSide.as() == null;
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
