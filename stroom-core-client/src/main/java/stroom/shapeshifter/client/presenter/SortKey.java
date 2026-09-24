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

import stroom.shapeshifter.config.Cast;
import stroom.shapeshifter.config.OutputNode.Order;
import stroom.shapeshifter.config.OutputNode.Sort;

/**
 * One row of the sort editor — <i>by · order · as</i> (design 44 §5ac). The key is held as the
 * text the field shows rather than as a reference, because that is what the author is editing;
 * the presenter reads it when the dialog is accepted, as it does every other field.
 */
public final class SortKey {

    private final String by;
    private final Order order;
    private final Cast as;

    public SortKey(final String by, final Order order, final Cast as) {
        this.by = by == null
                ? ""
                : by;
        this.order = order == null
                ? Order.ASCENDING
                : order;
        this.as = as;
    }

    /** A key of the configuration, spelt as the field shows it. */
    public static SortKey of(final Sort sort) {
        return new SortKey(Instructions.ref(sort.by()), sort.order(), sort.as());
    }

    public String getBy() {
        return by;
    }

    public Order getOrder() {
        return order;
    }

    public Cast getAs() {
        return as;
    }

    public boolean same(final SortKey other) {
        return other != null && by.equals(other.by) && order == other.order && as == other.as;
    }
}
