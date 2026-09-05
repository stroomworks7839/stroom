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
import stroom.shapeshifter.engine.config.Condition;
import stroom.shapeshifter.engine.config.ConfigException;
import stroom.shapeshifter.engine.config.MatchExpression;
import stroom.shapeshifter.engine.config.OutputNode;
import stroom.shapeshifter.engine.config.Project;
import stroom.shapeshifter.engine.config.RefExpression;
import stroom.shapeshifter.engine.config.Template;
import stroom.shapeshifter.engine.exec.EngineVars;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One walk over a configuration's bodies for every check that reads what they reference.
 *
 * <p>Some decide as they go — the comparison lint and the substring count need nothing but
 * the node in front of them. The rest cannot: a read in the first template may name
 * something the last one writes, a call chain may reach a template not yet seen, so reads,
 * names, sequences, keys and calls are collected and judged against the finished
 * configuration in {@link #report()}.
 *
 * <p>{@link #visit} is deliberately <b>exhaustive</b> — no {@code default} arm. An
 * instruction added to the vocabulary without being considered here is a compile error
 * rather than a check that silently ignores it; that is the point, and the microseconds
 * one walk saves are incidental.
 */
final class ReferenceCheck {

    private record Read(String templateName, RefExpression ref) {

    }

    /**
     * A use of a name rather than of a reference — a sequence walked or appended to.
     * Its own record rather than a {@link Read} wrapping a synthetic reference: the
     * accessor that unwrapped one would have been a cast that only held for the entries
     * built that way.
     */
    private record NamedUse(String templateName, String name) {

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

    /**
     * E37: the document template's body runs against no match — under design 23 the input
     * is the windows, and the body is split around its apply-templates — so a capture read
     * there is empty for ever. The compiler can see it, so it refuses it by name, as it
     * refuses captures on an eater; the apply-templates select is the one place a group
     * may be named, because it is the idiom that hands the input to a mode and is not read.
     * A named template the document template calls runs over the same no-match, so the
     * refusal follows call-template edges from the document template (E37 audit).
     */
    private boolean inDocumentTemplate;
    private boolean inApplySelect;
    private final Set<String> ownCaptures = new HashSet<>();
    private final List<String> documentTemplates = new ArrayList<>();
    private final Map<String, Set<String>> callsByTemplate = new HashMap<>();
    /** The first match read in each template, for the message that names it. */
    private final Map<String, String> matchReadByTemplate = new HashMap<>();

    /** Sequence bookkeeping (design/16 §9): what is declared, what is captured, what is used. */
    private final Set<String> declaredSequences = new HashSet<>();
    private final Set<String> captureNames = new HashSet<>();
    private final List<NamedUse> sequenceUses = new ArrayList<>();
    private final List<NamedUse> appendTargets = new ArrayList<>();

    /** Keys are their own namespace, so they get their own declared set and use list. */
    private final Set<String> declaredKeys = new HashSet<>();
    private final List<NamedUse> keyUses = new ArrayList<>();

    /** How many {@code for-each} bodies enclose the node being visited. */
    private int iterationDepth;

    /** Whether the reference being read belongs to a sort key rather than to a body. */
    private boolean inSortKey;

    /** How many {@code for-each-group} bodies enclose the node being visited. */
    private int groupDepth;

    ReferenceCheck(final Project project, final List<Message> warnings) {
        this.project = project;
        this.warnings = warnings;
        // Every name the engine sets is writable by definition, named once in EngineVars
        // so that setting, reading and refusing cannot drift apart.
        writable.addAll(EngineVars.ALL);
    }

    void template(final Template template) {
        templateName = template.name();
        inDocumentTemplate = template.match() instanceof MatchExpression.Source;
        if (inDocumentTemplate) {
            documentTemplates.add(template.name());
        }
        ownCaptures.clear();
        for (final CaptureBinding capture : template.captures()) {
            ownCaptures.add(capture.name());
        }
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
                case CaptureBinding.CaptureSource.Group ignored -> {
                }
                case CaptureBinding.CaptureSource.Step ignored -> {
                }
                case CaptureBinding.CaptureSource.Field ignored -> {
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
                inApplySelect = true;
                read(value.directive().select());
                inApplySelect = false;
                for (final OutputNode.Param param : value.directive().withParam()) {
                    writable.add(param.name());
                    read(param.value());
                }
            }
            case OutputNode.CallTemplate value -> {
                callsByTemplate.computeIfAbsent(templateName, name -> new LinkedHashSet<>()).add(value.name());
                for (final OutputNode.Param param : value.withParam()) {
                    writable.add(param.name());
                    read(param.value());
                }
            }
            case OutputNode.Variable value -> {
                writable.add(value.name());
                body(value.body());
            }
            case OutputNode.Element value -> body(value.body());
            case OutputNode.Attribute value -> body(value.body());
            case OutputNode.Namespace ignored -> {
            }
            case OutputNode.ValueMap value -> transform(List.of(value.select()), value.name());
            case OutputNode.Translate value -> transform(value.select(), value.name());
            case OutputNode.StringJoin value -> transform(value.select(), value.name());
            case OutputNode.Call value -> transform(value.select(), value.name());
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
                appendTargets.add(new NamedUse(templateName, value.name()));
                writable.add(value.name());
                read(value.select());
            }
            // The folds name a sequence rather than referencing one, so they join the
            // same use list a for-each does — checked against what anything writes, not
            // against the reference rules.
            case OutputNode.Count value -> fold(value.select(), value.name());
            case OutputNode.Sum value -> fold(value.select(), value.name());
            case OutputNode.Avg value -> fold(value.select(), value.name());
            case OutputNode.Min value -> fold(value.select(), value.name());
            case OutputNode.Max value -> fold(value.select(), value.name());
            case OutputNode.DistinctValues value -> fold(value.select(), value.name());
            case OutputNode.Key value -> {
                declaredKeys.add(value.name());
                sequenceUses.add(new NamedUse(templateName, value.select()));
                // Like a grouping's key, resolved with __index bound.
                iterationDepth++;
                read(value.groupBy());
                iterationDepth--;
            }
            case OutputNode.KeyGet value -> {
                keyUses.add(new NamedUse(templateName, value.key()));
                read(value.select());
                writable.add(value.name());
            }
            case OutputNode.ForEachGroup value -> {
                sequenceUses.add(new NamedUse(templateName, value.select()));
                // __index is bound while the key is resolved, so the key counts as
                // inside the iteration — but *not* yet inside the group: the key is what
                // forms it, so reading this grouping's own names there is the same
                // mistake as reading a position in a sort key (phase 4 audit).
                iterationDepth++;
                read(value.groupBy());
                groupDepth++;
                body(value.body());
                groupDepth--;
                iterationDepth--;
            }
            case OutputNode.ForEach value -> {
                // Walking __group is only meaningful inside a grouping, and the sequence
                // check cannot see that: __group is writable everywhere, being a name the
                // engine sets.
                if (EngineVars.GROUP.equals(value.select()) && groupDepth == 0) {
                    warnings.add(new Message(Severity.WARNING, "Template '" + templateName
                            + "' walks " + EngineVars.GROUP + " outside any"
                            + " for-each-group, where nothing sets it."));
                }
                sequenceUses.add(new NamedUse(templateName, value.select()));
                if (value.as() != null) {
                    writable.add(value.as());
                }
                // The sort keys are evaluated with __index bound, so they count as inside
                // the iteration: a key reading it is correct, not the lint's hazard.
                iterationDepth++;
                inSortKey = true;
                value.sort().forEach(key -> read(key.by()));
                inSortKey = false;
                body(value.body());
                iterationDepth--;
            }
        }
    }

    /** A fold: the named sequence is used, and the result may bind a name of its own. */
    private void fold(final String select, final String name) {
        sequenceUses.add(new NamedUse(templateName, select));
        if (name != null) {
            writable.add(name);
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
        if (ref == null) {
            return;
        }
        reads.add(new Read(templateName, ref));
        for (final RefExpression.RefPart part : ref.parts()) {
            if (part instanceof RefExpression.RefPart.Capture capture
                && (capture.varId() == null || ownCaptures.contains(capture.varId()))) {
                final String read = capture.varId() == null
                        ? "capture group " + capture.group()
                        : "capture '" + capture.varId() + "'";
                if (inDocumentTemplate && !inApplySelect) {
                    throw new ConfigException("Template '" + templateName + "' reads " + read
                            + " in its body, but the document template has no match: its body"
                            + " runs once around the apply-templates, over no match, so the"
                            + " reference would be empty for ever. Only the apply-templates"
                            + " select may name a group there.");
                }
                if (!inDocumentTemplate) {
                    matchReadByTemplate.putIfAbsent(templateName, read);
                }
            }
        }
        if (inSortKey) {
            for (final RefExpression.RefPart part : ref.parts()) {
                if (part instanceof RefExpression.RefPart.Capture capture) {
                    sortKeyPositional(capture.varId());
                    if (capture.matchIndex() != null) {
                        sortKeyPositional(capture.matchIndex().varRef());
                    }
                }
            }
        }
        for (final RefExpression.RefPart part : ref.parts()) {
            if (part instanceof RefExpression.RefPart.Capture capture) {
                if (iterationDepth == 0) {
                    iterationOnly(capture.varId());
                    if (capture.matchIndex() != null) {
                        iterationOnly(capture.matchIndex().varRef());
                    }
                }
                if (groupDepth == 0) {
                    groupOnly(capture.varId());
                    if (capture.matchIndex() != null) {
                        groupOnly(capture.matchIndex().varRef());
                    }
                }
            }
        }
    }

    /**
     * A sort key decides the order, so it cannot ask where an entry will land: nothing
     * has a position until the keys have been compared. {@code __index} is fine there —
     * it names the record, which is known — and is how a key reaches a parallel store.
     */
    private void sortKeyPositional(final String name) {
        if (EngineVars.POSITION.equals(name) || EngineVars.LAST.equals(name)) {
            warnings.add(new Message(Severity.WARNING, "Template '" + templateName
                    + "' reads " + name + " in a sort key, which decides the order: this"
                    + " entry has no position until the keys have been compared, so this"
                    + " reads the enclosing iteration's, if there is one."));
        }
    }

    // The grouping names carry the iteration names' hazard, outside a grouping.
    private void groupOnly(final String name) {
        if (name != null && EngineVars.GROUP_ONLY.contains(name)) {
            warnings.add(new Message(Severity.WARNING, "Template '" + templateName
                    + "' reads " + name + " outside any for-each-group, where nothing"
                    + " sets it."));
        }
    }

    /**
     * The same hazard the positional conditions carry, on the variables that carry it
     * too (phase 1 audit): outside an iteration nothing sets these, and absence here is
     * quiet — {@code $__position} writes nothing, and an index reference falls back to
     * the first entry, which is a wrong value rather than no value.
     */
    private void iterationOnly(final String name) {
        if (name != null && EngineVars.ITERATION_ONLY.contains(name)) {
            warnings.add(new Message(Severity.WARNING, "Template '" + templateName
                    + "' reads " + name + " outside any for-each, where nothing sets it."));
        }
    }

    /**
     * What could only be decided once the whole configuration had been seen. The order is
     * a contract, because it decides which error a doubly faulty configuration reports:
     * the refusals first, the substring warning last.
     */
    void report() {
        for (final String document : documentTemplates) {
            refuseMatchReadsCalledFrom(document, document, new HashSet<>(), new StringBuilder());
        }
        if (referencesKnowable) {
            for (final Read read : reads) {
                checkRead(read);
            }
        }
        // Design/16 §9's two checks. An append to a name no sequence declares would
        // create the store in the innermost scope and lose it on the way out — a
        // configuration that appears to work and accumulates nothing.
        for (final NamedUse append : appendTargets) {
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
        // The same refusal an append gets, for the same reason: a lookup in a key that
        // nothing builds answers nothing, for ever, and looks like a configuration that
        // works.
        for (final NamedUse use : keyUses) {
            if (!declaredKeys.contains(use.name())) {
                throw new ConfigException("Template '" + use.templateName()
                        + "' looks up in key '" + use.name() + "', which no key builds.");
            }
        }
        for (final NamedUse use : sequenceUses) {
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
     * E37 through call-template: a named template the document template calls, directly or
     * through other calls, runs its body over the caller's no-match, so a match read in it is
     * as empty as one in the document template's own body, and is refused with the chain of
     * calls that reaches it. A template a matching template also calls is still refused —
     * the call from the document template is empty whoever else makes it.
     */
    private void refuseMatchReadsCalledFrom(final String document, final String caller,
                                            final Set<String> visited, final StringBuilder chain) {
        for (final String called : callsByTemplate.getOrDefault(caller, Set.of())) {
            if (!visited.add(called)) {
                continue;
            }
            final int mark = chain.length();
            chain.append(chain.isEmpty() ? " calls '" : ", which calls '").append(called).append('\'');
            final String read = matchReadByTemplate.get(called);
            if (read != null) {
                throw new ConfigException("Template '" + document + "'" + chain + ", which reads " + read
                        + ": a call from the document template's body runs over no match, so"
                        + " the reference would be empty for ever.");
            }
            refuseMatchReadsCalledFrom(document, called, visited, chain);
            chain.setLength(mark);
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
