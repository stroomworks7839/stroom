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
 * The Shapeshifter engine: the layers above matching.
 * <p>
 * {@code stroom-shapeshifter-regex} answers one question — does this byte region match, and
 * what did its groups capture — and is deliberately a standalone, zero-dependency library.
 * This module is where the rest of the design lands (decision D12): the record/structure
 * layer that walks input with those matchers, the transforms over what they capture, and the
 * output side that replaces what XSLT does for DS3 today. Decision D10's pipeline adapter
 * ({@code stroom-shapeshifter-pipeline}) will sit above this in turn, keeping Stroom's own types
 * out of both library modules.
 */
package stroom.shapeshifter.engine;
