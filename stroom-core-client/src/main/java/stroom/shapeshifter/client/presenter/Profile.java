/*
 * Copyright 2016 Crown Copyright
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

package stroom.shapeshifter.client.presenter;

import stroom.shapeshifter.shared.ShapeshifterTrace.Timing;

import java.util.List;

/**
 * The run's timing as the editor shows it (design 18 §5.8): every run profiles, and the display
 * leads with shares, ratios and heat, keeping absolutes as detail - single-run timing under
 * instrumentation is a diagnostic, not a benchmark. Every reading is of a run: callers ask once
 * there is a trace, and say "before a run" themselves.
 */
public final class Profile {

    private Profile() {
    }

    /** The template's share of the time spent attempting, 0..1. */
    public static double share(final TraceModel trace, final String templateId) {
        final Timing timing = trace.timing(templateId);
        final long total = attemptedNanos(trace);
        return timing == null || total == 0
                ? 0
                : (double) timing.getNanos() / total;
    }

    /**
     * How the template's cost per attempt compares with the run's: 0 within it, 1 up to three
     * times it, 2 beyond - the colour of the heat bar, so that a zero-match template with a
     * long hot bar reads "matched nothing, consumed half the run".
     */
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

    /** The full numbers: attempts, matched with the hit rate, µs per attempt, total, share. */
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

    /** The whole run's line: what the document row says. */
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

    /** Nanoseconds as microseconds, to one decimal under ten, whole above; milliseconds past a thousand. */
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
