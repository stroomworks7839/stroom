/*
 * Copyright 2026 Crown Copyright
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

package stroom.shapeshifter.ai.cache;

import stroom.cache.api.CacheManager;
import stroom.cache.api.LoadingStroomCache;
import stroom.docref.DocRef;
import stroom.shapeshifter.ai.ShapeshifterAiConfig;
import stroom.shapeshifter.ai.stage.Rules;
import stroom.shapeshifter.shared.RoutingRule;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.util.entityevent.EntityAction;
import stroom.util.entityevent.EntityEvent;
import stroom.util.entityevent.EntityEventBus;
import stroom.util.entityevent.EntityEventHandler;
import stroom.util.shared.Clearable;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Optional;

/// The routing table of a document, in front of the rows (design 01 §12 item 8). Every stream of every
/// shape is routed against it, and it changes only when something is learned or an operator edits it, so
/// reading it from the database on the hot path would be a query per stream per node — at hundreds of
/// threads a node, the first thing to give.
///
/// Held until what writes it says otherwise: every write goes to the rows and then fires an entity event
/// for the document, which clears the entry on *every* node, since the node that learns a rule is rarely
/// the only node routing against it. The expiry in the configuration is a backstop and not the mechanism.
@Singleton
@EntityEventHandler(type = ShapeshifterAiDoc.TYPE)
public class CachedRules implements Rules, Clearable, EntityEvent.Handler {

    private static final String CACHE_NAME = "Shapeshifter AI Routing Rules";

    private final Rules rows;
    private final EntityEventBus entityEventBus;
    private final LoadingStroomCache<String, List<RoutingRule>> cache;

    @Inject
    public CachedRules(@Rows final Rules rows,
                       final CacheManager cacheManager,
                       final EntityEventBus entityEventBus,
                       final Provider<ShapeshifterAiConfig> configProvider) {
        this.rows = rows;
        this.entityEventBus = entityEventBus;
        this.cache = cacheManager.createLoadingCache(
                CACHE_NAME,
                () -> configProvider.get().getRuleCache(),
                // Copied, because the rows hand back a list of their own making and this one is shared
                // by every routing thread on the node until something invalidates it: a caller that
                // sorted or added to what it was given would corrupt the routing of every stream.
                docUuid -> List.copyOf(rows.forDocument(docUuid)));
    }

    @Override
    public List<RoutingRule> forDocument(final String docUuid) {
        return cache.get(docUuid);
    }

    @Override
    public RoutingRule append(final String docUuid, final RoutingRule rule) {
        final RoutingRule appended = rows.append(docUuid, rule);
        changed(docUuid);
        return appended;
    }

    @Override
    public Optional<RoutingRule> byUuid(final String docUuid, final String ruleUuid) {
        return forDocument(docUuid).stream()
                .filter(rule -> rule.getUuid().equals(ruleUuid))
                .findFirst();
    }

    @Override
    public RoutingRule insert(final String docUuid, final RoutingRule rule, final int at) {
        final RoutingRule inserted = rows.insert(docUuid, rule, at);
        changed(docUuid);
        return inserted;
    }

    @Override
    public void move(final String docUuid, final String ruleUuid, final int to) {
        rows.move(docUuid, ruleUuid, to);
        changed(docUuid);
    }

    @Override
    public void replace(final String docUuid, final RoutingRule rule) {
        rows.replace(docUuid, rule);
        changed(docUuid);
    }

    @Override
    public void remove(final String docUuid, final String ruleUuid) {
        rows.remove(docUuid, ruleUuid);
        changed(docUuid);
    }

    @Override
    public void clear() {
        cache.clear();
    }

    @Override
    public void onChange(final EntityEvent event) {
        final String docUuid = event.getDocRef() == null
                ? null
                : event.getDocRef().getUuid();
        if (docUuid == null) {
            clear();
        } else {
            cache.invalidate(docUuid);
        }
    }

    /// What was learned here must be known everywhere: this node's entry goes at once, and the others'
    /// go when the event reaches them.
    ///
    /// `CLEAR_CACHE` and not `UPDATE`, which means the document itself changed: the document did not —
    /// its rules are rows of their own (A41) — and saying it did would have every node re-index it and
    /// drop its name caches for a rule nobody authored.
    private void changed(final String docUuid) {
        cache.invalidate(docUuid);
        entityEventBus.fire(new EntityEvent(
                new DocRef(ShapeshifterAiDoc.TYPE, docUuid), EntityAction.CLEAR_CACHE));
    }
}
