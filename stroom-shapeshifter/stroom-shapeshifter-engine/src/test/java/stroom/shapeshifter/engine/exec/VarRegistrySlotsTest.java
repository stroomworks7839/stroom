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

import stroom.shapeshifter.engine.graph.KeyName;
import stroom.shapeshifter.engine.graph.Names;
import stroom.shapeshifter.engine.graph.VarName;
import stroom.shapeshifter.engine.value.TypedValue;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slots for names that arrive in the data (design 30 §8 ruling 8, design 33 §11 E).
 *
 * <p><b>Tested here rather than through a run, and the reason is the finding.</b> Nothing in a
 * configuration can read such a name — if it could name it, the compiler would have interned it
 * and it would not be data — so its slot is invisible in output. Two sabotages proved that:
 * giving every data-derived name slot zero, and giving them all the same slot, both passed the
 * entire suite including the `ausearch` golden that uses key-value captures. The registry's own
 * API is where the invariant is observable, so this is where it is pinned.
 */
class VarRegistrySlotsTest {

    private static Names names(final String... compiled) {
        final Map<String, VarName> all = new LinkedHashMap<>();
        all.put("__group", new VarName("__group", 0));
        for (int i = 0; i < compiled.length; i++) {
            all.put(compiled[i], new VarName(compiled[i], i + 1));
        }
        return new Names(all, Map.<String, KeyName>of());
    }

    @Test
    void namesFromTheDataGetSlotsOfTheirOwn() {
        final VarRegistry registry = new VarRegistry(names("kept"));
        registry.store("alpha").set(1, TypedValue.of("a"));
        registry.store("beta").set(1, TypedValue.of("b"));

        assertThat(registry.get("alpha")[0].get(1).asString()).isEqualTo("a");
        assertThat(registry.get("beta")[0].get(1).asString()).isEqualTo("b");
    }

    @Test
    void dataNamesDoNotLandOnACompiledSlot() {
        final Names names = names("kept");
        final VarRegistry registry = new VarRegistry(names);
        registry.store(names.lookup("kept")).set(1, TypedValue.of("survives"));

        for (int i = 0; i < 40; i++) {
            registry.store("key" + i).set(1, TypedValue.of("v" + i));
        }

        assertThat(registry.get(names.lookup("kept"))[0].get(1).asString())
                .isEqualTo("survives");
    }

    /** The growth path: more data names than the compiled table has slots, several times over. */
    @Test
    void growingPastTheCompiledSizeKeepsEveryNameDistinct() {
        final VarRegistry registry = new VarRegistry(names("kept"));
        for (int i = 0; i < 100; i++) {
            registry.store("k" + i).set(1, TypedValue.of("v" + i));
        }
        for (int i = 0; i < 100; i++) {
            assertThat(registry.get("k" + i)[0].get(1).asString()).isEqualTo("v" + i);
        }
    }
}
