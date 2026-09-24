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

package stroom.shapeshifter.client.presenter;

import stroom.docref.DocRef;
import stroom.explorer.client.presenter.DocSelectionBoxPresenter;
import stroom.security.shared.DocumentPermission;
import stroom.shapeshifter.client.presenter.AttemptFilterPresenter.AttemptFilterView;
import stroom.shapeshifter.shared.AttemptCriteria;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.ExecutionMode;
import stroom.shapeshifter.shared.PromotionMode;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.util.shared.NullSafe;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.List;

/**
 * Which attempts the Supervisor is showing (A28, design 01 §11.6): the six things the ruling says a
 * person may narrow by — document, feed, shape, execution mode, promotion mode and status.
 * <p>
 * Every one of them goes into the query rather than filtering its answer, as the page's total already
 * does: a filter applied afterwards would leave the pager promising pages that are not there.
 * <p>
 * Nothing is required. An empty form is every attempt of every document the person may read, which is
 * what the screen opens on.
 */
public class AttemptFilterPresenter extends MyPresenterWidget<AttemptFilterView> {

    private final DocSelectionBoxPresenter documentPresenter;

    @Inject
    public AttemptFilterPresenter(final EventBus eventBus,
                                  final AttemptFilterView view,
                                  final DocSelectionBoxPresenter documentPresenter) {
        super(eventBus, view);
        this.documentPresenter = documentPresenter;
        documentPresenter.setIncludedTypes(ShapeshifterAiDoc.TYPE);
        documentPresenter.setRequiredPermissions(DocumentPermission.VIEW);
        view.setDocumentView(documentPresenter.getView());
    }

    public void read(final AttemptCriteria criteria) {
        documentPresenter.setSelectedEntityReference(criteria.getDocUuid() == null
                ? null
                : new DocRef(ShapeshifterAiDoc.TYPE, criteria.getDocUuid()), true);
        getView().setFeed(NullSafe.string(criteria.getFeed()));
        getView().setShape(NullSafe.string(criteria.getShape()));
        getView().setExecutionMode(criteria.getExecutionMode());
        getView().setPromotionMode(criteria.getPromotionMode());
        getView().setStatuses(criteria.getStatuses());
    }

    /**
     * The criteria as the form has it, keeping the page request it was opened with: a filter changes
     * what is asked for, not how much of it.
     */
    public AttemptCriteria write(final AttemptCriteria opened) {
        final DocRef document = documentPresenter.getSelectedEntityReference();
        return new AttemptCriteria(
                opened.getPageRequest(),
                opened.getSortList(),
                NullSafe.get(document, DocRef::getUuid),
                blankToNull(getView().getFeed()),
                blankToNull(getView().getShape()),
                getView().getExecutionMode(),
                getView().getPromotionMode(),
                getView().getStatuses());
    }

    /**
     * Whether anything is being narrowed by, so that the screen can say a filter is on rather than
     * leaving a person wondering where the rest of the attempts went.
     */
    public static boolean isFiltering(final AttemptCriteria criteria) {
        return criteria.getDocUuid() != null
               || criteria.getFeed() != null
               || criteria.getShape() != null
               || criteria.getExecutionMode() != null
               || criteria.getPromotionMode() != null
               || !NullSafe.list(criteria.getStatuses()).isEmpty();
    }

    private static String blankToNull(final String value) {
        final String trimmed = NullSafe.string(value).trim();
        return trimmed.isEmpty()
                ? null
                : trimmed;
    }


    // --------------------------------------------------------------------------------


    public interface AttemptFilterView extends View {

        void setDocumentView(View view);

        String getFeed();

        void setFeed(String feed);

        String getShape();

        void setShape(String shape);

        ExecutionMode getExecutionMode();

        void setExecutionMode(ExecutionMode mode);

        PromotionMode getPromotionMode();

        void setPromotionMode(PromotionMode mode);

        /**
         * Empty for every status, which is what "not narrowed by status" means to the query.
         */
        List<AttemptStatus> getStatuses();

        void setStatuses(List<AttemptStatus> statuses);
    }
}
