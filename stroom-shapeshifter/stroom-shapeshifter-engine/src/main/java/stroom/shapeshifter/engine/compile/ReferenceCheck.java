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
import stroom.shapeshifter.engine.config.Cast;
import stroom.shapeshifter.engine.config.Condition;
import stroom.shapeshifter.engine.config.ConfigException;
import stroom.shapeshifter.engine.config.Declaration;
import stroom.shapeshifter.engine.config.EngineVars;
import stroom.shapeshifter.engine.config.MatchExpression;
import stroom.shapeshifter.engine.config.OutputNode;
import stroom.shapeshifter.engine.config.Project;
import stroom.shapeshifter.engine.config.RefExpression;
import stroom.shapeshifter.engine.config.Template;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One walk over a configuration's bodies for every check that reads what they reference.
 *
 * <p>Some decide as they go — the comparison lint and the substring count need nothing but
 * the node in front of them. The rest cannot: a read in the first template may name
 * something the last one writes, a call chain may reach a template not yet seen, so reads,
 * names, collection operations and calls are collected and judged against the finished
 * configuration in {@link #report()}.
 *
 * <p>{@link #visit} is deliberately <b>exhaustive</b> — no {@code default} arm. An
 * instruction added to the vocabulary without being considered here is a compile error
 * rather than a check that silently ignores it; that is the point, and the microseconds
 * one walk saves are incidental.
 */
final class ReferenceCheck {

    private record Read(String templateName, RefExpression ref, boolean asText) {

    }

    /** A use of a name, by a template. */
    private record NamedUse(String templateName, String name) {

    }

    private final Project project;
    private final List<Message> warnings;
    private final Set<String> writable = new HashSet<>();
    private final List<Read> reads = new ArrayList<>();

    /**
     * Design 35: every name a configuration binds or reads is declared once, with a type.
     * Declarations are collected first and judged in {@link #report()}, because a name is
     * declared on the template whose executions it lives for, which may be far from where
     * it is bound.
     */
    private final Map<String, Declaration> declarations = new HashMap<>();
    private final Map<String, String> declaredIn = new HashMap<>();
    /**
     * Names a block declares in place — a parameter, a loop's {@code as} — which are
     * declarations already, scoped to that block (design 35 §4), so no separate one is owed.
     * Held per template, because the scope is the block: a parameter of one template says
     * nothing about a name in another.
     */
    private final Set<NamedUse> implicit = new HashSet<>();
    private final List<NamedUse> binds = new ArrayList<>();
    /** Names a key-value capture puts its pairs into: they must be declared as maps. */
    private final List<NamedUse> mapNames = new ArrayList<>();

    private int explicitSubstringStarts;
    private String templateName;

    /**
     * E37: the document template's body runs against no match — under design 23 the input
     * is the windows, and the body is split around its apply-templates — so a capture read
     * there is empty forever. The compiler can see it, so it refuses it by name, as it
     * refuses captures on an eater; the apply-templates select is the one place a group
     * may be named, because it is the idiom that hands the input to a mode and is not read.
     * A named template the document template calls runs over the same no-match, so the
     * refusal follows call-template edges from the document template (E37).
     */
    private boolean inDocumentTemplate;
    private boolean inApplySelect;
    private final Set<String> ownCaptures = new HashSet<>();
    /** This template's captures that declare a kind (design 25 §9), by name. */
    private final Map<String, Cast> ownCasts = new HashMap<>();
    private final List<String> documentTemplates = new ArrayList<>();
    private final Map<String, Set<String>> callsByTemplate = new HashMap<>();
    /** The first match read in each template, for the message that names it. */
    private final Map<String, String> matchReadByTemplate = new HashMap<>();

    /**
     * An operation on a collection named by a bare reference, with the types it accepts
     * (design 35 §5): judged against the declaration in {@link #report()}. A collection
     * reached through an accessor is a run-time fact and is not here.
     */
    private record TypedUse(String templateName, String name, String verb, Set<Declaration.Type> accepts) {

    }

    private final List<TypedUse> typedUses = new ArrayList<>();

    /** Whether the reference being read must produce text — a collection there is refused. */
    private boolean asText = true;

    /** How many {@code for-each} bodies enclose the node being visited. */
    private int iterationDepth;

    /** Whether the reference being read belongs to a sort key rather than to a body. */
    private boolean inSortKey;

    /** How many {@code for-each-group} bodies enclose the node being visited. */
    private int groupDepth;

    ReferenceCheck(final Project project, final List<Message> warnings) {
        this.project = project;
        this.warnings = warnings;
    }

    /**
     * Note that a name is bound: a capture, a variable, a transform's target, a put into a
     * scalar. Every such site comes through here, so the rule that a bound name is a declared
     * name is judged once, in {@link #report()}, against every declaration in the configuration.
     */
    private void bind(final String name) {
        writable.add(name);
        binds.add(new NamedUse(templateName, name));
    }

    /**
     * A block's own declaration — a parameter, an argument, a loop's {@code as} — which is a
     * declaration in place, scoped to the block (design 35 §4), so it owes no other.
     */
    private void bindImplicit(final String name) {
        writable.add(name);
        implicit.add(new NamedUse(templateName, name));
    }

    /** Whether the template declares the name in place — see {@link #bindImplicit}. */
    private boolean implicitIn(final String template, final String name) {
        return implicit.contains(new NamedUse(template, name));
    }

    /**
     * Walk one template, once, in authored order, before {@link #report()}: guard, then
     * captures, then body, which is the order that decides which of two unknown names a
     * template reports.
     */
    void template(final Template template) {
        templateName = template.name();
        inDocumentTemplate = template.match() instanceof MatchExpression.Source;
        if (inDocumentTemplate) {
            documentTemplates.add(template.name());
        }
        for (final Declaration declaration : template.declarations()) {
            final String before = declaredIn.putIfAbsent(declaration.name(), template.name());
            if (before != null) {
                throw new ConfigException("Template '" + template.name() + "' declares '"
                        + declaration.name() + "', which template '" + before + "' already"
                        + " declares. A name is declared once: a second declaration would be a"
                        + " second variable under the same name, and a reference could not say"
                        + " which it meant.");
            }
            declarations.put(declaration.name(), declaration);
        }
        ownCaptures.clear();
        ownCasts.clear();
        for (final CaptureBinding capture : template.captures()) {
            ownCaptures.add(capture.name());
            if (capture.as() != null && capture.as() != Cast.STRING) {
                ownCasts.put(capture.name(), capture.as());
            }
        }
        if (template.guard() != null) {
            condition(template.guard());
        }
        for (final CaptureBinding capture : template.captures()) {
            bind(capture.name());
            if (!(capture.select() instanceof CaptureBinding.CaptureSource.KeyValue)) {
                // A capture assigns a scalar or appends to a list (design 35 §4): into a map or a
                // set it would overwrite the collection with one value.
                typedUses.add(new TypedUse(templateName, capture.name(), "captures into",
                        Set.of(Declaration.Type.SCALAR, Declaration.Type.LIST)));
            }
            switch (capture.select()) {
                case CaptureBinding.CaptureSource.Select select -> read(select.select());
                case CaptureBinding.CaptureSource.KeyValue keyValue -> {
                    // The pairs go into the map the capture names (design 35 §5), so every read
                    // of one is a get on a name the configuration knows, and nothing arrives
                    // from the data that a declaration did not foresee.
                    mapNames.add(new NamedUse(templateName, capture.name()));
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
            bindImplicit(declared.name());
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
                bind(value.name());
                body(value.body());
            }
            case OutputNode.Element value -> body(value.body());
            case OutputNode.Attribute value -> body(value.body());
            case OutputNode.Namespace ignored -> {
            }
            // A call's arguments are values: a SEQUENCE position receives a whole collection,
            // which the function's signature settles at compile time and its casts at run time.
            case OutputNode.Call value -> {
                value.select().forEach(this::value);
                if (value.name() != null) {
                    bind(value.name());
                }
            }
            // Tokenize with a name fills a list with the pieces (design/17 §16.4).
            case OutputNode.Tokenize value -> {
                transform(value.select(), value.name());
                if (value.name() != null) {
                    typedUses.add(new TypedUse(templateName, value.name(), "tokenizes into",
                            Set.of(Declaration.Type.LIST)));
                }
            }
            case OutputNode.Substring value -> {
                // Only an explicit start moves at the version gate; an omitted one means
                // "from the beginning" under either base (design/17 §7).
                if (value.start() != null) {
                    explicitSubstringStarts++;
                }
                transform(value.select(), value.name());
            }
            case OutputNode.ParseDate value -> {
                transform(value.select(), value.name());
                read(value.reference());
            }
            // Every other transform: its selects read, its name bound when it has one.
            case OutputNode.Transform value -> transform(value.select(), value.name());
            // The mutations (design 35 §5): the target is a collection, read as one; the value
            // may be a collection too, which is how nesting is built.
            case OutputNode.Append value -> {
                target(value.target(), "appends to", Declaration.Type.LIST);
                value(value.select());
            }
            case OutputNode.Insert value -> {
                target(value.target(), "inserts into", Declaration.Type.LIST);
                read(value.position());
                value(value.select());
            }
            case OutputNode.Put value -> {
                if (value.key() != null) {
                    target(value.target(), "puts at a key of", Declaration.Type.LIST, Declaration.Type.MAP);
                    read(value.key());
                } else {
                    target(value.target(), "puts into", Declaration.Type.SET, Declaration.Type.SCALAR);
                    final String name = value.target().bareName();
                    if (name != null) {
                        // A put into a scalar is the assignment that binds it.
                        bind(name);
                    }
                }
                value(value.select());
            }
            case OutputNode.Remove value -> {
                target(value.target(), "removes from", Declaration.Type.LIST, Declaration.Type.MAP,
                        Declaration.Type.SET);
                read(value.key());
            }
            case OutputNode.Clear value ->
                    target(value.target(), "clears", Declaration.Type.LIST, Declaration.Type.MAP, Declaration.Type.SET);
            case OutputNode.ForEachGroup value -> {
                target(value.select(), "groups", Declaration.Type.LIST);
                // index() is bound while the key is resolved, so the key counts as
                // inside the iteration — but *not* yet inside the group: the key is what
                // forms it, so reading this grouping's own names there is the same
                // mistake as reading a position in a sort key.
                iterationDepth++;
                read(value.groupBy());
                groupDepth++;
                body(value.body());
                groupDepth--;
                iterationDepth--;
            }
            case OutputNode.ForEach value -> {
                target(value.select(), "walks", Declaration.Type.LIST, Declaration.Type.MAP, Declaration.Type.SET);
                if (value.as() != null) {
                    bindImplicit(value.as());
                }
                if (value.asKey() != null) {
                    bindImplicit(value.asKey());
                }
                // The sort keys are evaluated with index() bound, so they count as inside
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

    /**
     * A collection an operation acts on, as a reference: read as a collection rather than as
     * text, and — when it is a bare declared name — judged against the types the operation
     * accepts. Reached through an accessor, its type is a run-time fact (design 35 §5).
     */
    private void target(final RefExpression ref, final String verb, final Declaration.Type... accepts) {
        if (ref.parts().size() == 1 && ref.parts().getFirst() instanceof RefExpression.RefPart.Counter counter
            && mutation(verb)) {
            // What a function answers is the engine's — group()'s members are the frame's list —
            // and a mutation of it would change what the engine says without any variable
            // changing. Read it, walk it, copy it into a declared list; do not act on it.
            throw new ConfigException("Template '" + templateName + "' " + verb + " " + counter.counter().spelling()
                    + ", which is a function, not a variable: copy it into a declared list to change it.");
        }
        final String name = ref.bareName();
        if (name != null) {
            // A bare name is judged as a typed use, not read: the operation is the writer.
            typedUses.add(new TypedUse(templateName, name, verb, Set.of(accepts)));
            return;
        }
        value(ref);
    }

    /** Whether a target verb changes its collection, as opposed to walking or reading it. */
    private static boolean mutation(final String verb) {
        return verb.startsWith("appends") || verb.startsWith("inserts") || verb.startsWith("puts")
               || verb.startsWith("removes") || verb.startsWith("clears");
    }

    /** A reference read for its value, which may be a collection. */
    private void value(final RefExpression ref) {
        final boolean was = asText;
        asText = false;
        read(ref);
        asText = was;
    }

    /** What each accessor reads (design 35 §5's table). */
    private static Declaration.Type[] accepts(final RefExpression.RefPart.Accessor.Kind kind) {
        return switch (kind) {
            case GET -> new Declaration.Type[]{Declaration.Type.LIST, Declaration.Type.MAP};
            case SIZE, CONTAINS -> new Declaration.Type[]{Declaration.Type.LIST, Declaration.Type.MAP,
                    Declaration.Type.SET};
            case LAST, HEAD -> new Declaration.Type[]{Declaration.Type.LIST};
            case KEYS -> new Declaration.Type[]{Declaration.Type.MAP};
            case VALUES -> new Declaration.Type[]{Declaration.Type.MAP, Declaration.Type.SET};
            case SUM, AVG, MIN, MAX -> new Declaration.Type[]{Declaration.Type.LIST, Declaration.Type.SET};
        };
    }


    /** The shape almost every instruction has: some selects read, an optional name bound. */
    private void transform(final List<RefExpression> select, final String name) {
        select.forEach(this::read);
        if (name != null) {
            bind(name);
        }
    }

    /**
     * Design/17 §8's lint, decided in place: a typed literal compared against an uncast
     * reference is the strict rule's one foot-gun — a capture is text unless it declares a
     * kind (design 25 §9), so the comparison is false on every record, silently — and it is
     * statically visible, so it draws a warning (D36's tier: warnings until a lint can prove
     * confusion rather than suspect it). The mirror image since D50 — a text literal against a
     * capture that declares a kind — is the same foot-gun the other way round. Conditions
     * also carry reads, which are collected on the same visit.
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
                final Cast declared = textAgainstDeclared(value.left(), value.right());
                final Cast declaredOther = textAgainstDeclared(value.right(), value.left());
                if (declared != null || declaredOther != null) {
                    warnings.add(new Message(Severity.WARNING, "Template '" + templateName
                            + "' compares a text literal against a capture declared as "
                            + (declared != null ? declared : declaredOther).name()
                                    .toLowerCase(Locale.ROOT)
                            + ": the kinds differ, so this is false on every record."
                            + " Read the literal with the same as, or drop the capture's."));
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
     * {@code position()}, so these read false on every record. Inside one they are exact.
     */
    private void positional(final String spelling) {
        if (iterationDepth == 0) {
            warnings.add(new Message(Severity.WARNING, "Template '" + templateName
                    + "' tests " + spelling + " outside any for-each: nothing sets a"
                    + " position there, so it is false on every record."));
        }
    }

    /** A typed literal on one side, an uncast reference to no declared kind on the other. */
    private boolean mismatch(final Condition.Operand literalSide,
                             final Condition.Operand refSide) {
        return literalSide.literal() != null
               && !(literalSide.literal() instanceof Condition.Literal.Text)
               && literalSide.as() == null
               && refSide.ref() != null
               && refSide.as() == null
               && declaredKind(refSide.ref()) == null;
    }

    /** A text literal against an uncast reference to a capture that declares a kind: the kind. */
    private Cast textAgainstDeclared(final Condition.Operand literalSide,
                                     final Condition.Operand refSide) {
        return literalSide.literal() instanceof Condition.Literal.Text
               && literalSide.as() == null
               && refSide.ref() != null
               && refSide.as() == null
                ? declaredKind(refSide.ref())
                : null;
    }

    /** The kind a single-part reference to one of this template's captures declares, or null. */
    private Cast declaredKind(final RefExpression ref) {
        if (ref.parts().size() == 1
            && ref.parts().getFirst() instanceof RefExpression.RefPart.Capture capture
            && capture.varId() != null) {
            return ownCasts.get(capture.varId());
        }
        return null;
    }

    private void read(final RefExpression ref) {
        if (ref == null) {
            return;
        }
        reads.add(new Read(templateName, ref, asText));
        // An accessor's collection and arguments are references of their own: the collection
        // read as one, the arguments as values.
        for (final RefExpression.RefPart part : ref.parts()) {
            if (part instanceof RefExpression.RefPart.Accessor accessor) {
                target(accessor.of(), accessor.kind().spelling() + " reads", accepts(accessor.kind()));
                if (accessor.key() != null) {
                    // A key or position is a scalar, so it is read as text is: a collection
                    // there is refused where it is written.
                    read(accessor.key());
                }
                if (accessor.orElse() != null) {
                    value(accessor.orElse());
                }
            }
        }
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
                            + " reference would be empty forever. Only the apply-templates"
                            + " select may name a group there.");
                }
                if (!inDocumentTemplate) {
                    matchReadByTemplate.putIfAbsent(templateName, read);
                }
            }
        }
        // The functions' lints: on a function part, and on the function an index rule reads.
        for (final RefExpression.RefPart part : ref.parts()) {
            final RefExpression.MatchIndex index = switch (part) {
                case RefExpression.RefPart.Capture capture -> capture.matchIndex();
                case RefExpression.RefPart.Counter counter -> counter.matchIndex();
                case RefExpression.RefPart.Text ignored -> null;
                case RefExpression.RefPart.Accessor ignored -> null;
            };
            if (part instanceof RefExpression.RefPart.Counter counter) {
                function(counter.counter());
            }
            if (index != null && index.counter() != null) {
                function(index.counter());
            }
        }
    }

    /** One function read, wherever it sits: the hazards are about where, not about what. */
    private void function(final EngineVars function) {
        if (inSortKey) {
            sortKeyPositional(function);
        }
        if (iterationDepth == 0) {
            iterationOnly(function);
        }
        if (groupDepth == 0) {
            groupOnly(function);
        }
    }

    /**
     * A sort key decides the order, so it cannot ask where an entry will land: nothing
     * has a position until the keys have been compared. {@code index()} is fine there —
     * it names the record, which is known — and is how a key reaches a parallel list.
     */
    private void sortKeyPositional(final EngineVars function) {
        if (function == EngineVars.POSITION || function == EngineVars.LAST) {
            warnings.add(new Message(Severity.WARNING, "Template '" + templateName
                    + "' reads " + function.spelling() + " in a sort key, which decides the order: this"
                    + " entry has no position until the keys have been compared, so this"
                    + " reads the enclosing iteration's, if there is one."));
        }
    }

    /** The grouping names carry the iteration names' hazard, outside a grouping. */
    private void groupOnly(final EngineVars function) {
        if (EngineVars.GROUP_ONLY.contains(function)) {
            warnings.add(new Message(Severity.WARNING, "Template '" + templateName
                    + "' reads " + function.spelling() + " outside any for-each-group, where nothing"
                    + " sets it."));
        }
    }

    /**
     * The same hazard the positional conditions carry, on the variables that carry it
     * too: outside an iteration nothing sets these, and absence here is
     * quiet — {@code position()} writes nothing, and an index reference falls back to
     * the first entry, which is a wrong value rather than no value.
     */
    private void iterationOnly(final EngineVars function) {
        if (EngineVars.ITERATION_ONLY.contains(function)) {
            warnings.add(new Message(Severity.WARNING, "Template '" + templateName
                    + "' reads " + function.spelling() + " outside any for-each, where nothing sets it."));
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
        {
            // Design 35's rule: a bound name is a declared name.
            for (final NamedUse bound : binds) {
                if (!declarations.containsKey(bound.name()) && !implicitIn(bound.templateName(), bound.name())) {
                    throw new ConfigException("Template '" + bound.templateName() + "' binds '"
                            + bound.name() + "', which no declaration names. Declare it, with"
                            + " its type, on the template whose executions it should live for.");
                }
            }
            for (final Read read : reads) {
                checkRead(read);
            }
            // An operation that disagrees with the declared type (design 35 §5): every
            // collection operation on a bare name, judged against what the name holds.
            for (final TypedUse use : typedUses) {
                if (implicitIn(use.templateName(), use.name())) {
                    continue;
                }
                declared(use.templateName(), use.name(), use.verb());
                final Declaration declaration = declarations.get(use.name());
                final Declaration.Type type = declaration != null
                        ? declaration.type()
                        : Declaration.Type.SCALAR;
                if (!use.accepts().contains(type)) {
                    throw new ConfigException("Template '" + use.templateName() + "' " + use.verb()
                            + " '" + use.name() + "', which is declared as a "
                            + type.name().toLowerCase(Locale.ROOT) + "; that takes "
                            + use.accepts().stream().map(t -> t.name().toLowerCase(Locale.ROOT))
                                    .sorted().collect(java.util.stream.Collectors.joining(" or ")) + ".");
                }
            }
            for (final NamedUse use : mapNames) {
                requireType(use, Declaration.Type.MAP, "puts pairs into");
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
                        + " the reference would be empty forever.");
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
     * work and quietly reads absence forever.
     */
    private void checkRead(final Read read) {
        for (final RefExpression.RefPart part : read.ref().parts()) {
            if (part instanceof RefExpression.RefPart.Capture capture) {
                if (capture.varId() != null) {
                    declaredAndWritten(read.templateName(), capture.varId(), "reads");
                    final Declaration declaration = declarations.get(capture.varId());
                    // A name bound in place — a loop's as, a parameter — shadows a declaration
                    // of the same name and holds a scalar, whatever the declaration says.
                    final boolean collection = declaration != null && declaration.type() != Declaration.Type.SCALAR
                                               && !implicitIn(read.templateName(), capture.varId());
                    if (collection && capture.matchIndex() == null && read.asText()) {
                        // A collection has no text form, so a reference to the whole of one
                        // where text is wanted could only fail at run time. Where a value is
                        // wanted — appended, put, walked — the whole collection is the value.
                        throw new ConfigException("Template '" + read.templateName() + "' reads '"
                                + capture.varId() + "', which is declared as a "
                                + declaration.type().name().toLowerCase(Locale.ROOT)
                                + ", where text is wanted: read one entry of it with get or last.");
                    }
                    if (capture.matchIndex() != null) {
                        requireType(new NamedUse(read.templateName(), capture.varId()),
                                Declaration.Type.LIST, "indexes into");
                    }
                }
                if (capture.matchIndex() != null && capture.matchIndex().varRef() != null) {
                    declaredAndWritten(read.templateName(), capture.matchIndex().varRef(), "indexes by");
                }
            }
        }
    }

    /**
     * Design 35's two halves of an unknown reference: no declaration names it; nothing writes
     * it. The second half is for scalars — a declared collection nothing fills is legitimately
     * empty, and is walked zero times or sized at nothing.
     */
    private void declaredAndWritten(final String template, final String name, final String verb) {
        declared(template, name, verb);
        final Declaration declaration = declarations.get(name);
        if (declaration != null && declaration.type() != Declaration.Type.SCALAR) {
            return;
        }
        if (!writable.contains(name)) {
            throw new ConfigException("Template '" + template + "' " + verb + " '" + name
                    + "', which nothing writes — no capture, variable, transform bind or"
                    + " parameter has that name. A misspelt name would otherwise read as absent"
                    + " for ever.");
        }
    }

    /** The first half on its own: a mutation's target owes a declaration, and is itself the writer. */
    private void declared(final String template, final String name, final String verb) {
        if (!declarations.containsKey(name) && !implicitIn(template, name)) {
            throw new ConfigException("Template '" + template + "' " + verb + " '" + name
                    + "', which no declaration names. A misspelt name would otherwise read as"
                    + " absent for ever; declare it, with its type, on the template whose"
                    + " executions it should live for.");
        }
    }

    /** The declared type must be the one the operation needs; an undeclared name was refused already. */
    private void requireType(final NamedUse use, final Declaration.Type type, final String verb) {
        final Declaration declaration = declarations.get(use.name());
        if (declaration != null && declaration.type() != type) {
            throw new ConfigException("Template '" + use.templateName() + "' " + verb + " '"
                    + use.name() + "' as a " + type.name().toLowerCase(Locale.ROOT)
                    + ", but it is declared as a "
                    + declaration.type().name().toLowerCase(Locale.ROOT) + ".");
        }
    }
}
