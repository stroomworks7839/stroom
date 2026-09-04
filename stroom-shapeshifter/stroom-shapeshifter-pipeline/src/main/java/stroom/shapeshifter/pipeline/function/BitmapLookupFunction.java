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

import stroom.pipeline.refdata.LookupIdentifier;
import stroom.pipeline.refdata.ReferenceDataResult;
import stroom.pipeline.refdata.store.RefDataValueProxy;
import stroom.util.date.DateUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code bitmap-lookup}: as {@code stroom.pipeline.xsltfunctions.BitmapLookup}: the key is a
 * number, decimal or {@code 0x} hex, and each set bit's position is looked up as a key of its
 * own; the values found, in bit order, joined with a comma (design 26 ruling 4) where the Saxon
 * class returns them as a sequence.
 */
public final class BitmapLookupFunction extends AbstractLookupFunction {

    public BitmapLookupFunction() {
        super("bitmap-lookup");
    }

    @Override
    String lookup(final Call call, final LookupIdentifier identifier) {
        final int[] bits = bits(identifier.getKey());
        final List<String> values = new ArrayList<>();
        final List<String> failed = new ArrayList<>();
        for (final int bit : bits) {
            final LookupIdentifier bitIdentifier = identifier.cloneWithNewKey(String.valueOf(bit));
            final ReferenceDataResult result = call.referenceData(bitIdentifier);
            if (result.getRefDataValueProxy().isEmpty()) {
                call.explainFailure(result);
                failed.add(String.valueOf(bit));
                continue;
            }
            final RefDataValueProxy proxy = result.getRefDataValueProxy().get();
            final String value = call.render(proxy);
            call.explainValue(value != null, result);
            if (value != null) {
                values.add(value);
            } else {
                failed.add(String.valueOf(bit));
            }
        }
        if (!failed.isEmpty()) {
            call.context.warn("Lookup failed (map = " + identifier.getPrimaryMapName() + ", keys = {"
                              + String.join(",", failed) + "}, eventTime = "
                              + DateUtil.createNormalDateTimeString(identifier.getEventTime()) + ")");
        }
        return values.isEmpty() ? null : String.join(",", values);
    }

    /** The positions of the set bits, lowest first, as {@code Bitmap.getBits} does. */
    static int[] bits(final String key) {
        final int value;
        try {
            value = key.startsWith("0x") ? Integer.valueOf(key.substring(2), 16) : Integer.parseInt(key);
        } catch (final NumberFormatException e) {
            throw new NumberFormatException("unable to parse number '" + key + "'");
        }
        final List<Integer> bits = new ArrayList<>();
        int remaining = value;
        int bit = 0;
        while (remaining > 0) {
            if ((remaining & 1) != 0) {
                bits.add(bit);
            }
            remaining = remaining >> 1;
            bit++;
        }
        return bits.stream().mapToInt(Integer::intValue).toArray();
    }
}
