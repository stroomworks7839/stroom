/*
 * Copyright 2026 Crown Copyright
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

package stroom.shapeshifter.pipeline;

import stroom.pipeline.errorhandler.ProcessException;
import stroom.pipeline.filter.AbstractXMLFilter;
import stroom.pipeline.filter.NullXMLFilter;
import stroom.pipeline.filter.SAXEventRecorder;
import stroom.pipeline.filter.SAXRecordDetector;
import stroom.pipeline.filter.SplitFilter;
import stroom.pipeline.filter.XMLFilter;
import stroom.pipeline.filter.XMLFilterFork;
import stroom.pipeline.writer.AbstractWriter;
import stroom.pipeline.xml.converter.ParserFactory;
import stroom.util.shared.ElementId;

import java.util.ArrayList;
import java.util.List;

/// What a text configuration may be put in front of: a writer, and nothing else (design 24, ruled
/// 2026-11-24). A document of characters and no elements is nothing to any other element, and the
/// one that matters most does not say so: an `XSLTFilter` runs its stylesheet over it, every
/// template on an element matches nothing, and the result is silently wrong.
final class TextTargets {

    private TextTargets() {
        // Static utility
    }

    /// Refuses a configuration that writes text where what it feeds is not a writer, by name. A
    /// configuration that writes structure may feed anything, as may one that writes nothing, which
    /// a new document does; and an element with no target feeds nothing.
    ///
    /// @param elementId The element that runs the configuration.
    /// @param factory The configuration.
    /// @param target What the element feeds.
    static void check(final ElementId elementId, final ParserFactory factory, final XMLFilter target) {
        if (!(factory instanceof final ShapeshifterParserFactory shapeshifter) || !shapeshifter.writesText()) {
            return;
        }
        final List<String> refused = new ArrayList<>();
        collect(target, refused);
        if (!refused.isEmpty()) {
            throw ProcessException.create(
                    "The configuration of '" + name(elementId) + "' writes text, which only a writer can take, but "
                    + (refused.size() == 1
                            ? refused.getFirst() + " is not a writer"
                            : String.join(" and ", refused) + " are not writers")
                    + ". Put a TextWriter directly after it, or have the configuration write elements");
        }
    }

    private static void collect(final XMLFilter target, final List<String> refused) {
        if (target instanceof final XMLFilterFork fork) {
            for (final XMLFilter each : fork.getFilters()) {
                collect(each, refused);
            }
        } else if (target instanceof final AbstractXMLFilter filter && isSteppings(filter)) {
            collect(filter.getFilter(), refused);
        } else if (target != null && !(target instanceof NullXMLFilter) && !(target instanceof AbstractWriter)) {
            refused.add("'" + name(target) + "'");
        }
    }

    // What stepping puts between the elements of a pipeline it watches, which the author did not
    // and cannot take out: its recorders and detector, and the split it makes after a parser. That
    // split has no id, where one the author placed has, and an author's own drops text.
    private static boolean isSteppings(final AbstractXMLFilter filter) {
        return filter instanceof SAXEventRecorder
               || filter instanceof SAXRecordDetector
               || filter instanceof SplitFilter && filter.getElementId() == null;
    }

    private static String name(final XMLFilter filter) {
        return filter.getElementId() != null
                ? name(filter.getElementId())
                : filter.getClass().getSimpleName();
    }

    private static String name(final ElementId elementId) {
        return elementId == null
                ? "Shapeshifter"
                : elementId.toString();
    }
}
