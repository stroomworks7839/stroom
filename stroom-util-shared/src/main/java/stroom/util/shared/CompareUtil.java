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

package stroom.util.shared;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public final class CompareUtil {

    private CompareUtil() {
    }

    public static int compareLong(final Long l1, final Long l2) {
        if (l1 == null && l2 == null) {
            return 0;
        }
        if (l1 == null) {
            return -1;
        }
        if (l2 == null) {
            return +1;
        }
        return l1.compareTo(l2);
    }

    public static int compareInteger(final Integer l1, final Integer l2) {
        if (l1 == null && l2 == null) {
            return 0;
        }
        if (l1 == null) {
            return -1;
        }
        if (l2 == null) {
            return +1;
        }
        return l1.compareTo(l2);
    }

    public static int compareBoolean(final Boolean l1, final Boolean l2) {
        if (l1 == null && l2 == null) {
            return 0;
        }
        if (l1 == null) {
            return -1;
        }
        if (l2 == null) {
            return +1;
        }
        return l1.compareTo(l2);
    }

    public static int compareString(final String l1, final String l2) {
        if (l1 == null && l2 == null) {
            return 0;
        }
        if (l1 == null) {
            return -1;
        }
        if (l2 == null) {
            return +1;
        }
        return l1.compareToIgnoreCase(l2);
    }

    public static int compareBigDecimal(final BigDecimal val1, final BigDecimal val2) {
        if (val1 == null && val2 == null) {
            return 0;
        }
        if (val1 == null) {
            return -1;
        }
        if (val2 == null) {
            return +1;
        }
        return val1.compareTo(val2);
    }

    /**
     * Convert a BaseCriteria into a Comparator
     * <p>
     * e.g. of fieldComparatorsMap
     *
     * <pre>
     * private static final Map<String, Comparator<DBTableStatus>> FIELD_COMPARATORS = Map.of(
     *   DBTableStatus.FIELD_DATABASE, Comparator.comparing(
     *     DBTableStatus::getDb,
     *     String::compareToIgnoreCase),
     *   DBTableStatus.FIELD_TABLE, Comparator.comparing(
     *     DBTableStatus::getTable,
     *     String::compareToIgnoreCase),
     *   DBTableStatus.FIELD_ROW_COUNT, Comparator.comparing(DBTableStatus::getCount),
     *   DBTableStatus.FIELD_DATA_SIZE, Comparator.comparing(DBTableStatus::getDataSize),
     *   DBTableStatus.FIELD_INDEX_SIZE, Comparator.comparing(DBTableStatus::getIndexSize));
     * </pre>
     */
    public static <T> Comparator<T> buildCriteriaComparator(
            final Map<String, Comparator<T>> fieldComparatorsMap,
            final BaseCriteria criteria) {

        Objects.requireNonNull(fieldComparatorsMap);
        Objects.requireNonNull(criteria);
        Objects.requireNonNull(criteria.getSortList());

        Comparator<T> comparator = Comparator.comparingInt(dbTableStatus -> 1);

        for (final Sort sort : criteria.getSortList()) {
            final String field = sort.getId();

            Comparator<T> fieldComparator = fieldComparatorsMap.get(field);

            Objects.requireNonNull(fieldComparator, () ->
                    "Missing comparator for field " + field);

            if (sort.isDesc()) {
                fieldComparator = fieldComparator.reversed();
            }

            comparator = comparator.thenComparing(fieldComparator);
        }
        return comparator;
    }

    /**
     * Creates a null safe case insensitive comparator that can work with stuff like
     * getDocRef().getName()
     */
    public static <T1, T2> Comparator<T1> getNullSafeCaseInsensitiveComparator(
            final Function<T1, T2> extractor1,
            final Function<T2, String> extractor2) {
        return getNullSafeComparator(extractor1, extractor2, String.CASE_INSENSITIVE_ORDER);
    }

    public static <T1, T2, T3 extends Comparable<T3>> Comparator<T1> getNullSafeComparator(
            final Function<T1, T2> extractor1,
            final Function<T2, T3> extractor2,
            final Comparator<T3> comparator) {

        // Sort with nulls first but also handle null intermediate values
        return Comparator.comparing(
                extractor1,
                Comparator.nullsFirst(
                        Comparator.comparing(
                                extractor2,
                                Comparator.nullsFirst(comparator))));
    }

    /**
     * Creates a null safe case insensitive comparator that can work with stuff like
     * getDocRef().getName().substring(1,3)
     */
    public static <T1, T2, T3> Comparator<T1> getNullSafeCaseInsensitiveComparator(
            final Function<T1, T2> extractor1,
            final Function<T2, T3> extractor2,
            final Function<T3, String> extractor3) {
        return getNullSafeComparator(extractor1, extractor2, extractor3, String.CASE_INSENSITIVE_ORDER);
    }

    public static <T1, T2, T3, T4 extends Comparable<T4>> Comparator<T1> getNullSafeComparator(
            final Function<T1, T2> extractor1,
            final Function<T2, T3> extractor2,
            final Function<T3, T4> extractor3,
            final Comparator<T4> comparator) {

        // Sort with nulls first but also handle deps with null intermediate values
        return Comparator.comparing(
                extractor1,
                Comparator.nullsFirst(
                        Comparator.comparing(
                                extractor2,
                                Comparator.nullsFirst(
                                        Comparator.comparing(
                                                extractor3,
                                                Comparator.nullsFirst(comparator))))));
    }
}
