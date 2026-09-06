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
 * Reading Data Splitter v3 configurations, and converting them to templates.
 *
 * <p>Nothing requires this — existing DS3 keeps running as it is — but DS3's fixtures come with
 * golden output produced by Java Stroom itself, which makes them the only external oracle this
 * engine has. Everything here exists so those fixtures can be run against it.
 *
 * <p>It is also the only place that parses reference text. Modern configurations build their
 * expressions structurally, and should keep doing so.
 *
 * <p>Not everything DS3 says is imported. {@code matchOrder} is refused by name — the engine's
 * dispatch attribute owns that decision now (E18/E20). Beyond that the import is strict rather
 * than lossy: an unknown attribute, a document element other than {@code <dataSplitter>}, or a
 * root child that is not an expression is a
 * {@link stroom.shapeshifter.engine.config.ConfigException}, because a configuration imported
 * with pieces silently dropped would be the quietly-wrong kind of success.
 */
package stroom.shapeshifter.engine.ds3;

