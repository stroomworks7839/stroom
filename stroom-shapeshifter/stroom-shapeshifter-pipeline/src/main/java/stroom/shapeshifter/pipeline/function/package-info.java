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
 * Stroom's XSLT functions as Shapeshifter functions (design 26 section 5), under Stroom's own
 * names. Each is a thin adapter over the helper the Saxon class calls, or, where the Saxon
 * class holds its logic inline, a carrying of that logic with the Saxon class named as the
 * thing to keep in step with. Group A, the pure functions and the clock, is here; the
 * pipeline's context and the lookups follow in their phases.
 */
package stroom.shapeshifter.pipeline.function;
