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
 */
package stroom.shapeshifter.engine.ds3;
