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

package stroom.shapeshifter.engine.fixture;

/**
 * Thrown by {@link EngineHarness} for a path the port has not reached yet.
 *
 * <p>It exists so that "not written" and "written and wrong" are different words. A fixture
 * whose status is {@code PENDING} is expected to fail; this exception says it failed because
 * nothing has been built, which is the only acceptable reason during the early phases.
 */
public class PortPendingException extends RuntimeException {

    public PortPendingException(final String message) {
        super(message);
    }
}
