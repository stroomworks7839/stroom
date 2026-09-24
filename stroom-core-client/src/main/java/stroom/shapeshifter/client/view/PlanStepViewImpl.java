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

package stroom.shapeshifter.client.view;

import stroom.item.client.SelectionBox;
import stroom.shapeshifter.client.presenter.PlanStepPresenter.PlanStepView;
import stroom.shapeshifter.shared.Check;
import stroom.shapeshifter.shared.ConfigureRole;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.shapeshifter.shared.StepGuard;
import stroom.widget.tickbox.client.view.CustomCheckBox;

import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.View;
import com.gwtplatform.mvp.client.ViewImpl;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class PlanStepViewImpl extends ViewImpl implements PlanStepView {

    private final Widget widget;
    /**
     * A tick per check, in the order the closed list declares them: a multi-select of a list this short
     * reads better as boxes than as a picker, and it shows the ones that are off as well as the ones
     * that are on.
     */
    private final Map<Check, CustomCheckBox> checkBoxes = new EnumMap<>(Check.class);

    @UiField
    TextBox id;
    @UiField
    SelectionBox<QuestionKind> kind;
    @UiField
    SelectionBox<ConfigureRole> role;
    @UiField
    SelectionBox<StepGuard> when;
    @UiField
    TextBox candidates;
    @UiField
    TextBox kinds;
    @UiField
    FlowPanel checks;
    @UiField
    SimplePanel transitions;

    @Inject
    public PlanStepViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        kind.addItems(QuestionKind.values());
        role.addItems(ConfigureRole.values());
        when.addItems(StepGuard.values());
        for (final Check check : Check.values()) {
            final CustomCheckBox box = new CustomCheckBox();
            box.setLabel(check.getDisplayValue());
            checkBoxes.put(check, box);
            checks.add(box);
        }
        // The role follows the kind, and the form says so as the kind is chosen rather than when the
        // save is refused: only CONFIGURE takes one.
        kind.addValueChangeHandler(event -> roleFollowsKind());
        roleFollowsKind();
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public String getId() {
        return id.getValue();
    }

    @Override
    public void setId(final String id) {
        this.id.setValue(id);
    }

    @Override
    public QuestionKind getKind() {
        return kind.getValue();
    }

    @Override
    public void setKind(final QuestionKind kind) {
        this.kind.setValue(kind);
        roleFollowsKind();
    }

    @Override
    public ConfigureRole getRole() {
        return QuestionKind.CONFIGURE == kind.getValue()
                ? role.getValue()
                : null;
    }

    @Override
    public void setRole(final ConfigureRole role) {
        this.role.setValue(role);
    }

    @Override
    public StepGuard getWhen() {
        return when.getValue();
    }

    @Override
    public void setWhen(final StepGuard when) {
        this.when.setValue(when);
    }

    @Override
    public Integer getCandidates() {
        return number(candidates);
    }

    @Override
    public void setCandidates(final Integer candidates) {
        this.candidates.setValue(text(candidates));
    }

    @Override
    public Integer getKinds() {
        return number(kinds);
    }

    @Override
    public void setKinds(final Integer kinds) {
        this.kinds.setValue(text(kinds));
    }

    @Override
    public List<Check> getChecks() {
        final List<Check> ticked = new ArrayList<>();
        checkBoxes.forEach((check, box) -> {
            if (Boolean.TRUE.equals(box.getValue())) {
                ticked.add(check);
            }
        });
        return ticked;
    }

    @Override
    public void setChecks(final List<Check> checks) {
        checkBoxes.forEach((check, box) -> box.setValue(checks.contains(check)));
    }

    @Override
    public void setTransitionsView(final View view) {
        transitions.setWidget(view.asWidget());
    }

    @Override
    public void setReadOnly(final boolean readOnly) {
        id.setEnabled(!readOnly);
        kind.setEnabled(!readOnly);
        when.setEnabled(!readOnly);
        candidates.setEnabled(!readOnly);
        kinds.setEnabled(!readOnly);
        checkBoxes.values().forEach(box -> box.setEnabled(!readOnly));
        roleFollowsKind();
    }

    /**
     * Only a {@code CONFIGURE} step takes a role, so for every other kind the picker is off and empty.
     */
    private void roleFollowsKind() {
        final boolean configures = QuestionKind.CONFIGURE == kind.getValue();
        role.setEnabled(configures && id.isEnabled());
        if (!configures) {
            role.setValue(null);
        }
    }

    /**
     * A limit left empty is the kind's own default, which is what null means on the step; anything that
     * is not a number is treated the same way, since the field will not hold one a person did not type.
     */
    private static Integer number(final TextBox box) {
        final String text = box.getValue() == null
                ? ""
                : box.getValue().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(text);
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private static String text(final Integer value) {
        return value == null
                ? ""
                : String.valueOf(value);
    }


    // --------------------------------------------------------------------------------


    public interface Binder extends UiBinder<Widget, PlanStepViewImpl> {

    }
}
