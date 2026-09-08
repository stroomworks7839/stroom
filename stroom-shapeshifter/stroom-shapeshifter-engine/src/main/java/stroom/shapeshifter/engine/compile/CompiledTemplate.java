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

import stroom.shapeshifter.engine.config.CaptureBinding;
import stroom.shapeshifter.engine.config.MatchExpression;
import stroom.shapeshifter.engine.config.Template;
import stroom.shapeshifter.engine.text.Encoding;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A template, its compiled match, and its compiled body, together, with what the match loop
 * would otherwise ask the authored {@link Template} for decided here (design 29 §3.1, D51), so
 * that the match loop reads a field rather than walking to the model. Build one with
 * {@link #of}, which derives those components; the canonical constructor takes them already
 * derived and does not check them against the template.
 *
 * <p>Inlined rather than looked up because the match loop touches this once per candidate per
 * position, which is the hottest thing the engine does — and what a match runs next is its
 * body, already compiled.
 *
 * @param template     the authored template, carried for its name, its identifier, its
 *                     messages and its guard expression; the facts below are decided once here
 *                     rather than read from it per candidate (design 29 §3.1)
 * @param match        its compiled match expression
 * @param body         its compiled body
 * @param encoding     the template's declared encoding override, parsed and validated (E3), or
 *                     null to inherit the run's — which a byte-order mark may still have
 *                     replaced
 * @param captures     its bindings, compiled: where each reads and the cast it declares (D50)
 * @param maxMatch     how many matches count before the template stops being tried, or
 *                     {@link Template.MatchLimits#UNLIMITED}; read per candidate per pass
 * @param consume      whether this is an eater, whose matches advance without counting (D36)
 * @param contentGroup the group a match hands its body: the field for a delimiter template,
 *                     which is group 1, and the whole match for every other
 * @param onlyMatch    the match indices whose bodies run, or null for all of them
 * @param guarded      whether the template has a guard to evaluate on the way into a level
 * @param clearNames   the captures whose stores a first match clears: every binding but a
 *                     key-value one, which names its own. The compiler's array, never written;
 *                     handed out rather than copied because the run iterates it per record
 * @param captureNames every binding's name, which a recursive apply shadows. The compiler's
 *                     array, on the same terms
 */
public record CompiledTemplate(Template template,
                               CompiledMatch match,
                               List<CompiledOp> body,
                               Encoding encoding,
                               List<CompiledCapture> captures,
                               int maxMatch,
                               boolean consume,
                               int contentGroup,
                               Set<Integer> onlyMatch,
                               boolean guarded,
                               String[] clearNames,
                               String[] captureNames) {

    public CompiledTemplate {
        body = List.copyOf(body);
        captures = List.copyOf(captures);
    }

    /**
     * Compile a template, deciding here everything the match loop would otherwise ask the
     * authored {@link Template} for on every candidate, every match and every level entry
     * (design 29 §3.1, D51). The model stays the model, carried for names, identifiers and
     * messages; the loop reads the fields beside it.
     */
    static CompiledTemplate of(final Template template,
                               final CompiledMatch match,
                               final List<CompiledOp> body,
                               final Encoding encoding,
                               final List<CompiledCapture> captures) {
        final List<String> clear = new ArrayList<>();
        final List<String> named = new ArrayList<>();
        for (final CaptureBinding capture : template.captures()) {
            named.add(capture.name());
            if (!(capture.select() instanceof CaptureBinding.CaptureSource.KeyValue)) {
                clear.add(capture.name());
            }
        }
        return new CompiledTemplate(template, match, body, encoding, captures,
                template.matchLimits().maxMatch(),
                template.consume(),
                // A delimiter template's content is the field, group 1; every other template's
                // is the whole match. The group that carries the delimiter too is not it.
                template.match() instanceof MatchExpression.Delimiter ? 1 : 0,
                template.matchLimits().onlyMatch(),
                template.guard() != null,
                clear.toArray(String[]::new),
                named.toArray(String[]::new));
    }
}
