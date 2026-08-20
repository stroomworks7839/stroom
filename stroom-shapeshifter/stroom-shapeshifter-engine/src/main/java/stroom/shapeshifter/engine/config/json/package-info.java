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
 * Reading and writing the configuration model as JSON.
 *
 * <p>One class, {@code ProjectJson}, holds the whole wire format. It exists as a seam: the model
 * it produces has no dependency on it, so a different serialiser — or a hand-written reader with
 * no third-party dependency at all — can be substituted without touching anything else.
 */
package stroom.shapeshifter.engine.config.json;
