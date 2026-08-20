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

import java.util.List;
import java.util.Map;

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

    /** Substitute characters one for one. XSLT: {@code translate()}. */
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

    /** Take part of a value. XSLT: {@code substring()}. */
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
     */
    record ApplyDirective(RefExpression select,
                          String mode,
                          List<Param> withParam,
                          int maxDepth,
                          String templateRef,
                          boolean ignoreErrors) {

        /** How deep recursion goes before the engine calls it a runaway. */
        public static final int DEFAULT_MAX_DEPTH = 64;

        public ApplyDirective {
            withParam = withParam == null ? List.of() : List.copyOf(withParam);
        }
    }

    /** Convenience for the common shape of a parameter list. */
    static List<Param> params(final Map<String, RefExpression> values) {
        return values.entrySet().stream().map(e -> new Param(e.getKey(), e.getValue())).toList();
    }
}
