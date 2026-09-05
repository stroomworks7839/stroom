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

package stroom.shapeshifter.engine.exec;

/**
 * A fatal emission ends the run; the message is already recorded when this flies, so it
 * carries nothing and fills in no stack. Thrown by the run, the level, the body and the
 * function runtime alike, and caught in one place: where the run turns it into its last word.
 */
final class AbortRun extends RuntimeException {

    AbortRun() {
        super(null, null, false, false);
    }
}
