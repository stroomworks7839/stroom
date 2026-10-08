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
import stroom.shapeshifter.shared.LearnerCriteria;
import stroom.shapeshifter.shared.LearnerState;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.util.shared.NullSafe;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.List;

/// Which learning pipelines the Supervisor lists (design 06 A142): every one of every document the person
/// may read until somebody narrows it, by document, by the states a pipeline's learner is in, and to those
/// whose pipeline still exists unless deleted ones are asked for (A137).
///
/// Every choice goes into the query rather than filtering its answer, as the page's total already does.
public class LearnerFilterPresenter extends MyPresenterWidget<LearnerFilterPresenter.LearnerFilterView> {

    private final DocSelectionBoxPresenter documentPresenter;

    @Inject
    public LearnerFilterPresenter(final EventBus eventBus,
                                  final LearnerFilterView view,
                                  final DocSelectionBoxPresenter documentPresenter) {
        super(eventBus, view);
        this.documentPresenter = documentPresenter;
        documentPresenter.setIncludedTypes(ShapeshifterAiDoc.TYPE);
        documentPresenter.setRequiredPermissions(DocumentPermission.VIEW);
        view.setDocumentView(documentPresenter.getView());
    }

    public void read(final LearnerCriteria criteria) {
        documentPresenter.setSelectedEntityReference(criteria.getDocUuid() == null
                ? null
                : new DocRef(ShapeshifterAiDoc.TYPE, criteria.getDocUuid()), true);
        getView().setStates(criteria.getStates());
        getView().setShowDeletedPipelines(criteria.isShowDeletedPipelines());
    }

    /// The criteria as the form has it, keeping the page request it was opened with: a filter changes
    /// what is asked for, not how much of it.
    public LearnerCriteria write(final LearnerCriteria opened) {
        final DocRef document = documentPresenter.getSelectedEntityReference();
        return new LearnerCriteria(
                opened.getPageRequest(),
                opened.getSortList(),
                NullSafe.get(document, DocRef::getUuid),
                getView().getStates(),
                getView().isShowDeletedPipelines());
    }

    /// Whether anything is being narrowed, or widened to deleted pipelines, so that the screen can say a
    /// filter is on rather than leaving a person wondering where the rest went.
    public static boolean isFiltering(final LearnerCriteria criteria) {
        return criteria.getDocUuid() != null
               || !NullSafe.list(criteria.getStates()).isEmpty()
               || criteria.isShowDeletedPipelines();
    }


    // --------------------------------------------------------------------------------


    public interface LearnerFilterView extends View {

        void setDocumentView(View view);

        /// Empty for every state, which is what "not narrowed by state" means to the query.
        List<LearnerState> getStates();

        void setStates(List<LearnerState> states);

        /// Whether a deleted pipeline's learning is shown too (design 06 A137).
        boolean isShowDeletedPipelines();

        void setShowDeletedPipelines(boolean show);
    }
}
