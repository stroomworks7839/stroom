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

package stroom.shapeshifter.client.model;

import stroom.shapeshifter.config.MatchExpression;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.shared.ShapeshifterTrace.Timing;

import java.util.List;
import java.util.Objects;

/// The run's timing as the editor shows it (design 18 §5.8): every run profiles, and the display
/// leads with shares, ratios and heat, keeping absolutes as detail - single-run timing under
/// instrumentation is a diagnostic, not a benchmark. Every reading is of a run: callers ask once
/// there is a trace, and say "before a run" themselves.
public final class Profile {

    private Profile() {
    }

    /// Whether the run tried the template, which is when it has results to show (design 48 C5).
    ///
    /// @param trace The run.
    /// @param templateId The template.
    /// @return Whether it was tried at all.
    public static boolean tried(final TraceModel trace, final String templateId) {
        final Timing timing = trace.timing(templateId);
        return timing != null && timing.getAttempts() > 0;
    }

    /// Whether the run could have tried the template and did not: it tried another of the same
    /// mode, which took everything before this one's turn came. That is worth saying. Of a mode
    /// the run never dispatched into, it is not: nothing in it was tried, and the template is
    /// only not built yet, or not reached (design 48 C5). The document is run and not tried, so
    /// it is neither passed over nor what passes another over.
    ///
    /// @param project The project.
    /// @param trace The run.
    /// @param template The template.
    /// @return Whether it was passed over.
    public static boolean passedOver(final Project project, final TraceModel trace, final Template template) {
        if (isDocument(template) || tried(trace, template.id())) {
            return false;
        }
        for (final Template other : project.templates()) {
            if (!isDocument(other) && Objects.equals(other.mode(), template.mode()) && tried(trace, other.id())) {
                return true;
            }
        }
        return false;
    }

    /// What the run made of a template, in the few words a heading has room for: the numbers in
    /// full are [#describe]'s.
    ///
    /// @param project The project.
    /// @param trace The run.
    /// @param template The template.
    /// @return The words, or null where the run has nothing to say of it.
    public static String summary(final Project project, final TraceModel trace, final Template template) {
        if (tried(trace, template.id())) {
            final Timing timing = trace.timing(template.id());
            return (template.consume()
                    ? "skipped "
                    : "matched ") + timing.getMatched() + "/" + timing.getAttempts();
        }
        return passedOver(project, trace, template)
                ? "not tried"
                : null;
    }

    private static boolean isDocument(final Template template) {
        return template.match() instanceof MatchExpression.Source;
    }

    /// The template's share of the time spent attempting, 0..1.
    public static double share(final TraceModel trace, final String templateId) {
        final Timing timing = trace.timing(templateId);
        final long total = attemptedNanos(trace);
        return timing == null || total == 0
                ? 0
                : (double) timing.getNanos() / total;
    }

    /// How the template's cost per attempt compares with the run's: 0 within it, 1 up to three
    /// times it, 2 beyond - the colour of the heat bar, so that a zero-match template with a
    /// long hot bar reads "matched nothing, consumed half the run".
    public static int cost(final TraceModel trace, final String templateId) {
        final Timing timing = trace.timing(templateId);
        if (timing == null || timing.getAttempts() == 0) {
            return 0;
        }
        final double runPerAttempt = perAttempt(trace);
        final double perAttempt = (double) timing.getNanos() / timing.getAttempts();
        return runPerAttempt == 0 || perAttempt <= runPerAttempt
                ? 0
                : perAttempt <= 3 * runPerAttempt
                        ? 1
                        : 2;
    }

    /// The full numbers: attempts, matched with the hit rate, µs per attempt, total, share.
    public static String describe(final TraceModel trace, final String templateId) {
        final Timing timing = trace.timing(templateId);
        if (timing == null || timing.getAttempts() == 0) {
            return "not tried in this run";
        }
        return timing.getAttempts() + " attempts · " + timing.getMatched() + " matched ("
               + percent((double) timing.getMatched() / timing.getAttempts()) + ") · "
               + micros((double) timing.getNanos() / timing.getAttempts()) + " per attempt · "
               + micros(timing.getNanos()) + " · " + percent(share(trace, templateId)) + " of the run";
    }

    /// The whole run's line: what the document row says.
    public static String runTotal(final TraceModel trace) {
        return micros(trace.trace().getRunNanos()) + " · " + attempts(trace) + " attempts";
    }

    private static List<Timing> timings(final TraceModel trace) {
        return trace.trace().getTimings() == null
                ? List.of()
                : trace.trace().getTimings();
    }

    private static long attempts(final TraceModel trace) {
        long attempts = 0;
        for (final Timing timing : timings(trace)) {
            attempts += timing.getAttempts();
        }
        return attempts;
    }

    private static long attemptedNanos(final TraceModel trace) {
        long total = 0;
        for (final Timing timing : timings(trace)) {
            total += timing.getNanos();
        }
        return total;
    }

    private static double perAttempt(final TraceModel trace) {
        final long attempts = attempts(trace);
        return attempts == 0
                ? 0
                : (double) attemptedNanos(trace) / attempts;
    }

    static String percent(final double ratio) {
        return Math.round(ratio * 100) + "%";
    }

    /// Nanoseconds as microseconds, to one decimal under ten, whole above; milliseconds past a thousand.
    static String micros(final double nanos) {
        final double micros = nanos / 1000;
        if (micros >= 1000) {
            return Math.round(micros / 100) / 10.0 + " ms";
        }
        if (micros >= 10) {
            return Math.round(micros) + " µs";
        }
        return Math.round(micros * 10) / 10.0 + " µs";
    }
}
