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

package stroom.shapeshifter.ai.scoring;

import stroom.shapeshifter.ai.stage.ShapeSignature;

import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XPathCompiler;
import net.sf.saxon.s9api.XPathSelector;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmNodeKind;
import net.sf.saxon.s9api.XdmValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A step's XML output as records — the children of its root, which is what both {@code records:2} and
 * {@code event-logging:3} make of one — with XPath over each. The scorers that judge the meaning of the
 * output (design 01 §8.4: extraction quality, business rules) read it through this. Paths are evaluated
 * with the root's namespace as the default element namespace, so a document writes
 * {@code EventSource/User/Id}, not a prefix per step.
 */
public final class OutputRecords {

    private static final Processor PROCESSOR = new Processor(false);

    private final XdmNode root;
    private final List<XdmNode> records;
    private final XPathCompiler compiler;

    private OutputRecords(final XdmNode root, final List<XdmNode> records) {
        this.root = root;
        this.records = records;
        this.compiler = PROCESSOR.newXPathCompiler();
        final String namespace = root.getNodeName().getNamespaceURI();
        if (!namespace.isEmpty()) {
            compiler.declareNamespace("", namespace);
        }
    }

    /**
     * The records a scorer of meaning judges: the output of a step that transformed records into records.
     * A parser's output is records too, but of the input's shape rather than the target's; design 01 §4
     * has the stream-level scorers judge that step, and these scorers judge what follows it. Empty where
     * the output does not parse, which the compile gate is there to say.
     */
    public static Optional<OutputRecords> ofTransformed(final Attempted step) {
        return step.parser()
                ? Optional.empty()
                : parse(step.result().output());
    }

    /**
     * Whether a path the document names can be compiled at all, so that a fault in a setting is refused
     * when the scorecard is built rather than failing every stream the stage sees.
     *
     * @throws IllegalArgumentException Naming the path and Saxon's complaint.
     */
    public static void check(final String xpath) {
        try {
            PROCESSOR.newXPathCompiler().compile(xpath);
        } catch (final SaxonApiException e) {
            throw new IllegalArgumentException("XPath '" + xpath + "' does not compile: " + e.getMessage(), e);
        }
    }

    /**
     * @return The output as records, or empty where the output is not markup or does not parse.
     */
    public static Optional<OutputRecords> parse(final String output) {
        if (output == null || !ShapeSignature.isMarkup(output)) {
            return Optional.empty();
        }
        try {
            final XdmNode document = PROCESSOR.newDocumentBuilder().build(ConfinedXml.source(output));
            XdmNode root = null;
            for (final XdmNode child : document.children()) {
                if (child.getNodeKind() == XdmNodeKind.ELEMENT) {
                    root = child;
                }
            }
            if (root == null) {
                return Optional.empty();
            }
            final List<XdmNode> records = new ArrayList<>();
            for (final XdmNode child : root.children()) {
                if (child.getNodeKind() == XdmNodeKind.ELEMENT) {
                    records.add(child);
                }
            }
            return Optional.of(new OutputRecords(root, records));
        } catch (final SaxonApiException e) {
            return Optional.empty();
        }
    }

    public List<XdmNode> records() {
        return records;
    }

    public XdmNode root() {
        return root;
    }

    /**
     * The effective boolean value of a path against one record: whether it selects anything, or is true.
     *
     * @throws IllegalArgumentException If the path does not compile: a fault in the document's setting,
     *                                  which a scorer reports rather than scores.
     */
    public boolean holds(final XdmNode record, final String xpath) {
        try {
            final XPathSelector selector = compiler.compile(xpath).load();
            selector.setContextItem(record);
            return selector.effectiveBooleanValue();
        } catch (final SaxonApiException e) {
            throw new IllegalArgumentException("XPath '" + xpath + "' cannot be evaluated: " + e.getMessage(), e);
        }
    }

    /**
     * A path against one record, as a value; {@code count(...)} and the like.
     */
    public XdmValue evaluate(final XdmNode record, final String xpath) {
        try {
            final XPathSelector selector = compiler.compile(xpath).load();
            selector.setContextItem(record);
            return selector.evaluate();
        } catch (final SaxonApiException e) {
            throw new IllegalArgumentException("XPath '" + xpath + "' cannot be evaluated: " + e.getMessage(), e);
        }
    }

    public long count(final XdmNode record, final String xpath) {
        final XdmValue value = evaluate(record, "count(" + xpath + ")");
        final XdmItem item = value.itemAt(0);
        return Long.parseLong(item.getStringValue());
    }
}
