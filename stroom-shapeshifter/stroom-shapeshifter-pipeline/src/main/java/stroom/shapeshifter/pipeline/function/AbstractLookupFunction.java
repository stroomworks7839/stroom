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

package stroom.shapeshifter.pipeline.function;

import stroom.docref.DocRef;
import stroom.meta.shared.Meta;
import stroom.pipeline.refdata.LookupIdentifier;
import stroom.pipeline.refdata.ReferenceData;
import stroom.pipeline.refdata.ReferenceDataResult;
import stroom.pipeline.refdata.ReferenceDataResult.LazyMessage;
import stroom.pipeline.refdata.store.FastInfosetUtil;
import stroom.pipeline.refdata.store.FastInfosetValue;
import stroom.pipeline.refdata.store.RefDataValueProxy;
import stroom.pipeline.refdata.store.RefStreamDefinition;
import stroom.pipeline.refdata.store.StringValue;
import stroom.pipeline.shared.data.PipelineReference;
import stroom.shapeshifter.engine.Severity;
import stroom.shapeshifter.engine.function.Arguments;
import stroom.shapeshifter.engine.function.FunctionCall;
import stroom.shapeshifter.engine.function.FunctionContext;
import stroom.shapeshifter.engine.function.Kind;
import stroom.shapeshifter.engine.function.Purity;
import stroom.shapeshifter.engine.function.Signature;
import stroom.shapeshifter.engine.value.TypedValue;
import stroom.shapeshifter.pipeline.ElementServices;
import stroom.util.date.DateUtil;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * What {@code lookup} and {@code bitmap-lookup} share, as {@code stroom.pipeline.xsltfunctions.
 * AbstractLookup}: the map, the key, an optional lookup time (the stream's creation time
 * otherwise), {@code ignoreWarnings} and {@code trace}; the reference data reached through the
 * element's {@code pipelineReference} properties; and the messages Stroom's lookups write. A
 * value is text: a string value as it is, an XML value serialised (design 26 ruling 4).
 */
abstract class AbstractLookupFunction extends StroomFunction {

    AbstractLookupFunction(final String name) {
        super(name, Signature.of(2, Kind.STRING, Kind.STRING, Kind.STRING, Kind.STRING, Kind.BOOLEAN, Kind.BOOLEAN),
                Purity.CONTEXT);
    }

    /** The lookup itself, once the identifier is settled; null for nothing found. */
    abstract String lookup(Call call, LookupIdentifier identifier);

    @Override
    public FunctionCall bind(final FunctionContext context) {
        return new Call(context);
    }

    /** One run's lookups: the default time settled once, the messages routed to the run. */
    final class Call implements FunctionCall {

        final FunctionContext context;
        private long defaultMs = -1;
        boolean ignoreWarnings;
        boolean trace;

        private Call(final FunctionContext context) {
            this.context = context;
        }

        @Override
        public TypedValue call(final Arguments arguments) {
            final String map = requiredString(context, arguments, 0);
            final String key = requiredString(context, arguments, 1);
            ignoreWarnings = Boolean.TRUE.equals(arguments.bool(3));
            trace = Boolean.TRUE.equals(arguments.bool(4));
            long ms = defaultMs();
            if (arguments.size() > 2) {
                // Written but absent is Stroom's "empty date": a warning and no value, not the default.
                final String time = arguments.string(2);
                try {
                    ms = DateUtil.parseNormalDateTimeString(Objects.requireNonNull(time));
                } catch (final RuntimeException e) {
                    if (!ignoreWarnings) {
                        context.warn(time == null
                                ? "Lookup failed to parse empty date"
                                : "Lookup failed to parse date: " + time);
                    }
                    return null;
                }
            }
            final LookupIdentifier identifier;
            try {
                identifier = new LookupIdentifier(map, key, ms);
            } catch (final RuntimeException e) {
                context.error("Identifier must have a map and a key (map: " + map + ", key: " + key
                              + ", lookup time: " + ms + ") " + e.getMessage());
                return null;
            }
            if (pipelineReferences().isEmpty()) {
                say(Severity.ERROR, "No reference loaders have been added to this element to perform a lookup",
                        identifier, null);
                return null;
            }
            try {
                return text(lookup(this, identifier));
            } catch (final RuntimeException e) {
                context.error("Error performing lookup " + describe(identifier) + " " + e.getMessage());
                return null;
            }
        }

        private long defaultMs() {
            if (defaultMs == -1) {
                final Meta meta = Holders.meta(context);
                defaultMs = meta != null ? meta.getCreateMs() : System.currentTimeMillis();
            }
            return defaultMs;
        }

        List<PipelineReference> pipelineReferences() {
            final ElementServices.PipelineReferences references =
                    context.service(ElementServices.PipelineReferences.class);
            return references == null ? List.of() : references.references();
        }

        /** The reference data for an identifier, as Stroom's {@code getReferenceData}: a result to read. */
        ReferenceDataResult referenceData(final LookupIdentifier identifier) {
            final ReferenceDataResult result = new ReferenceDataResult(identifier, trace, ignoreWarnings);
            final ReferenceData referenceData = context.service(ReferenceData.class);
            if (referenceData == null) {
                return result;
            }
            return referenceData.ensureReferenceDataAvailability(pipelineReferences(), identifier, result);
        }

        /**
         * A found value as text, or null: the string as it is, XML serialised, a null value absent.
         * Read through {@code consumeBytes}, as Stroom's off-heap consumer does: the off-heap store's
         * value is a view into its LMDB read transaction and dies with it, so the bytes are copied
         * inside the consumer and decoded after ({@code supplyValue} would hand back the dead view).
         */
        String render(final RefDataValueProxy proxy) {
            final byte[] type = new byte[1];
            final byte[][] copy = new byte[1][];
            final boolean found = proxy.consumeBytes(typed -> {
                type[0] = typed.getTypeId();
                final ByteBuffer view = typed.getByteBuffer().duplicate();
                copy[0] = new byte[view.remaining()];
                view.get(copy[0]);
            });
            if (!found || copy[0] == null) {
                return null;
            }
            return switch (type[0]) {
                // Stroom's StringSerde is plain UTF-8; decoded here rather than reaching into stroom-lmdb.
                case StringValue.TYPE_ID -> new String(copy[0], StandardCharsets.UTF_8);
                case FastInfosetValue.TYPE_ID -> FastInfosetUtil.byteBufferToString(ByteBuffer.wrap(copy[0]))
                        .replaceFirst("^<\\?xml[^>]*\\?>\\s*", "")
                        .stripTrailing();
                default -> null;
            };
        }

        /** Stroom's {@code logFailureReason}: why nothing was found, at the severity it earns. */
        void explainFailure(final ReferenceDataResult result) {
            final Severity max = maxSeverity(result);
            if (!ignoreWarnings && result.getEffectiveStreams().isEmpty()) {
                final String feeds = pipelineReferences().stream()
                        .map(reference -> reference.getFeed() == null ? null : reference.getFeed().getName())
                        .filter(Objects::nonNull)
                        .map(name -> "'" + name + "'")
                        .collect(Collectors.joining(", "));
                say(atLeast(max, Severity.WARNING),
                        "No effective streams found in any of the reference loaders (feeds: [" + feeds
                        + "]). Do reference data streams exist for the lookup time?",
                        result.getCurrentLookupIdentifier(), result);
            } else if (!ignoreWarnings && result.getQualifyingStreams().isEmpty()) {
                final String streams = result.getEffectiveStreams().stream()
                        .map(entry -> (entry.getKey().getFeed() == null ? null : entry.getKey().getFeed().getName())
                                      + ":" + entry.getValue().getStreamId())
                        .collect(Collectors.joining(", "));
                say(atLeast(max, Severity.WARNING), "Map not found in effective streams [" + streams + "]",
                        result.getCurrentLookupIdentifier(), result);
            } else if (max.compareTo(Severity.ERROR) >= 0) {
                say(atLeast(max, Severity.ERROR), "Errors found during lookup",
                        result.getCurrentLookupIdentifier(), result);
            }
        }

        /** Stroom's {@code logLookupValue}: found or not, and with what said on the way. */
        void explainValue(final boolean found, final ReferenceDataResult result) {
            final Severity max = maxSeverity(result);
            if (max.compareTo(Severity.WARNING) >= 0) {
                say(max, (found ? "Key found " : "Key not found ")
                         + (max.compareTo(Severity.ERROR) >= 0 ? "with errors" : "with warnings"),
                        result.getCurrentLookupIdentifier(), result);
            } else if (found) {
                say(Severity.INFO, "Key found", result.getCurrentLookupIdentifier(), result);
            } else {
                say(atLeast(max, Severity.WARNING), "Key not found", result.getCurrentLookupIdentifier(), result);
            }
        }

        /**
         * Stroom's {@code outputInfo}: said when tracing, when it is an error, or when it is a
         * warning and warnings are not ignored; with the identifier, the stream the value came
         * from and what the reference data said on the way.
         */
        void say(final Severity severity, final String message, final LookupIdentifier identifier,
                 final ReferenceDataResult result) {
            final boolean speak = trace || severity.compareTo(Severity.ERROR) >= 0
                                  || (!ignoreWarnings && severity.compareTo(Severity.WARNING) >= 0);
            if (!speak) {
                return;
            }
            final StringBuilder text = new StringBuilder(message);
            if (!message.endsWith(" ")) {
                text.append(' ');
            }
            text.append(describe(identifier));
            if (result != null) {
                result.getRefDataValueProxy()
                        .flatMap(RefDataValueProxy::getSuccessfulMapDefinition)
                        .map(definition -> definition.getRefStreamDefinition())
                        .map(RefStreamDefinition::getStreamId)
                        .ifPresent(streamId -> text.append(" found in stream: ").append(streamId));
                for (final LazyMessage lazy : result.getMessages()) {
                    final Severity lazySeverity = severity(lazy.getSeverity());
                    if (trace || lazySeverity.compareTo(Severity.ERROR) >= 0
                        || (!ignoreWarnings && lazySeverity.compareTo(Severity.WARNING) >= 0)) {
                        text.append("\n> ").append(lazy.getSeverity().getDisplayValue()).append(": ")
                                .append(lazy.getMessage());
                    }
                }
            }
            context.message(severity, text.toString());
        }

        private Severity maxSeverity(final ReferenceDataResult result) {
            Severity max = Severity.INFO;
            for (final LazyMessage lazy : result.getMessages()) {
                final Severity candidate = severity(lazy.getSeverity());
                if (candidate.compareTo(max) > 0) {
                    max = candidate;
                }
            }
            return max;
        }
    }

    static String describe(final LookupIdentifier identifier) {
        final StringBuilder sb = new StringBuilder();
        identifier.appendTo(sb);
        return sb.toString();
    }

    static Severity atLeast(final Severity severity, final Severity floor) {
        return severity.compareTo(floor) >= 0 ? severity : floor;
    }

    static Severity severity(final stroom.util.shared.Severity severity) {
        return switch (severity) {
            case INFO -> Severity.INFO;
            case WARNING -> Severity.WARNING;
            case ERROR -> Severity.ERROR;
            case FATAL_ERROR -> Severity.FATAL;
        };
    }
}
