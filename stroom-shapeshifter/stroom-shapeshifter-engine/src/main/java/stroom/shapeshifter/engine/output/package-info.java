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
 * Output: the sinks a run writes to.
 *
 * <p>{@code XmlByteSink} serialises the structure a run writes into bytes exactly as Saxon
 * would, and is the default sink; {@code SaxEventSink} forwards the same structure as SAX
 * events, counting events as its position; {@code CharacterSink} delivers a text
 * configuration's writes as {@code characters} events. {@code Utf8} is what the three share:
 * where a buffer's trailing bytes begin a character the next write will finish. This package
 * depends on the root package's sink contract and on the JDK, and nothing inside the engine
 * depends on it but the body's variable buffer (design 27 §2.5, ruling 8).
 */
package stroom.shapeshifter.engine.output;
