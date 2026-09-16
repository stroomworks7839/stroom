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

package stroom.shapeshifter.engine.config;

import stroom.shapeshifter.engine.Severity;

import java.util.ArrayList;
import java.util.List;

/**
 * One instruction in a template's body.
 *
 * <p>A body is a flat sequence of instructions, each writing to the output in turn — not a tree
 * of elements that has to be balanced. That is XSLT's streaming model, and the names follow
 * XSLT's wherever an equivalent exists, because the people who will write these configurations
 * already know what {@code value-of} and {@code apply-templates} do.
 *
 * <p>The transform instructions at the end are the function library. Each takes one or more
 * values, produces one, and either writes it or — when {@code name} is set — binds it to a
 * variable instead. That dual behaviour is why they are instructions rather than expressions.
 */
public sealed interface OutputNode permits OutputNode.Holder, OutputNode.Binding, OutputNode.Leaf {

    /**
     * An instruction that holds bodies, walked in written order (D47). Every walk that needs to
     * look inside an instruction asks here rather than carrying its own list of containers,
     * which twice stopped short of an iteration's body before this was said once (design 27
     * phase 1). A record is a holder or it is not: the {@code permits} list is the statement.
     */
    sealed interface Holder extends OutputNode
            permits If, Choose, Switch, Variable, Element, Attribute, ForEach, ForEachGroup {

        /** The bodies nested directly inside, in written order. */
        List<List<OutputNode>> bodies();
    }

    /**
     * An instruction that may bind a name instead of, or as well as, writing (D47). The
     * transforms bind when named and write when not; a variable always binds. These are the
     * two value sources that are not collection-shaped (design 35 §4, §5): everything design 16
     * built for one collection type is now a declared type and an operation on it.
     */
    sealed interface Binding extends OutputNode
            permits Transform, Variable {

        String name();

        /** Whether this instruction binds rather than writes. */
        default boolean binds() {
            return name() != null;
        }
    }

    /**
     * A transform: one or more selected values in, one value out, written or bound (D47). The
     * function library's shape, and a function call's.
     */
    sealed interface Transform extends Binding
            permits Translate, StringJoin, Call, Replace, LowerCase, UpperCase, NormalizeSpace,
                    Trim, Substring, Tokenize, Number, Add, Subtract, Multiply, Divide, Mod, Round,
                    Floor, Ceiling, Abs, StringLength, SubstringBefore, SubstringAfter, StartsWith,
                    EndsWith, Contains, FormatNumber, ParseDate, FormatDate, Decode {

        List<RefExpression> select();
    }

    /** Everything else: writes, reads or declares, and holds nothing (D47). */
    sealed interface Leaf extends OutputNode
            permits Text, ValueOf, EmitError, ApplyTemplates, CallTemplate, Namespace,
                    Append, Insert, Put, Remove, Clear {

    }

    /**
     * An instruction whose text may be a regular expression the compiler interns (D47). A
     * marker, not a classification: it is outside the {@code permits} lists, and an instruction
     * with a pattern of its own declares it here, in the same clause that says everything else
     * about it, so the pattern-collecting walk needs no list of its own.
     */
    interface Regexed {

        String pattern();

        boolean isRegex();
    }

    /**
     * Emit a message into the run's message stream, as {@code value-of} emits into output —
     * authored diagnostics for paths the author can name, like the cautionary eater (D36).
     * A {@link Severity#FATAL} emission aborts the run.
     */
    record EmitError(Severity severity, RefExpression message) implements Leaf {

    }

    /** Write literal text. XSLT: {@code xsl:text}. */
    record Text(String value) implements Leaf {

    }

    /** Write the value of an expression. XSLT: {@code xsl:value-of}. */
    record ValueOf(RefExpression select) implements Leaf {

    }

    /** Run a body if a condition holds. XSLT: {@code xsl:if}. */
    record If(Condition test, List<OutputNode> then) implements Holder {

        public If {
            then = then == null ? List.of() : List.copyOf(then);
        }

        @Override
        public List<List<OutputNode>> bodies() {
            return List.of(then);
        }
    }

    /** Run the first branch whose condition holds. XSLT: {@code xsl:choose}. */
    record Choose(List<WhenBranch> when, List<OutputNode> otherwise) implements Holder {

        public Choose {
            when = when == null ? List.of() : List.copyOf(when);
            otherwise = otherwise == null ? List.of() : List.copyOf(otherwise);
        }

        /** The branches in written order, then the otherwise. */
        @Override
        public List<List<OutputNode>> bodies() {
            final List<List<OutputNode>> bodies = new ArrayList<>(when.size() + 1);
            for (final WhenBranch branch : when) {
                bodies.add(branch.body());
            }
            bodies.add(otherwise);
            return bodies;
        }
    }

    /** Run the branch whose value matches, comparing a single expression against literals. */
    record Switch(RefExpression select,
                  List<SwitchCase> cases,
                  List<OutputNode> defaultBody) implements Holder {

        public Switch {
            cases = cases == null ? List.of() : List.copyOf(cases);
            defaultBody = defaultBody == null ? List.of() : List.copyOf(defaultBody);
        }

        /** The cases in written order, then the default. */
        @Override
        public List<List<OutputNode>> bodies() {
            final List<List<OutputNode>> bodies = new ArrayList<>(cases.size() + 1);
            for (final SwitchCase switchCase : cases) {
                bodies.add(switchCase.body());
            }
            bodies.add(defaultBody);
            return bodies;
        }
    }

    /** Match templates against some content. XSLT: {@code xsl:apply-templates}. */
    record ApplyTemplates(ApplyDirective directive) implements Leaf {

    }

    /**
     * Invoke a template by name, with no content matching. XSLT: {@code xsl:call-template}.
     *
     * @param name      the template to invoke
     * @param withParam parameters to pass, in order
     */
    record CallTemplate(String name, List<Param> withParam) implements Leaf {

        public CallTemplate {
            withParam = withParam == null ? List.of() : List.copyOf(withParam);
        }
    }

    /** Bind a variable to what a nested body writes. XSLT: {@code xsl:variable}. */
    record Variable(String name, List<OutputNode> body) implements Holder, Binding {

        public Variable {
            if (name == null || name.isEmpty()) {
                throw new ConfigException("A variable needs a name");
            }
            body = body == null ? List.of() : List.copyOf(body);
        }

        @Override
        public List<List<OutputNode>> bodies() {
            return List.of(body);
        }
    }

    /**
     * Open an element around a body; it closes when the body finishes. XSLT: {@code xsl:element}.
     *
     * <p>The three structural instructions (design 20, D40). Inside an element's body, what
     * {@code text} and {@code value-of} write is content; the sink escapes it. Attributes and
     * namespaces must come before any content — the compiler checks the body as written, the
     * sink checks what arrives.
     *
     * @param name        the qualified name, prefix included if it has one
     * @param namespace   the namespace URI, or null to use whatever the prefix is bound to in scope;
     *                    given, and not already bound to the prefix, the element declares it
     * @param omitIfEmpty leave no trace if nothing arrived — no declaration, attribute or content
     *                    (DS3's lazy {@code <record>}, P1 in design 21)
     */
    record Element(String name, String namespace, boolean omitIfEmpty, List<OutputNode> body)
            implements Holder {

        public Element {
            body = body == null ? List.of() : List.copyOf(body);
        }

        @Override
        public List<List<OutputNode>> bodies() {
            return List.of(body);
        }
    }

    /**
     * An attribute of the enclosing element whose value is what the body writes.
     * XSLT: {@code xsl:attribute}. The body may write text and values, not structure.
     *
     * @param omitIfEmpty drop the attribute if its value came out empty (DS3's normalised
     *                    {@code <data>} attributes, P2 in design 21)
     */
    record Attribute(String name, boolean omitIfEmpty, List<OutputNode> body) implements Holder {

        public Attribute {
            body = body == null ? List.of() : List.copyOf(body);
        }

        @Override
        public List<List<OutputNode>> bodies() {
            return List.of(body);
        }
    }

    /** Declare a prefix on the enclosing element; the empty prefix is the default namespace. */
    record Namespace(String prefix, String uri) implements Leaf {

        public Namespace {
            prefix = prefix == null ? "" : prefix;
        }
    }

    // -----------------------------------------------------------------------------------
    // Transform functions
    // -----------------------------------------------------------------------------------

    /**
     * A call to a registered function (design 26 §3): the function by name, its arguments as a
     * select list — positional, absent where a reference resolves to nothing — and, with a
     * name, a binding rather than a write.
     */
    record Call(String function, List<RefExpression> select, String name) implements Transform {

    }

    /**
     * Substitute substrings, one search string at a time, in order: each {@code from} entry is
     * replaced throughout by the {@code to} entry at the same index, or deleted when {@code to}
     * has no entry there. Whole substrings, not characters — this is <i>not</i> XSLT's
     * {@code translate()}, whose arguments are parallel character sets.
     */
    record Translate(List<RefExpression> select,
                     List<String> from,
                     List<String> to,
                     String name) implements Transform {

        public Translate {
            select = select == null ? List.of() : List.copyOf(select);
            from = from == null ? List.of() : List.copyOf(from);
            to = to == null ? List.of() : List.copyOf(to);
        }
    }

    /** Join values with a separator. XSLT: {@code string-join()}. */
    record StringJoin(List<RefExpression> select, String separator, String name)
            implements Transform {

        public StringJoin {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /**
     * Replace occurrences of a pattern. XSLT: {@code replace()}.
     *
     * @param isRegex whether {@code pattern} is a regex or a literal. It defaults to literal,
     *                which is worth knowing: most patterns in the corpus are literals
     */
    record Replace(List<RefExpression> select,
                   String pattern,
                   String replacement,
                   boolean isRegex,
                   String name) implements Transform, Regexed {

        public Replace {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /**
     * The bytes a value encodes — base64, hex, a compression — decoded (design 38 §3). The
     * old {@code Decode} step, as a transform: a match captures the encoded bytes and the body
     * decodes them, usually to apply templates to the result.
     */
    record Decode(List<RefExpression> select, Codec codec, String name) implements Transform {

        public Decode {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** Lower-case. XSLT: {@code lower-case()}. */
    record LowerCase(List<RefExpression> select, String name) implements Transform {

        public LowerCase {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** Upper-case. XSLT: {@code upper-case()}. */
    record UpperCase(List<RefExpression> select, String name) implements Transform {

        public UpperCase {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** Collapse runs of whitespace. XSLT: {@code normalize-space()}. */
    record NormalizeSpace(List<RefExpression> select, String name) implements Transform {

        public NormalizeSpace {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** Strip leading and trailing whitespace. */
    record Trim(List<RefExpression> select, String name) implements Transform {

        public Trim {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /**
     * Take part of a value. XSLT: {@code substring()} — and the base is <b>version-gated</b>
     * (design/17 §7, superseding E21's documented-not-aligned ruling): below version 5
     * {@code start} is 0-based, faithful to the ported library and existing configurations;
     * from version 5 it is 1-based, XSLT's own reading, with a start below 1 shrinking the
     * window per XPath's rule. An <b>omitted</b> start means "from the beginning" under
     * either base — which is why it is nullable rather than defaulting to a number that
     * would change meaning at the gate. The compiler warns, once per configuration, on any
     * pre-version-5 configuration a bump would change.
     */
    record Substring(List<RefExpression> select,
                     Integer start,
                     Integer length,
                     String name) implements Transform {

        public Substring {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** Split on a delimiter. XSLT: {@code tokenize()}. */
    record Tokenize(List<RefExpression> select, String delimiter, String name)
            implements Transform {

        public Tokenize {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** Read as a number. XSLT: {@code number()}. */
    record Number(List<RefExpression> select, String name) implements Transform {

        public Number {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    // -----------------------------------------------------------------------------------
    // Arithmetic (design/17 §5). Inputs cast per the table; any absent or non-numeric
    // input makes the whole result absent — not zero, and not a partial fold.
    // -----------------------------------------------------------------------------------

    /** Fold {@code +} over the inputs. Whole numbers stay exact; overflow promotes (§11). */
    record Add(List<RefExpression> select, String name) implements Transform {

        public Add {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** {@code a - b}, exactly two inputs. */
    record Subtract(List<RefExpression> select, String name) implements Transform {

        public Subtract {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** Fold {@code *} over the inputs. */
    record Multiply(List<RefExpression> select, String name) implements Transform {

        public Multiply {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** {@code a / b}. Whole when exact, fractional otherwise; division by zero is absent. */
    record Divide(List<RefExpression> select, String name) implements Transform {

        public Divide {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** {@code a mod b}, the sign following the dividend — XPath's {@code mod}, Java's {@code %}. */
    record Mod(List<RefExpression> select, String name) implements Transform {

        public Mod {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** Round half-up on ties — XPath's {@code round()}: {@code round(-2.5)} is {@code -2}. */
    record Round(List<RefExpression> select, String name) implements Transform {

        public Round {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** XSLT: {@code floor()}. */
    record Floor(List<RefExpression> select, String name) implements Transform {

        public Floor {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** XSLT: {@code ceiling()}. */
    record Ceiling(List<RefExpression> select, String name) implements Transform {

        public Ceiling {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** XPath: {@code abs()}. */
    record Abs(List<RefExpression> select, String name) implements Transform {

        public Abs {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    // -----------------------------------------------------------------------------------
    // The string additions (design/17 §6)
    // -----------------------------------------------------------------------------------

    /** Length in code points — what a person would count. XSLT: {@code string-length()}. */
    record StringLength(List<RefExpression> select, String name) implements Transform {

        public StringLength {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /**
     * The part before the first occurrence of a marker, <b>absent when the marker is not
     * found</b> — not the empty string, so a condition can tell the two apart (§6). XSLT
     * returns {@code ""} for both; the written output is identical, the testable value is not.
     */
    record SubstringBefore(List<RefExpression> select,
                           String marker,
                           String name) implements Transform {

        public SubstringBefore {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** The part after the first occurrence of a marker; absent when not found, as above. */
    record SubstringAfter(List<RefExpression> select,
                          String marker,
                          String name) implements Transform {

        public SubstringAfter {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** {@code starts-with()} as a value — for binding and for choosing on a computed flag. */
    record StartsWith(List<RefExpression> select, String prefix, String name) implements Transform {

        public StartsWith {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** {@code ends-with()} as a value. */
    record EndsWith(List<RefExpression> select, String suffix, String name) implements Transform {

        public EndsWith {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** {@code contains()} as a value. The condition of the same name stays; this one binds. */
    record Contains(List<RefExpression> select, String substring, String name)
            implements Transform {

        public Contains {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /**
     * Format a number through a picture string — {@code java.text.DecimalFormat} under
     * {@code Locale.ROOT}, which matches XSLT's default decimal format for the ordinary
     * pictures and diverges on the edges (per-mille, explicit {@code +}, infinity); the
     * boundary is recorded by the proving case. XSLT: {@code format-number()}.
     */
    record FormatNumber(List<RefExpression> select,
                        String picture,
                        String name) implements Transform {

        public FormatNumber {
            select = select == null ? List.of() : List.copyOf(select);
            if (picture == null || picture.isEmpty()) {
                throw new ConfigException("A format-number needs a picture");
            }
        }
    }

    // -----------------------------------------------------------------------------------
    // Collections (design 35 §5). A declaration says what a name holds — a list, a map, a set —
    // and these are the mutations on it, named after XPath 3.1's array: and map: libraries.
    // A mutation is a statement in a body; an accessor is a function in an expression
    // (RefExpression.RefPart.Accessor). Nothing mutates from inside an expression.
    // -----------------------------------------------------------------------------------

    /**
     * Add a value to a list — {@code array:append}. The list is a reference, so a nested list
     * reached through {@code get} is appended to in place; a bare name is the common case.
     *
     * <p>An absent value appends nothing: the value was not there, so there is nothing to
     * add. (A <i>capture</i> into a list appends absence, to keep positions aligned with match
     * numbers — that is the capture's rule, not this instruction's.)
     */
    record Append(RefExpression target, RefExpression select) implements Leaf {

        public Append {
            requireTarget(target, "append");
            if (select == null) {
                throw new ConfigException("An append needs a value to add");
            }
        }
    }

    /**
     * Insert a value before a position of a list — {@code array:insert-before}. Positions are
     * 1-based, as XPath's are; inserting at {@code size + 1} appends.
     */
    record Insert(RefExpression target, RefExpression position, RefExpression select)
            implements Leaf {

        public Insert {
            requireTarget(target, "insert");
            if (position == null || select == null) {
                throw new ConfigException("An insert needs a position and a value");
            }
        }
    }

    /**
     * Put a value somewhere — {@code array:put} at a position of a list, {@code map:put} under
     * a key of a map, and with no key: into a set as a member, or into a scalar as its value.
     * "Put this value at this location" is one idea, and the declared type says which.
     *
     * @param key the position (list) or key (map), or null for a set or a scalar
     */
    record Put(RefExpression target, RefExpression key, RefExpression select) implements Leaf {

        public Put {
            requireTarget(target, "put");
            if (select == null) {
                throw new ConfigException("A put needs a value");
            }
        }
    }

    /**
     * Remove an entry — {@code array:remove} by position, {@code map:remove} by key, and from
     * a set by value. There is deliberately no remove-by-value for a list: XPath has none
     * either, and a filter-and-rebuild is its answer.
     */
    record Remove(RefExpression target, RefExpression key) implements Leaf {

        public Remove {
            requireTarget(target, "remove");
            if (key == null) {
                throw new ConfigException("A remove needs a position, key or value");
            }
        }
    }

    /**
     * Empty a collection. XPath needs no {@code clear} because its values are immutable and
     * a name is rebound; these mutate in place, so the start of an accumulation is said.
     */
    record Clear(RefExpression target) implements Leaf {

        public Clear {
            requireTarget(target, "clear");
        }
    }

    private static void requireTarget(final RefExpression target, final String what) {
        if (target == null || target.parts().isEmpty()) {
            throw new ConfigException("A " + what + " needs a collection to act on");
        }
    }

    /**
     * Walk a collection, running a body once per entry — XSLT's {@code xsl:for-each}, over
     * what has been declared rather than over a tree. A list's populated entries in order; a
     * set's members in insertion order; a map's entries, with the key bound too
     * (design 35 §5: {@code map:for-each}'s reading).
     *
     * @param select the collection: a reference, so a nested one reached through {@code get}
     *               is walked in place; a bare name is the common case
     * @param as     binds the entry's value for the body, or null to read it by index alone
     * @param asKey  binds the entry's key, for a map, or null
     * @param body   what runs per entry, with {@code index()}, {@code position()} and
     *               {@code last()} bound (§4.3)
     */
    record ForEach(RefExpression select, String as, String asKey, List<Sort> sort, List<OutputNode> body)
            implements Holder {

        public ForEach {
            if (select == null || select.parts().isEmpty()) {
                throw new ConfigException("A for-each needs a collection to walk");
            }
            sort = sort == null ? List.of() : List.copyOf(sort);
            body = body == null ? List.of() : List.copyOf(body);
        }

        @Override
        public List<List<OutputNode>> bodies() {
            return List.of(body);
        }
    }

    /**
     * Group a list's entries and run a body once per group — XSLT's
     * {@code xsl:for-each-group} with {@code group-by} (design/16 §6).
     *
     * <p>Groups form in <b>order of first appearance</b>, XSLT's rule and the one a log
     * summary wants. What is grouped is the <b>index set</b>, not the values: two captures of
     * one template at match <i>i</i> belong to the same record, so a group's members are
     * positions that any parallel list can be read at. That is how a byte engine with no
     * tree reaches what {@code current-group()} reaches.
     *
     * @param select  the list whose positions are grouped
     * @param groupBy the key, evaluated per entry with {@code index()} bound, or null to
     *                group by the entry's own value
     */
    record ForEachGroup(RefExpression select, RefExpression groupBy, List<OutputNode> body)
            implements Holder {

        public ForEachGroup {
            if (select == null || select.parts().isEmpty()) {
                throw new ConfigException("A for-each-group needs a list to group");
            }
            body = body == null ? List.of() : List.copyOf(body);
        }

        @Override
        public List<List<OutputNode>> bodies() {
            return List.of(body);
        }
    }

    /**
     * One key of an iteration's ordering (design/16 §5). {@code by} is evaluated once per
     * entry with {@code index()} bound, so a key can read the item, a parallel list at the
     * same match, or a concatenation.
     *
     * <p>Ordering is by the same {@code as} cast every typed read in the engine uses; uncast
     * it compares string forms, which is the one <b>total</b> reading and therefore the only
     * safe default for something that must order a whole column (design/17 §8).
     */
    record Sort(RefExpression by, Order order, Cast as) {

        public Sort {
            if (by == null) {
                throw new ConfigException("A sort key needs something to sort by");
            }
            order = order == null ? Order.ASCENDING : order;
        }
    }

    /** Which way a sort key runs. */
    enum Order {
        ASCENDING, DESCENDING
    }

    // -----------------------------------------------------------------------------------
    // Dates (design/17 §9): the composed pair the 2026-08-21 ruling chose over
    // stroom:format-date's conflated signature
    // -----------------------------------------------------------------------------------

    /**
     * Parse text into an instant. The pattern is a {@code DateTimeFormatter} pattern under
     * the root locale, or one of the reserved names {@code iso}, {@code epoch-millis},
     * {@code epoch-seconds}. The timezone supplies the placement only when the pattern
     * parses no offset; a parsed offset always wins and is carried. A pattern with no year
     * needs a {@code reference} date — the nearest-year rule, Stroom's own with the input
     * made explicit — and is refused at compile time without one.
     */
    record ParseDate(List<RefExpression> select,
                     String pattern,
                     String timezone,
                     RefExpression reference,
                     String name) implements Transform {

        public ParseDate {
            select = select == null ? List.of() : List.copyOf(select);
            if (pattern == null || pattern.isEmpty()) {
                throw new ConfigException("A parse-date needs a pattern");
            }
        }
    }

    /**
     * Render an instant. Zone precedence: the instruction's {@code timezone} if given, else
     * the instant's own carried offset, else UTC — a value round-trips through its original
     * offset unless the author says otherwise.
     */
    record FormatDate(List<RefExpression> select,
                      String pattern,
                      String timezone,
                      String name) implements Transform {

        public FormatDate {
            select = select == null ? List.of() : List.copyOf(select);
            if (pattern == null || pattern.isEmpty()) {
                throw new ConfigException("A format-date needs a pattern");
            }
        }
    }

    // -----------------------------------------------------------------------------------
    // Supporting shapes
    // -----------------------------------------------------------------------------------

    /** One branch of a {@link Choose}. XSLT: {@code xsl:when}. */
    record WhenBranch(Condition test, List<OutputNode> body) {

        public WhenBranch {
            body = body == null ? List.of() : List.copyOf(body);
        }
    }

    /** One case of a {@link Switch}. */
    record SwitchCase(String value, List<OutputNode> body) {

        public SwitchCase {
            body = body == null ? List.of() : List.copyOf(body);
        }
    }

    /** One parameter passed to a template. */
    record Param(String name, RefExpression value) {

    }

    /**
     * What {@code apply-templates} should do.
     *
     * @param select      the content to match templates against
     * @param mode        restrict candidates to templates in this mode, or null for those with
     *                    no mode
     * @param withParam   parameters to pass to whichever template matches
     * @param maxDepth    how deep recursion may go before it is an error
     * @param ignoreErrors suppress the dispatched level's skip and unmatched-content reports.
     *                     This is DS3's {@code ignoreErrors} on the group whose content is being
     *                     dispatched: the container owns the gate, not the templates inside it
     * @param dispatch     how the dispatched level runs (D36), or null to inherit the source
     *                     default. The container owns this too, exactly as DS3's group owned
     *                     {@code matchOrder}
     */
    record ApplyDirective(RefExpression select,
                          String mode,
                          List<Param> withParam,
                          int maxDepth,
                          boolean ignoreErrors,
                          Dispatch dispatch) {


        /** How deep recursion goes before the engine calls it a runaway. */
        public static final int DEFAULT_MAX_DEPTH = 64;

        public ApplyDirective {
            withParam = withParam == null ? List.of() : List.copyOf(withParam);
            if (maxDepth <= 0) {
                throw new ConfigException("A max depth must be positive: " + maxDepth);
            }
        }
    }
}
