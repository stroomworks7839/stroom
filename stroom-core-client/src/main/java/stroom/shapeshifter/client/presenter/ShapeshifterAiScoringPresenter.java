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

import stroom.alert.client.event.AlertEvent;
import stroom.alert.client.event.ConfirmEvent;
import stroom.docref.DocRef;
import stroom.entity.client.presenter.DocPresenter;
import stroom.shapeshifter.client.presenter.ShapeshifterAiScoringPresenter.ShapeshifterAiScoringView;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.svg.client.SvgPresets;
import stroom.widget.button.client.ButtonView;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupType;
import stroom.widget.util.client.MultiSelectEvent;

import com.google.gwt.dom.client.Style.BorderStyle;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The Scoring tab: the scorer set of design §8.4 as a list with add, edit and delete. A Shapeshifter AI document
 * applies
 * each scorer at most once, so the set is keyed by scorer type and the dialog refuses a duplicate.
 */
public class ShapeshifterAiScoringPresenter
        extends DocPresenter<ShapeshifterAiScoringView, ShapeshifterAiDoc>
        implements ShapeshifterAiSettingsUiHandlers {

    private final ScorerListPresenter listPresenter;
    private final Provider<ScorerSettingPresenter> editPresenterProvider;
    private final List<ScorerSetting> scorers = new ArrayList<>();

    private final ButtonView addButton;
    private final ButtonView editButton;
    private final ButtonView deleteButton;

    @Inject
    public ShapeshifterAiScoringPresenter(final EventBus eventBus,
                                             final ShapeshifterAiScoringView view,
                                             final ScorerListPresenter listPresenter,
                                             final Provider<ScorerSettingPresenter> editPresenterProvider) {
        super(eventBus, view);
        this.listPresenter = listPresenter;
        this.editPresenterProvider = editPresenterProvider;
        view.setUiHandlers(this);
        view.setTableView(listPresenter.getView());

        addButton = listPresenter.add(SvgPresets.ADD.title("Add scorer"));
        editButton = listPresenter.add(SvgPresets.EDIT.title("Edit selected scorer"));
        deleteButton = listPresenter.add(SvgPresets.DELETE.title("Remove selected scorer"));
        listPresenter.getView().asWidget().getElement().getStyle().setBorderStyle(BorderStyle.NONE);
        updateButtons();
    }

    @Override
    protected void onBind() {
        registerHandler(addButton.addClickHandler(this::onAdd));
        registerHandler(editButton.addClickHandler(this::onEdit));
        registerHandler(deleteButton.addClickHandler(this::onDelete));
        registerHandler(listPresenter.getSelectionModel().addSelectionHandler(this::onSelection));
        super.onBind();
    }

    @Override
    protected void onRead(final DocRef docRef, final ShapeshifterAiDoc doc, final boolean readOnly) {
        scorers.clear();
        scorers.addAll(doc.getScorers());
        listPresenter.getSelectionModel().clear();
        update();
    }

    @Override
    protected ShapeshifterAiDoc onWrite(final ShapeshifterAiDoc doc) {
        return doc.copy().scorers(new ArrayList<>(scorers)).build();
    }

    private void onAdd(final ClickEvent event) {
        if (!isReadOnly()) {
            final ScorerType unused = firstUnusedType();
            if (unused == null) {
                AlertEvent.fireInfo(this, "Every scorer is already in the set.", null);
                return;
            }
            showScorer(new ScorerSetting(unused, null, null, null, null), null, scorer -> {
                scorers.add(scorer);
                update();
                listPresenter.getSelectionModel().setSelected(scorer);
                onChange();
            });
        }
    }

    private void onEdit(final ClickEvent event) {
        if (!isReadOnly()) {
            final ScorerSetting existing = listPresenter.getSelectionModel().getSelected();
            if (existing != null) {
                edit(existing);
            }
        }
    }

    private void edit(final ScorerSetting existing) {
        showScorer(existing, existing, scorer -> {
            scorers.set(scorers.indexOf(existing), scorer);
            update();
            listPresenter.getSelectionModel().setSelected(scorer);
            if (!existing.equals(scorer)) {
                onChange();
            }
        });
    }

    private void onDelete(final ClickEvent event) {
        if (!isReadOnly()) {
            final ScorerSetting existing = listPresenter.getSelectionModel().getSelected();
            if (existing != null) {
                ConfirmEvent.fire(this, "Remove the " + existing.getType().getDisplayValue() + " scorer?", ok -> {
                    if (ok) {
                        scorers.remove(existing);
                        listPresenter.getSelectionModel().clear();
                        update();
                        onChange();
                    }
                });
            }
        }
    }

    private void onSelection(final MultiSelectEvent event) {
        final ScorerSetting scorer = listPresenter.getSelectionModel().getSelected();
        if (scorer != null && event.getSelectionType().isDoubleSelect() && !isReadOnly()) {
            edit(scorer);
        }
        updateButtons();
    }

    /**
     * @param replacing The setting being edited, whose own type is not a duplicate of itself; null when
     *                  adding.
     */
    private void showScorer(final ScorerSetting setting,
                            final ScorerSetting replacing,
                            final Consumer<ScorerSetting> onOk) {
        final ScorerSettingPresenter presenter = editPresenterProvider.get();
        presenter.read(setting);
        ShowPopupEvent.builder(presenter)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(600, 400))
                .caption("Scorer")
                .onShow(e -> listPresenter.focus())
                .onHideRequest(e -> {
                    if (!e.isOk()) {
                        e.hide();
                        return;
                    }
                    final Optional<String> problem = presenter.validate()
                            .or(() -> duplicate(presenter.write().getType(), replacing));
                    if (problem.isPresent()) {
                        AlertEvent.fireError(this, problem.get(), e::reset);
                    } else {
                        onOk.accept(presenter.write());
                        e.hide();
                    }
                })
                .fire();
    }

    private Optional<String> duplicate(final ScorerType type, final ScorerSetting replacing) {
        return scorers.stream()
                .filter(scorer -> scorer != replacing && scorer.getType() == type)
                .findFirst()
                .map(scorer -> "The " + type.getDisplayValue() + " scorer is already in the set.");
    }

    private ScorerType firstUnusedType() {
        for (final ScorerType type : ScorerType.values()) {
            if (scorers.stream().noneMatch(scorer -> scorer.getType() == type)) {
                return type;
            }
        }
        return null;
    }

    private void update() {
        listPresenter.setData(scorers);
        updateButtons();
    }

    private void updateButtons() {
        final boolean editable = !isReadOnly();
        final boolean selected = listPresenter.getSelectionModel().getSelected() != null;
        addButton.setEnabled(editable);
        editButton.setEnabled(editable && selected);
        deleteButton.setEnabled(editable && selected);
    }


    // --------------------------------------------------------------------------------


    public interface ShapeshifterAiScoringView
            extends View, HasUiHandlers<ShapeshifterAiSettingsUiHandlers> {

        void setTableView(View view);
    }
}
