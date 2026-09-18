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

import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The document's scorer set applied to a step (design §8.4): every setting whose scorer has a signal on
 * the step is scored, judged against its threshold, and weighted into a total. A setting whose scorer is
 * not installed, or whose parameters its scorer refuses, is a configuration fault and is refused when
 * the scorecard is built, not silently skipped or failed stream by stream at scoring time.
 */
public final class Scorecard {

    private static final Set<ScorerType> MEANING = EnumSet.of(
            ScorerType.SCHEMA_CONFORMANCE, ScorerType.EXTRACTION_QUALITY, ScorerType.BUSINESS_RULES,
            ScorerType.EVENT_CLASSIFICATION);

    private final List<ScorerSetting> settings;
    private final Map<ScorerType, Scorer> scorers;

    public Scorecard(final List<ScorerSetting> settings, final List<Scorer> scorers) {
        this.settings = List.copyOf(settings);
        this.scorers = scorers.stream().collect(Collectors.toUnmodifiableMap(Scorer::type, Function.identity()));
        for (final ScorerSetting setting : settings) {
            if (!this.scorers.containsKey(setting.getType())) {
                throw new IllegalArgumentException("No scorer is installed for " + setting.getType());
            }
            this.scorers.get(setting.getType()).validate(setting.getParameters());
        }
    }

    /**
     * This scorecard with only the scorers of meaning — those that judge what the output says, not how
     * much of the input it took or how many records it made — for judging one event on its own.
     */
    public Scorecard meaning() {
        return new Scorecard(settings.stream().filter(setting -> MEANING.contains(setting.getType())).toList(),
                List.copyOf(scorers.values()));
    }

    public Verdict judge(final Attempted step) {
        final List<Judgement> judgements = new ArrayList<>();
        double weightedSum = 0.0;
        double weights = 0.0;
        for (final ScorerSetting setting : settings) {
            final Optional<Score> score = scorers.get(setting.getType()).score(setting.getParameters(), step);
            if (score.isPresent()) {
                judgements.add(new Judgement(setting, score.get()));
                weightedSum += setting.getWeight() * score.get().value();
                weights += setting.getWeight();
            }
        }
        return new Verdict(List.copyOf(judgements), weights == 0.0
                ? 1.0
                : weightedSum / weights);
    }
}
