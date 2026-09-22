/*
 * Copyright 2016 Crown Copyright
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

package stroom.shapeshifter.client.presenter;

import stroom.pipeline.shared.SourceLocation;

/**
 * Where the sample came from (design 18 Q2, design 44 §5). The document never holds data, so
 * the editor holds only this — a record to read, or text an author pasted — and sends it with
 * every run. A record is re-read each time, so a feed that moved on is seen to have moved on.
 */
public final class SampleSource {

    /** The panel's row for the sample, distinct from any template's id or a part's row. */
    private static final String ROW = "sample:";

    private final SourceLocation location;
    private final String text;
    private final String label;

    private SampleSource(final SourceLocation location, final String text, final String label) {
        this.location = location;
        this.text = text;
        this.label = label;
    }

    /** The panel row that stands for the sample. */
    public static String rowId() {
        return ROW;
    }

    public static boolean isRow(final String rowId) {
        return ROW.equals(rowId);
    }

    /** A record of a stream, read by the server under the caller's permissions. */
    public static SampleSource record(final SourceLocation location, final String feed) {
        final StringBuilder said = new StringBuilder();
        said.append(feed == null
                ? "stream"
                : feed).append(' ').append(location.getMetaId());
        if (location.getPartIndex() > 0) {
            said.append(" · part ").append(location.getPartIndex() + 1);
        }
        if (location.getRecordIndex() > 0) {
            said.append(" · record ").append(location.getRecordIndex() + 1);
        }
        return new SampleSource(location, null, said.toString());
    }

    /** Text an author pasted. */
    public static SampleSource pasted(final String text) {
        final int lines = text.isEmpty()
                ? 0
                : text.split("\n", -1).length;
        return new SampleSource(null, text, "pasted · " + lines + (lines == 1
                ? " line"
                : " lines"));
    }

    public SourceLocation getLocation() {
        return location;
    }

    /** The text, for a pasted sample; null for a record, which the server reads. */
    public String getText() {
        return text;
    }

    /** What the crumb says the sample is. */
    public String getLabel() {
        return label;
    }
}
