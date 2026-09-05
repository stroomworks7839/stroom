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

import stroom.shapeshifter.engine.Instrument;
import stroom.shapeshifter.engine.Message;
import stroom.shapeshifter.engine.Severity;
import stroom.shapeshifter.engine.compile.CompiledProject;
import stroom.shapeshifter.engine.function.Arguments;
import stroom.shapeshifter.engine.function.FunctionCall;
import stroom.shapeshifter.engine.function.FunctionContext;
import stroom.shapeshifter.engine.function.FunctionDefinition;
import stroom.shapeshifter.engine.function.FunctionFailure;
import stroom.shapeshifter.engine.function.Purity;
import stroom.shapeshifter.engine.function.RunMode;
import stroom.shapeshifter.engine.function.Services;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The functions bound to one run (design 26): bound once before any input is read, called
 * by name with the context each call may reach — the run's messages, the calling match's
 * offset and length, the record count, a state map shared by every call, the configured
 * services — and, in preview, the impure ones not called at all.
 */
final class FunctionRuntime {

    private final CompiledProject compiled;
    private final RunMode mode;
    private final Services services;
    private final List<Message> messages;
    private final Map<String, FunctionCall> library = new HashMap<>();
    private final Map<String, Object> state = new HashMap<>();
    private final Set<String> notRunInPreview = new HashSet<>();
    private long callOffset = Instrument.UNLOCATABLE;
    private long callLength = -1;
    private long records;

    FunctionRuntime(final CompiledProject compiled,
                    final RunMode mode,
                    final Services services,
                    final List<Message> messages) {
        this.compiled = compiled;
        this.mode = mode;
        this.services = services;
        this.messages = messages;
    }

    /**
     * Bind every function the configuration calls, once, before the run (design 26 §3). A
     * definition that cannot be bound — a service it needs is missing — is the run's first and
     * last message, FATAL, rather than an exception through the caller.
     */
    void bind() {
        for (final FunctionDefinition definition : compiled.functions()) {
            try {
                library.put(definition.name(), definition.bind(new Context(definition.name())));
            } catch (final RuntimeException e) {
                messages.add(new Message(Severity.FATAL,
                        definition.name() + ": could not be bound to this run: " + describe(e)));
                throw new AbortRun();
            }
        }
    }

    /** One more top-level record has begun; what {@link FunctionContext#recordNumber()} answers. */
    void countRecord() {
        records++;
    }

    /**
     * Whether a call to this definition is skipped because the run is a preview and the
     * function is impure (design 26 §4) — said once per function per run.
     */
    boolean skippedInPreview(final FunctionDefinition definition) {
        if (mode != RunMode.PREVIEW || definition.purity() != Purity.IMPURE) {
            return false;
        }
        if (notRunInPreview.add(definition.name())) {
            messages.add(new Message(Severity.WARNING, definition.name() + ": not run in preview"));
        }
        return true;
    }

    /**
     * Call a bound function with the calling match's place in the input, which its context
     * answers for the call's duration and not after. A failure the function declares
     * ({@link FunctionFailure}) is FATAL and ends the run; any other exception is an ERROR and
     * the call produced nothing.
     *
     * @return the function's result, or null for nothing
     */
    TypedValue invoke(final String function, final Arguments arguments, final long offset, final long length) {
        final FunctionCall bound = library.get(function);
        callOffset = offset;
        callLength = length;
        try {
            return bound.call(arguments);
        } catch (final FunctionFailure e) {
            messages.add(new Message(Severity.FATAL, function + ": " + e.getMessage()));
            throw new AbortRun();
        } catch (final RuntimeException e) {
            messages.add(new Message(Severity.ERROR, function + ": " + describe(e)));
            return null;
        } finally {
            callOffset = Instrument.UNLOCATABLE;
            callLength = -1;
        }
    }

    /** An exception as a message: its own message, or its class when it has none. */
    private static String describe(final RuntimeException e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getName();
    }

    /** What a bound function may reach (design 26 §2), for one function by name. */
    private final class Context implements FunctionContext {

        private final String function;

        private Context(final String function) {
            this.function = function;
        }

        @Override
        public void warn(final String message) {
            messages.add(new Message(Severity.WARNING, function + ": " + message));
        }

        @Override
        public void error(final String message) {
            messages.add(new Message(Severity.ERROR, function + ": " + message));
        }

        @Override
        public long inputOffset() {
            return callOffset;
        }

        @Override
        public long inputLength() {
            return callLength;
        }

        @Override
        public long recordNumber() {
            return records;
        }

        @Override
        public void message(final Severity severity, final String message) {
            messages.add(new Message(severity, function + ": " + message));
        }

        @Override
        public Map<String, Object> state() {
            return state;
        }

        @Override
        public <T> T service(final Class<T> type) {
            return type.cast(services.lookup(type));
        }
    }
}
