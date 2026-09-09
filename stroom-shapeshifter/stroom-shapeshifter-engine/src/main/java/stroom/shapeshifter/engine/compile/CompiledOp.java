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

import stroom.shapeshifter.engine.Severity;
import stroom.shapeshifter.engine.config.Cast;
import stroom.shapeshifter.engine.config.Dispatch;
import stroom.shapeshifter.engine.config.OutputNode;
import stroom.shapeshifter.engine.config.OutputNode.ApplyDirective;
import stroom.shapeshifter.engine.config.Template;
import stroom.shapeshifter.engine.function.FunctionDefinition;
import stroom.shapeshifter.engine.value.Dates;
import stroom.shapeshifter.engine.value.Replacer;
import stroom.shapeshifter.engine.value.TypedValue;
import stroom.shapeshifter.regex.BytePattern;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * One instruction of a compiled body.
 *
 * <p>The authored {@link OutputNode} says what to do; this is the same instruction with every
 * decision that does not depend on a match already taken (D35). Literal text is bytes. A
 * reference is a {@link CompiledRef} that knows its strategy. A regex replace holds its
 * {@link BytePattern} instead of the text to look one up by. An apply knows whether its select
 * is the parent's whole content. The transform instructions collapse to one instruction
 * holding its function, parameters already bound.
 *
 * <p>Conditions are compiled too, since design 30: a {@code matches} test holds the pattern
 * it runs rather than the text to look one up by. Their references are not — that is E39,
 * deferred on design 29 phase 4's measurement.
 */
public sealed interface CompiledOp {

    /** Shared empty, so an unlinked or capture-free apply allocates no array. */
    String[] EMPTY_NAMES = new String[0];

    /** Write a literal: a UTF-8-tagged value; the sink's encoding decides its bytes (design 25). */
    record Text(TypedValue value) implements CompiledOp {

    }

    /** Write the value of a reference. */
    record ValueOf(CompiledRef ref) implements CompiledOp {

    }

    /** Run a body if a condition holds. */
    record If(CompiledCondition test, List<CompiledOp> then) implements CompiledOp {

    }

    /** Run the first branch whose condition holds. */
    record Choose(List<When> when, List<CompiledOp> otherwise) implements CompiledOp {

    }

    /** One branch of a {@link Choose}. */
    record When(CompiledCondition test, List<CompiledOp> body) {

    }

    /**
     * Run the branch whose value matches.
     *
     * @param cases      the branch each case value takes, first declaration winning where a
     *                   value is written twice — the order the authored scan had
     * @param defaultBody what runs when no case matches, which a select resolving to nothing
     *                   also reaches
     */
    record Switch(CompiledRef select,
                  Map<String, List<CompiledOp>> cases,
                  List<CompiledOp> defaultBody) implements CompiledOp {

    }

    final class Apply implements CompiledOp {

        private final ApplyDirective directive;
        private final CompiledRef select;
        private final boolean wholeParentContent;
        private final boolean locatable;
        private final Dispatch dispatch;
        private List<CompiledTemplate> candidates = List.of();
        private String[] recursiveShadow = EMPTY_NAMES;

        /**
         * Match templates against some content.
         *
         * @param directive          the authored directive — mode, limits, gates
         * @param select             the content reference, compiled
         * @param wholeParentContent whether the select means "the content this template is working
         *                           on", which is passed straight through rather than re-resolved
         * @param locatable          whether the dispatched content is still part of the input, and
         *                           can therefore be pointed at
         * @param dispatch           how the dispatched level runs — the directive's word, the
         *                           source default, or the version default, resolved once (D36)
         */
        Apply(final ApplyDirective directive,
              final CompiledRef select,
              final boolean wholeParentContent,
              final boolean locatable,
              final Dispatch dispatch) {
            this.directive = directive;
            this.select = select;
            this.wholeParentContent = wholeParentContent;
            this.locatable = locatable;
            this.dispatch = dispatch;
        }

        /**
         * Bind the templates this apply dispatches to, once the whole project has compiled.
         *
         * <p>A body compiles before every template exists, so the mode cannot be resolved where
         * the op is built — which is why design 29 phase 1 left this to phase 3. The ops are
         * collected as they are made and linked when the list is complete, rather than found
         * again by walking the compiled bodies, so no nesting can hide one.
         */
        void link(final List<CompiledTemplate> candidates) {
            this.candidates = candidates;
            final List<String> shadow = new ArrayList<>();
            for (final CompiledTemplate candidate : candidates) {
                shadow.addAll(Arrays.asList(candidate.captureNames()));
            }
            this.recursiveShadow = shadow.toArray(EMPTY_NAMES);
        }

        /** The authored directive — mode, limits, gates. */
        public ApplyDirective directive() {
            return directive;
        }

        /** The content reference, compiled. */
        public CompiledRef select() {
            return select;
        }

        /**
         * Whether the select means "the content this template is working on", which is passed
         * straight through rather than re-resolved.
         */
        public boolean wholeParentContent() {
            return wholeParentContent;
        }

        /** Whether the dispatched content is still part of the input, and can be pointed at. */
        public boolean locatable() {
            return locatable;
        }

        /** How the dispatched level runs — the directive's word, or a default, resolved once (D36). */
        public Dispatch dispatch() {
            return dispatch;
        }

        /** The templates answering this apply's mode, in authored order. */
        public List<CompiledTemplate> candidates() {
            return candidates;
        }

        /** Every capture name a recursive apply shadows, flattened once across the candidates. */
        public String[] recursiveShadow() {
            return recursiveShadow;
        }
    }

    /** Emit a message into the run's stream; {@code FATAL} aborts the run (D36). */
    record EmitError(Severity severity, CompiledRef message) implements CompiledOp {

    }

    /** Invoke a template by name. The target is a field read at run time, not a search. */
    final class CallTemplate implements CompiledOp {

        private final String name;
        private final List<Arg> args;
        private CompiledTemplate target;
        private List<Param> params = List.of();

        CallTemplate(final String name, final List<Arg> args) {
            this.name = name;
            this.args = args;
        }

        /**
         * Bind the template this calls and work out what its parameters do here.
         *
         * <p>Which parameters this call site leaves unsupplied is fixed: the target's
         * declarations and the call's arguments are both written down. So the search through the
         * arguments per declared parameter, and the encoding of each default, happen once — see
         * {@link Param}.
         *
         * @param target the template named, or null when the name resolves to none, which is
         *               not an error here: the call does nothing at run time
         */
        void link(final CompiledTemplate target) {
            this.target = target;
            if (target == null) {
                return;
            }
            final List<Param> declared = new ArrayList<>();
            for (final Template.ParamDecl parameter : target.template().param()) {
                final boolean supplied = args.stream()
                        .anyMatch(arg -> arg.name().equals(parameter.name()));
                declared.add(new Param(parameter.name(),
                        !supplied && parameter.defaultValue() != null
                                ? TypedValue.of(parameter.defaultValue())
                                : null));
            }
            this.params = List.copyOf(declared);
        }

        /** The name called, kept for diagnostics. */
        public String name() {
            return name;
        }

        /** The arguments this call supplies. */
        public List<Arg> args() {
            return args;
        }

        /** The template called, or null when the name names none. */
        public CompiledTemplate target() {
            return target;
        }

        /** The target's parameters as this call site sees them. */
        public List<Param> params() {
            return params;
        }
    }

    /**
     * A called template's parameter, at one call site.
     *
     * @param name         the parameter, which the call always shadows
     * @param defaultValue the value to bind, encoded once — or null when this call supplies the
     *                     parameter itself, or the declaration has no default
     */
    record Param(String name, TypedValue defaultValue) {

    }

    /** One argument of a {@link CallTemplate}. */
    record Arg(String name, CompiledRef value) {

    }

    /** Bind a variable to what a nested body writes. */
    record Variable(String name, List<CompiledOp> body) implements CompiledOp {

    }

    /** Design 20's structural instructions, bracketing their bodies with the sink's calls. */
    record Element(String name, String namespace, boolean omitIfEmpty, List<CompiledOp> body)
            implements CompiledOp {

    }

    /** An attribute on the enclosing element, its value the body's text; omitted if empty when asked. */
    record Attribute(String name, boolean omitIfEmpty, List<CompiledOp> body) implements CompiledOp {

    }

    /** A namespace declaration on the enclosing element; a null prefix declares the default. */
    record Namespace(String prefix, String uri) implements CompiledOp {

    }

    /**
     * Map a value to another, from a table written in the configuration.
     *
     * @param entries      the mapped value for each input, encoded once — first declaration
     *                     winning, and an entry mapping to nothing holding the default, so a
     *                     lookup that misses and one that finds nothing agree as they did
     * @param defaultValue what an unmapped value produces, never null: an undeclared default
     *                     is the empty value
     */
    record ValueMap(CompiledRef select,
                    Map<String, TypedValue> entries,
                    TypedValue defaultValue,
                    String name) implements CompiledOp {

    }

    /**
     * A call to a registered function (design 26): {@code select.get(i)} is the reference at
     * position {@code i}, or null where {@code sequences.get(i)} names the store whose entries
     * that position receives.
     *
     * @param slot where the run's bindings hold this function, so the call reads an array rather
     *             than hashing its name
     */
    record CallFunction(FunctionDefinition definition,
                        int slot,
                        List<CompiledRef> select,
                        List<String> sequences,
                        String name) implements CompiledOp {

    }

    /**
     * Run a transform function over resolved inputs — every {@code translate}, {@code replace},
     * {@code substring} and the rest, as one instruction with its parameters already closed
     * over. What kind it was matters at authoring time; at run time there is only "resolve the
     * selects, apply the function, write or bind the result".
     *
     * @param numericKind the instruction's name when it is arithmetic — the hook for the
     *                    {@code strict_values} diagnostic (design/17 §10) — or null
     */
    record Transform(List<CompiledRef> select,
                     String name,
                     Function<List<TypedValue>, TypedValue> function,
                     String numericKind) implements CompiledOp {

    }

    /**
     * Parse text into an instant (design/17 §9). Its own instruction rather than a
     * {@link Transform}, because the reference date resolves against the match at run time —
     * threading it through the select list would let an absent input shift positions, and a
     * reference read as an input is a wrong date that looks right.
     *
     * @param reference the reference date's ref, or null; required at compile time when the
     *                  pattern has no year
     */
    record ParseDate(CompiledRef select,
                     CompiledRef reference,
                     Dates.Parser parser,
                     String name) implements CompiledOp {

    }

    /**
     * Replace by regex (design 29 phase 3).
     *
     * <p>Its own instruction rather than a {@link Transform}, for {@link ParseDate}'s reason: it
     * holds a compiled thing — a {@link Replacer}, which is a matcher and a replacement already
     * parsed into literals and group indices — and a {@code Transform} would carry it inside a
     * closure where nothing can see it. An instruction that holds compiled state should say so.
     */
    record Replace(List<CompiledRef> select, String name, Replacer replacer) implements CompiledOp {

        public Replace {
            select = List.copyOf(select);
        }
    }

    /** Declare a sequence and empty it (design/16 §9). */
    record Sequence(String name) implements CompiledOp {

    }

    /** Add a value to a declared sequence, at its next free index. */
    record Append(String name, CompiledRef select) implements CompiledOp {

    }

    /** Walk a sequence, running a body per populated entry (design/16 §4). */
    record ForEach(String select,
                   String as,
                   List<SortKey> sort,
                   List<CompiledOp> body) implements CompiledOp {

    }

    /** Group a sequence's entries, running a body per group (design/16 §6). */
    record ForEachGroup(String select,
                        CompiledRef groupBy,
                        List<CompiledOp> body) implements CompiledOp {

    }

    /** Build a random-access index over a sequence (design/16 §8). */
    record Key(String name, String select, CompiledRef groupBy) implements CompiledOp {

    }

    /** Look one value up in a key, binding the entries it names. */
    record KeyGet(String key, CompiledRef select, String name) implements CompiledOp {

    }

    /** One compiled ordering key: the reference resolved once, the cast decided once. */
    record SortKey(CompiledRef by, OutputNode.Order order, Cast as) {

    }

    /** What a {@link Fold} does. The authored vocabulary is five instructions; this is one. */
    enum FoldKind {
        COUNT, SUM, AVG, MIN, MAX
    }

    /**
     * Fold a sequence to one value (design/16 §8). Five authored instructions collapse here
     * the way the transforms collapse to {@link Transform}: what kind it was matters at
     * authoring time, and at run time there is only "read the sequence, fold it, write or
     * bind the result".
     */
    record Fold(String select, FoldKind kind, Cast as, String name) implements CompiledOp {

    }

    /** The distinct entries of a sequence, bound as a dense one. */
    record DistinctValues(String select, String name) implements CompiledOp {

    }

    /**
     * Split a value. Its own instruction rather than a {@link Transform} because binding a
     * name now means binding <b>N</b> values, which a transform's single result cannot do —
     * design/17 §16.4's ruling.
     */
    record Tokenize(CompiledRef select, String delimiter, String name) implements CompiledOp {

    }
}
