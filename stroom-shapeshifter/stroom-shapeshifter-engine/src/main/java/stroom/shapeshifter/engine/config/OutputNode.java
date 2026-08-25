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
public sealed interface OutputNode {

    /**
     * Emit a message into the run's message stream, as {@code value-of} emits into output —
     * authored diagnostics for paths the author can name, like the cautionary eater (D36).
     * A {@link Severity#FATAL} emission aborts the run.
     */
    record EmitError(Severity severity, RefExpression message) implements OutputNode {

    }

    /** Write literal text. XSLT: {@code xsl:text}. */
    record Text(String value) implements OutputNode {

    }

    /** Write the value of an expression. XSLT: {@code xsl:value-of}. */
    record ValueOf(RefExpression select) implements OutputNode {

    }

    /** Run a body if a condition holds. XSLT: {@code xsl:if}. */
    record If(Condition test, List<OutputNode> then) implements OutputNode {

        public If {
            then = then == null ? List.of() : List.copyOf(then);
        }
    }

    /** Run the first branch whose condition holds. XSLT: {@code xsl:choose}. */
    record Choose(List<WhenBranch> when, List<OutputNode> otherwise) implements OutputNode {

        public Choose {
            when = when == null ? List.of() : List.copyOf(when);
            otherwise = otherwise == null ? List.of() : List.copyOf(otherwise);
        }
    }

    /** Run the branch whose value matches, comparing a single expression against literals. */
    record Switch(RefExpression select,
                  List<SwitchCase> cases,
                  List<OutputNode> defaultBody) implements OutputNode {

        public Switch {
            cases = cases == null ? List.of() : List.copyOf(cases);
            defaultBody = defaultBody == null ? List.of() : List.copyOf(defaultBody);
        }
    }

    /** Match templates against some content. XSLT: {@code xsl:apply-templates}. */
    record ApplyTemplates(ApplyDirective directive) implements OutputNode {

    }

    /**
     * Invoke a template by name, with no content matching. XSLT: {@code xsl:call-template}.
     *
     * @param name      the template to invoke
     * @param withParam parameters to pass, in order
     */
    record CallTemplate(String name, List<Param> withParam) implements OutputNode {

        public CallTemplate {
            withParam = withParam == null ? List.of() : List.copyOf(withParam);
        }
    }

    /** Bind a variable to what a nested body writes. XSLT: {@code xsl:variable}. */
    record Variable(String name, List<OutputNode> body) implements OutputNode {

        public Variable {
            body = body == null ? List.of() : List.copyOf(body);
        }
    }

    /**
     * Map a value through a lookup table.
     *
     * @param select       the value to look up
     * @param entries      the table
     * @param defaultValue what to produce when nothing matches, or null to produce nothing
     * @param name         bind the result to this variable instead of writing it, or null
     */
    record ValueMap(RefExpression select,
                    List<Entry> entries,
                    String defaultValue,
                    String name) implements OutputNode {

        public ValueMap {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    // -----------------------------------------------------------------------------------
    // Transform functions
    // -----------------------------------------------------------------------------------

    /**
     * Substitute substrings, one search string at a time, in order: each {@code from} entry is
     * replaced throughout by the {@code to} entry at the same index, or deleted when {@code to}
     * has no entry there. Whole substrings, not characters — this is <i>not</i> XSLT's
     * {@code translate()}, whose arguments are parallel character sets.
     */
    record Translate(List<RefExpression> select,
                     List<String> from,
                     List<String> to,
                     String name) implements OutputNode {

        public Translate {
            select = select == null ? List.of() : List.copyOf(select);
            from = from == null ? List.of() : List.copyOf(from);
            to = to == null ? List.of() : List.copyOf(to);
        }
    }

    /** Join values with a separator. XSLT: {@code string-join()}. */
    record StringJoin(List<RefExpression> select, String separator, String name) implements OutputNode {

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
                   String name) implements OutputNode {

        public Replace {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** Lower-case. XSLT: {@code lower-case()}. */
    record LowerCase(List<RefExpression> select, String name) implements OutputNode {

        public LowerCase {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** Upper-case. XSLT: {@code upper-case()}. */
    record UpperCase(List<RefExpression> select, String name) implements OutputNode {

        public UpperCase {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** Collapse runs of whitespace. XSLT: {@code normalize-space()}. */
    record NormalizeSpace(List<RefExpression> select, String name) implements OutputNode {

        public NormalizeSpace {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** Strip leading and trailing whitespace. */
    record Trim(List<RefExpression> select, String name) implements OutputNode {

        public Trim {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /**
     * Take part of a value. XSLT: {@code substring()} — but note the trap: {@code start} is
     * <b>0-based</b> where XSLT's is 1-based. {@code substring(x, 1, 2)} here is XSLT's
     * {@code substring(x, 2, 2)}. Ruled 2026-08-21 (E21): documented, not aligned — the
     * 0-based form is faithful to the ported transform library and existing configurations.
     */
    record Substring(List<RefExpression> select,
                     int start,
                     Integer length,
                     String name) implements OutputNode {

        public Substring {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** Split on a delimiter. XSLT: {@code tokenize()}. */
    record Tokenize(List<RefExpression> select, String delimiter, String name) implements OutputNode {

        public Tokenize {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** Read as a number. XSLT: {@code number()}. */
    record Number(List<RefExpression> select, String name) implements OutputNode {

        public Number {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    // -----------------------------------------------------------------------------------
    // Arithmetic (design/17 §5). Inputs cast per the table; any absent or non-numeric
    // input makes the whole result absent — not zero, and not a partial fold.
    // -----------------------------------------------------------------------------------

    /** Fold {@code +} over the inputs. Whole numbers stay exact; overflow promotes (§11). */
    record Add(List<RefExpression> select, String name) implements OutputNode {

        public Add {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** {@code a - b}, exactly two inputs. */
    record Subtract(List<RefExpression> select, String name) implements OutputNode {

        public Subtract {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** Fold {@code *} over the inputs. */
    record Multiply(List<RefExpression> select, String name) implements OutputNode {

        public Multiply {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** {@code a / b}. Whole when exact, fractional otherwise; division by zero is absent. */
    record Divide(List<RefExpression> select, String name) implements OutputNode {

        public Divide {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** {@code a mod b}, the sign following the dividend — XPath's {@code mod}, Java's {@code %}. */
    record Mod(List<RefExpression> select, String name) implements OutputNode {

        public Mod {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** Round half-up on ties — XPath's {@code round()}: {@code round(-2.5)} is {@code -2}. */
    record Round(List<RefExpression> select, String name) implements OutputNode {

        public Round {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** XSLT: {@code floor()}. */
    record Floor(List<RefExpression> select, String name) implements OutputNode {

        public Floor {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** XSLT: {@code ceiling()}. */
    record Ceiling(List<RefExpression> select, String name) implements OutputNode {

        public Ceiling {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** XPath: {@code abs()}. */
    record Abs(List<RefExpression> select, String name) implements OutputNode {

        public Abs {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    // -----------------------------------------------------------------------------------
    // The string additions (design/17 §6)
    // -----------------------------------------------------------------------------------

    /** Length in code points — what a person would count. XSLT: {@code string-length()}. */
    record StringLength(List<RefExpression> select, String name) implements OutputNode {

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
                           String name) implements OutputNode {

        public SubstringBefore {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** The part after the first occurrence of a marker; absent when not found, as above. */
    record SubstringAfter(List<RefExpression> select,
                          String marker,
                          String name) implements OutputNode {

        public SubstringAfter {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** {@code starts-with()} as a value — for binding and for choosing on a computed flag. */
    record StartsWith(List<RefExpression> select, String prefix, String name) implements OutputNode {

        public StartsWith {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** {@code ends-with()} as a value. */
    record EndsWith(List<RefExpression> select, String suffix, String name) implements OutputNode {

        public EndsWith {
            select = select == null ? List.of() : List.copyOf(select);
        }
    }

    /** {@code contains()} as a value. The condition of the same name stays; this one binds. */
    record Contains(List<RefExpression> select, String substring, String name) implements OutputNode {

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
                        String name) implements OutputNode {

        public FormatNumber {
            select = select == null ? List.of() : List.copyOf(select);
            if (picture == null || picture.isEmpty()) {
                throw new ConfigException("A format-number needs a picture");
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

    /** One entry of a {@link ValueMap} table. */
    record Entry(String from, String to) {

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
     * @param templateRef invoke this named template rather than dispatching, while still
     *                    matching content — a hybrid of apply and call
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
                          String templateRef,
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
