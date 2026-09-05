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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The variables in scope, and their values.
 *
 * <p>A registry is a stack of scopes. Reads search from the innermost outwards, so an inner
 * capture shadows an outer one of the same name; writes land in whichever scope already holds
 * the name, or in the innermost if none does.
 *
 * <p>Scopes exist for memory rather than for hygiene. A recursive apply over a large document
 * would otherwise accumulate every capture of every level; popping a scope releases them, which
 * is what keeps a stream of unbounded length processable in bounded space.
 *
 * <p>Each name maps to a <i>list</i> of stores, indexed by capture group. Group 0 is where
 * ordinary captures land; the wider list is what lets a variable carry a whole match's groups.
 */
public final class VarRegistry {

    private final List<Map<String, List<Store>>> scopes = new ArrayList<>();

    public VarRegistry() {
        scopes.add(new HashMap<>());
    }

    /** Enter a new scope. */
    public void push() {
        scopes.add(new HashMap<>());
    }

    /** Leave the current scope, discarding everything written in it. */
    public void pop() {
        if (scopes.size() <= 1) {
            throw new IllegalStateException("Cannot pop the global scope");
        }
        scopes.removeLast();
    }

    /** The stores for a name, searching outwards from the current scope, or null. */
    public List<Store> get(final String name) {
        for (int i = scopes.size() - 1; i >= 0; i--) {
            final List<Store> stores = scopes.get(i).get(name);
            if (stores != null) {
                return stores;
            }
        }
        return null;
    }

    /** The stores for a name, creating them in the innermost scope if nothing holds it yet. */
    public List<Store> entry(final String name) {
        final List<Store> found = get(name);
        if (found != null) {
            return found;
        }
        final List<Store> stores = new ArrayList<>(1);
        scopes.getLast().put(name, stores);
        return stores;
    }

    /** The group-0 store for a name, creating it if needed. */
    public Store store(final String name) {
        final List<Store> stores = entry(name);
        if (stores.isEmpty()) {
            stores.add(new Store());
        }
        return stores.getFirst();
    }

    /** Note that a name exists, so that a later write finds it rather than creating it inside. */
    public void register(final String name) {
        if (get(name) == null) {
            scopes.getFirst().put(name, new ArrayList<>(1));
        }
    }

    /** Note a name in the <i>current</i> scope only, so an inner write cannot escape it. */
    public void shadow(final String name) {
        scopes.getLast().computeIfAbsent(name, key -> new ArrayList<>(1));
    }

    /** A name's stores from the current scope only, ignoring anything outside it. */
    public List<Store> fromCurrentScope(final String name) {
        return scopes.getLast().get(name);
    }
}
