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

package stroom.shapeshifter.client.presenter;

import stroom.data.client.presenter.EditExpressionPresenter;
import stroom.explorer.client.presenter.DocSelectionBoxPresenter;
import stroom.pipeline.shared.PipelineDoc;
import stroom.query.api.ExpressionOperator;
import stroom.query.client.presenter.SimpleFieldSelectionListModel;
import stroom.security.shared.DocumentPermission;
import stroom.shapeshifter.client.presenter.RoutingRulePresenter.RoutingRuleView;
import stroom.shapeshifter.shared.RoutingFields;
import stroom.shapeshifter.shared.RoutingRule;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

/**
 * The edit dialog for one routing rule: the selector in the standard expression editor over
 * {@link RoutingFields}, the fragment in the standard document picker, and the pin. Promotion history is
 * the supervisor's to write, not the operator's, so it is shown in the table and not editable here.
 */
public class RoutingRulePresenter extends MyPresenterWidget<RoutingRuleView> {

    private final EditExpressionPresenter editExpressionPresenter;
    private final DocSelectionBoxPresenter fragmentPresenter;
    private final SimpleFieldSelectionListModel fields = new SimpleFieldSelectionListModel();
    private RoutingRule original;

    @Inject
    public RoutingRulePresenter(final EventBus eventBus,
                                final RoutingRuleView view,
                                final EditExpressionPresenter editExpressionPresenter,
                                final DocSelectionBoxPresenter fragmentPresenter) {
        super(eventBus, view);
        this.editExpressionPresenter = editExpressionPresenter;
        this.fragmentPresenter = fragmentPresenter;
        fields.addItems(RoutingFields.FIELDS);
        fragmentPresenter.setIncludedTypes(PipelineDoc.TYPE);
        fragmentPresenter.setRequiredPermissions(DocumentPermission.USE);
        view.setExpressionView(editExpressionPresenter.getView());
        view.setFragmentView(fragmentPresenter.getView());
    }

    public void read(final RoutingRule rule) {
        original = rule;
        editExpressionPresenter.init(null, null, fields);
        editExpressionPresenter.read(rule.getExpression() == null
                ? ExpressionOperator.builder().build()
                : rule.getExpression());
        fragmentPresenter.setSelectedEntityReference(rule.getPipeline(), true);
        getView().setPinned(rule.isPinned());
    }

    public RoutingRule write() {
        // A selector with no terms is the catch-all's null, not an empty AND: the grid says "(all streams)"
        // of one and the stage pairs a draft with its rule by it.
        final ExpressionOperator edited = editExpressionPresenter.write();
        return original.copy()
                .expression(edited == null || edited.getChildren() == null || edited.getChildren().isEmpty()
                        ? null
                        : edited)
                .pipeline(fragmentPresenter.getSelectedEntityReference())
                .pinned(getView().isPinned())
                .build();
    }


    // --------------------------------------------------------------------------------


    public interface RoutingRuleView extends View {

        void setExpressionView(View view);

        void setFragmentView(View view);

        boolean isPinned();

        void setPinned(boolean pinned);
    }
}
