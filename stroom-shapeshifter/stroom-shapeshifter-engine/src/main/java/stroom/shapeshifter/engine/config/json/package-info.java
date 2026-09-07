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

/**
 * The wire format: the JSON a configuration is stored as, read into a {@code Project} and
 * written back from one.
 *
 * <p>{@code ProjectJson} is the way in and out; the families a template is made of have a
 * class each — {@code MatchJson}, {@code ReferenceJson}, {@code ConditionJson},
 * {@code OutputJson} — with a family's reader and writer together, because the round trip is
 * the property that matters; {@code JsonFields} holds the primitives they all use and states
 * the format's rules. The model's records do not depend on this package — {@code ProjectReader}
 * is the one door in {@code config} that does — so the model stays a plain tree of records
 * that a different serialiser, or none, could carry.
 */
package stroom.shapeshifter.engine.config.json;
