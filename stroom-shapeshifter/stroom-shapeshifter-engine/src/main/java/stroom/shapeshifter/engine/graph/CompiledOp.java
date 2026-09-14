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

package stroom.shapeshifter.engine.graph;

import stroom.shapeshifter.engine.Severity;
import stroom.shapeshifter.engine.config.Cast;
import stroom.shapeshifter.engine.config.Declaration;
import stroom.shapeshifter.engine.config.Dispatch;
import stroom.shapeshifter.engine.config.OutputNode;
import stroom.shapeshifter.engine.config.OutputNode.ApplyDirective;
import stroom.shapeshifter.engine.function.FunctionDefinition;
import stroom.shapeshifter.engine.value.Dates;
import stroom.shapeshifter.engine.value.TypedValue;
import stroom.shapeshifter.regex.BytePattern;

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
 *
 * <p><b>A body is a {@code CompiledOp[]}, and nothing may write to one.</b> The arrays replaced
 * {@code List<CompiledOp>} because the interpreter walked them per instruction through an
 * interface the JIT could not bind: {@code List::get}, {@code size} and {@code iterator} failed
 * to inline at hundreds of sites, the receiver profile being polluted across every {@code List}
 * implementation in the engine. An array load has no call in it, and design 33 §10 is the
 * measurement — it is the whole of what that design delivered.
 *
 * <p><b>What it costs.</b> The compiler used to hand over {@code List.copyOf(ops)}, which was
 * immutable: a write threw. An array cannot be immutable in Java, so the guarantee is now a rule
 * rather than a type. It matters because a compiled project outlives the runs that use it and is
 * shared between them (D35) — a run that wrote into a body would be editing every later run's
 * program. Nothing writes to one, and the accessors that hand the array out are the whole surface
 * to check.
 */
public sealed interface CompiledOp {


    /** Shared empty, for an apply whose mode no template answers. */
    CompiledTemplate[] EMPTY_CANDIDATES = new CompiledTemplate[0];

    /** Shared empty, for a call whose target declares no parameters. */
    CallTemplate.Param[] EMPTY_PARAMS = new CallTemplate.Param[0];

    /** Write a literal: a UTF-8-tagged value; the sink's encoding decides its bytes (design 25). */
    record Text(TypedValue value) implements CompiledOp {

    }

    /** Write the value of a reference. */
    record ValueOf(CompiledRef ref) implements CompiledOp {

    }

    /** Run a body if a condition holds. */
    record If(CompiledCondition test, CompiledOp[] then) implements CompiledOp {

    }

    /** Run the first branch whose condition holds. */
    record Choose(When[] when, CompiledOp[] otherwise) implements CompiledOp {

    }

    /** One branch of a {@link Choose}. */
    record When(CompiledCondition test, CompiledOp[] body) {

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
                  Map<String, CompiledOp[]> cases,
                  CompiledOp[] defaultBody) implements CompiledOp {

    }

    final class Apply implements CompiledOp {

        private final ApplyDirective directive;
        private final CompiledRef select;
        private final boolean wholeParentContent;
        private final boolean locatable;
        private final Dispatch dispatch;
        private CompiledTemplate[] candidates = EMPTY_CANDIDATES;

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
        public Apply(final ApplyDirective directive,
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
        public void link(final CompiledTemplate[] candidates) {
            this.candidates = candidates;
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
        public CompiledTemplate[] candidates() {
            return candidates;
        }

    }

    /** Emit a message into the run's stream; {@code FATAL} aborts the run (D36). */
    record EmitError(Severity severity, CompiledRef message) implements CompiledOp {

    }

    /** Invoke a template by name. The target is a field read at run time, not a search. */
    final class CallTemplate implements CompiledOp {

        private final String name;
        private final Arg[] args;
        private CompiledTemplate target;
        private Param[] params = EMPTY_PARAMS;

        public CallTemplate(final String name, final Arg[] args) {
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
        public void link(final CompiledTemplate target, final Param[] params) {
            this.target = target;
            this.params = params;
        }

        /** The name called, kept for diagnostics. */
        public String name() {
            return name;
        }

        /** The arguments this call supplies. */
        public Arg[] args() {
            return args;
        }

        /** The template called, or null when the name names none. */
        public CompiledTemplate target() {
            return target;
        }

        /** The target's parameters as this call site sees them. */
        public Param[] params() {
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
    record Param(VarName name, TypedValue defaultValue) {

    }

    /** One argument of a {@link CallTemplate}. */
    record Arg(VarName name, CompiledRef value) {

    }

    /** Bind a variable to what a nested body writes. */
    record Variable(VarName name, CompiledOp[] body) implements CompiledOp {

    }

    /** Design 20's structural instructions, bracketing their bodies with the sink's calls. */
    record Element(String name, String namespace, boolean omitIfEmpty, CompiledOp[] body)
            implements CompiledOp {

    }

    /** An attribute on the enclosing element, its value the body's text; omitted if empty when asked. */
    record Attribute(String name, boolean omitIfEmpty, CompiledOp[] body) implements CompiledOp {

    }

    /** A namespace declaration on the enclosing element; a null prefix declares the default. */
    record Namespace(String prefix, String uri) implements CompiledOp {

    }

    /**
     * A call to a registered function (design 26): {@code select.get(i)} is the reference at
     * position {@code i}, or null where {@code sequences.get(i)} names the list whose entries
     * that position receives.
     *
     * @param slot where the run's bindings hold this function, so the call reads an array rather
     *             than hashing its name
     */
    record CallFunction(FunctionDefinition definition,
                        int slot,
                        CompiledRef[] select,
                        VarName[] sequences,
                        VarName name) implements CompiledOp {

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
    record Transform(CompiledRef[] select,
                     VarName name,
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
                     VarName name) implements CompiledOp {

    }

    /**
     * Replace by regex (design 29 phase 3).
     *
     * <p>Its own instruction rather than a {@link Transform}, for {@link ParseDate}'s reason: it
     * holds a compiled thing — a {@link Replacer}, which is a matcher and a replacement already
     * parsed into literals and group indices — and a {@code Transform} would carry it inside a
     * closure where nothing can see it. An instruction that holds compiled state should say so.
     */
    record Replace(CompiledRef[] select, VarName name, Replacer replacer) implements CompiledOp {

    }

    /** Add a value to a list, reached by reference (design 35 §5). */
    record Append(CompiledRef target, CompiledRef select) implements CompiledOp {

    }

    /** Insert a value before a 1-based position of a list. */
    record Insert(CompiledRef target, CompiledRef position, CompiledRef select) implements CompiledOp {

    }

    /**
     * Put a value at a position of a list, under a key of a map, into a set, or into a scalar
     * — which is the declared type's to say, settled here when the target is a declared name.
     *
     * @param key      the position or key, or null for a set or a scalar
     * @param declared the target's declared type when it is a bare name, or null when the
     *                 target is reached through an accessor and the type is a run-time fact
     */
    record Put(CompiledRef target, CompiledRef key, CompiledRef select, Declaration.Type declared)
            implements CompiledOp {

    }

    /** Remove by position, key or member. */
    record Remove(CompiledRef target, CompiledRef key) implements CompiledOp {

    }

    /** Empty a collection. */
    record Clear(CompiledRef target) implements CompiledOp {

    }

    /** Walk a collection, running a body per entry (design/16 §4, design 35 §5). */
    record ForEach(CompiledRef select,
                   VarName as,
                   VarName asKey,
                   SortKey[] sort,
                   CompiledOp[] body) implements CompiledOp {

    }

    /** Group a list's entries, running a body per group (design/16 §6). */
    record ForEachGroup(CompiledRef select,
                        CompiledRef groupBy,
                        CompiledOp[] body) implements CompiledOp {

    }

    /** One compiled ordering key: the reference resolved once, the cast decided once. */
    record SortKey(CompiledRef by, OutputNode.Order order, Cast as) {

    }

    /**
     * Split a value. Its own instruction rather than a {@link Transform} because binding a
     * name now means binding <b>N</b> values, which a transform's single result cannot do —
     * design/17 §16.4's ruling.
     */
    record Tokenize(CompiledRef select, String delimiter, VarName name) implements CompiledOp {

    }
}
