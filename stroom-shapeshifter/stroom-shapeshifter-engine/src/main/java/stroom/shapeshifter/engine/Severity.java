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

package stroom.shapeshifter.engine;

/** How much a message matters. */
public enum Severity {

    /** Worth knowing. */
    INFO,
    /** Something was probably wrong with the data or the configuration. */
    WARNING,
    /** Something was wrong, and output is likely incomplete. */
    ERROR,
    /** Processing cannot continue. */
    FATAL
}
