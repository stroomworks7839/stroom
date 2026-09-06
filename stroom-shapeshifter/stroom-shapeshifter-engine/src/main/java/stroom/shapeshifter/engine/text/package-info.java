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
 * Text encodings, at the boundaries where they matter.
 *
 * <p>The engine matches bytes (D13), so an encoding is needed only to turn a configuration's
 * delimiters into bytes to look for ({@code Encoding}, {@code RegexEncodings}), to turn captured
 * bytes back into text on the way out, and — for the family the regex library cannot match in
 * — to transcode a whole source to UTF-8 before the window sees it ({@code Transcode}, design 19
 * phase 6). Everything between those points is encoding-agnostic by construction.
 */
package stroom.shapeshifter.engine.text;
