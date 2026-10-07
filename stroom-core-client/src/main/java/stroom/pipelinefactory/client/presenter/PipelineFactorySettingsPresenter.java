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

package stroom.pipelinefactory.client.presenter;

import stroom.alert.client.event.AlertEvent;
import stroom.alert.client.event.ConfirmEvent;
import stroom.data.client.presenter.EditExpressionPresenter;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.entity.client.presenter.DocPresenter;
import stroom.entity.client.presenter.ReadOnlyChangeHandler;
import stroom.explorer.client.presenter.DocSelectionBoxPresenter;
import stroom.meta.shared.MetaFields;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipelinefactory.client.presenter.PipelineFactorySettingsPresenter.PipelineFactorySettingsView;
import stroom.pipelinefactory.shared.PipelineFactoryDoc;
import stroom.pipelinefactory.shared.PipelineFactoryProperty;
import stroom.query.api.ExpressionOperator;
import stroom.query.client.presenter.SimpleFieldSelectionListModel;
import stroom.security.client.presenter.UserRefSelectionBoxPresenter;
import stroom.security.shared.FindUserContext;
import stroom.svg.client.SvgPresets;
import stroom.widget.button.client.ButtonView;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupType;
import stroom.widget.util.client.MultiSelectEvent;

import com.google.gwt.event.dom.client.ClickEvent;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/// What a Pipeline Factory makes and where (design 06 §3.1): whether it runs, the streams it looks at,
/// what makes one pipeline, the template, the names and folders, the values it writes onto each pipeline,
/// and who it makes them as. What is wrong with the factory is shown as it is edited, since the store
/// refuses to save a factory with problems.
public class PipelineFactorySettingsPresenter
        extends DocPresenter<PipelineFactorySettingsView, PipelineFactoryDoc>
        implements PipelineFactoryUiHandlers {

    private final EditExpressionPresenter streamFilterPresenter;
    private final DocSelectionBoxPresenter templatePresenter;
    private final UserRefSelectionBoxPresenter runAsUserPresenter;
    private final PipelineFactoryPropertyListPresenter propertyListPresenter;
    private final Provider<PipelineFactoryPropertyPresenter> propertyPresenterProvider;
    private final List<PipelineFactoryProperty> properties = new ArrayList<>();

    private final ButtonView addButton;
    private final ButtonView editButton;
    private final ButtonView deleteButton;

    @Inject
    public PipelineFactorySettingsPresenter(
            final EventBus eventBus,
            final PipelineFactorySettingsView view,
            final EditExpressionPresenter streamFilterPresenter,
            final DocSelectionBoxPresenter templatePresenter,
            final UserRefSelectionBoxPresenter runAsUserPresenter,
            final PipelineFactoryPropertyListPresenter propertyListPresenter,
            final Provider<PipelineFactoryPropertyPresenter> propertyPresenterProvider,
            final RestFactory restFactory) {
        super(eventBus, view);
        this.streamFilterPresenter = streamFilterPresenter;
        this.templatePresenter = templatePresenter;
        this.runAsUserPresenter = runAsUserPresenter;
        this.propertyListPresenter = propertyListPresenter;
        this.propertyPresenterProvider = propertyPresenterProvider;
        view.setUiHandlers(this);

        // The fields a processor filter can query, since the stream filter is part of every filter made.
        final SimpleFieldSelectionListModel fields = new SimpleFieldSelectionListModel();
        fields.addItems(MetaFields.getProcessorFilterFields());
        streamFilterPresenter.init(restFactory, MetaFields.STREAM_STORE_DOC_REF, fields);
        view.setStreamFilterView(streamFilterPresenter.getView());

        templatePresenter.setIncludedTypes(PipelineDoc.TYPE);
        view.setTemplateView(templatePresenter.getView());

        runAsUserPresenter.setContext(FindUserContext.RUN_AS);
        view.setRunAsUserView(runAsUserPresenter.getView());

        view.setPropertiesView(propertyListPresenter.getView());
        addButton = propertyListPresenter.add(SvgPresets.ADD.title("Add a property value"));
        editButton = propertyListPresenter.add(SvgPresets.EDIT.title("Edit the selected property value"));
        deleteButton = propertyListPresenter.add(SvgPresets.DELETE.title("Remove the selected property value"));
        updateButtons();
    }

    @Override
    protected void onBind() {
        registerHandler(streamFilterPresenter.addChangeHandler(this::onSettingChange));
        registerHandler(templatePresenter.addDataSelectionHandler(event -> onSettingChange()));
        registerHandler(runAsUserPresenter.addDataSelectionHandler(event -> onSettingChange()));
        registerHandler(addButton.addClickHandler(this::onAdd));
        registerHandler(editButton.addClickHandler(this::onEdit));
        registerHandler(deleteButton.addClickHandler(this::onDelete));
        registerHandler(propertyListPresenter.getSelectionModel().addSelectionHandler(this::onSelection));
        super.onBind();
    }

    @Override
    protected void onRead(final DocRef docRef, final PipelineFactoryDoc doc, final boolean readOnly) {
        final PipelineFactorySettingsView view = getView();
        view.setEnabled(doc.isEnabled());
        view.setKey(doc.getKey());
        view.setFolderPattern(doc.getFolderPattern());
        view.setNamePattern(doc.getNamePattern());
        streamFilterPresenter.read(Objects.requireNonNullElse(doc.getStreamFilter(),
                ExpressionOperator.builder().build()));
        templatePresenter.setSelectedEntityReference(doc.getTemplate(), true);
        templatePresenter.setEnabled(!readOnly);
        runAsUserPresenter.setSelected(doc.getRunAsUser());
        runAsUserPresenter.setEnabled(!readOnly);
        properties.clear();
        properties.addAll(doc.getProperties());
        propertyListPresenter.getSelectionModel().clear();
        updateProperties();
        view.setProblems(doc.problems());
    }

    @Override
    protected PipelineFactoryDoc onWrite(final PipelineFactoryDoc doc) {
        final PipelineFactorySettingsView view = getView();
        return doc.copy()
                .enabled(view.isEnabled())
                .streamFilter(streamFilterOf(doc))
                .key(view.getKey())
                .template(templatePresenter.getSelectedEntityReference())
                .folderPattern(view.getFolderPattern())
                .namePattern(view.getNamePattern())
                .properties(new ArrayList<>(properties))
                .runAsUser(runAsUserPresenter.getSelected())
                .build();
    }

    /// An empty filter and no filter both mean every stream. Where the editor holds an empty one, the
    /// document keeps whichever of the two it had, so that opening a factory saved with an empty filter
    /// does not make it look changed.
    private ExpressionOperator streamFilterOf(final PipelineFactoryDoc doc) {
        final ExpressionOperator written = streamFilterPresenter.write();
        if (written != null && written.hasChildren()) {
            return written;
        }
        final ExpressionOperator had = doc.getStreamFilter();
        return had == null || had.hasChildren()
                ? null
                : had;
    }

    @Override
    public void onSettingChange() {
        onChange();
        if (getEntity() != null) {
            getView().setProblems(onWrite(getEntity()).problems());
        }
    }

    private void onAdd(final ClickEvent event) {
        if (!isReadOnly()) {
            show(new PipelineFactoryProperty(null, null, null), property -> {
                properties.add(property);
                updateProperties();
                propertyListPresenter.getSelectionModel().setSelected(property);
                onSettingChange();
            });
        }
    }

    private void onEdit(final ClickEvent event) {
        final PipelineFactoryProperty existing = propertyListPresenter.getSelectionModel().getSelected();
        if (existing != null && !isReadOnly()) {
            edit(existing);
        }
    }

    private void edit(final PipelineFactoryProperty existing) {
        show(existing, property -> {
            properties.set(properties.indexOf(existing), property);
            updateProperties();
            propertyListPresenter.getSelectionModel().setSelected(property);
            if (!existing.equals(property)) {
                onSettingChange();
            }
        });
    }

    private void onDelete(final ClickEvent event) {
        final PipelineFactoryProperty existing = propertyListPresenter.getSelectionModel().getSelected();
        if (existing != null && !isReadOnly()) {
            final String question = "Remove the value for " + existing.getElementId() + "." + existing.getName() + "?";
            ConfirmEvent.fire(this, question, ok -> {
                if (ok) {
                    properties.remove(existing);
                    propertyListPresenter.getSelectionModel().clear();
                    updateProperties();
                    onSettingChange();
                }
            });
        }
    }

    private void onSelection(final MultiSelectEvent event) {
        final PipelineFactoryProperty selected = propertyListPresenter.getSelectionModel().getSelected();
        if (selected != null && event.getSelectionType().isDoubleSelect() && !isReadOnly()) {
            edit(selected);
        }
        updateButtons();
    }

    private void show(final PipelineFactoryProperty property, final Consumer<PipelineFactoryProperty> onOk) {
        final PipelineFactoryPropertyPresenter presenter = propertyPresenterProvider.get();
        presenter.read(property);
        ShowPopupEvent.builder(presenter)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizableX(500))
                .caption("Property value")
                .onHideRequest(e -> {
                    if (!e.isOk()) {
                        e.hide();
                        return;
                    }
                    final PipelineFactoryProperty written = presenter.write();
                    if (written.getElementId().isEmpty() || written.getName().isEmpty()) {
                        AlertEvent.fireError(this, "A property value needs an element and a property name.",
                                e::reset);
                    } else {
                        onOk.accept(written);
                        e.hide();
                    }
                })
                .fire();
    }

    private void updateProperties() {
        propertyListPresenter.setData(properties);
        updateButtons();
    }

    private void updateButtons() {
        final boolean editable = !isReadOnly();
        final boolean selected = propertyListPresenter.getSelectionModel().getSelected() != null;
        addButton.setEnabled(editable);
        editButton.setEnabled(editable && selected);
        deleteButton.setEnabled(editable && selected);
    }


    // --------------------------------------------------------------------------------


    /// The settings form.
    public interface PipelineFactorySettingsView
            extends View, ReadOnlyChangeHandler, HasUiHandlers<PipelineFactoryUiHandlers> {

        /// @param view The stream filter's expression editor.
        void setStreamFilterView(View view);

        /// @param view The template picker.
        void setTemplateView(View view);

        /// @param view The run-as user picker.
        void setRunAsUserView(View view);

        /// @param view The property values table.
        void setPropertiesView(View view);

        /// @return Whether the job runs the factory.
        boolean isEnabled();

        /// @param enabled Whether the job runs the factory.
        void setEnabled(boolean enabled);

        /// @return The key fields ticked, in the order a key lists them.
        List<String> getKey();

        /// @param key The key fields to tick.
        void setKey(List<String> key);

        /// @return Where a made pipeline goes.
        String getFolderPattern();

        /// @param folderPattern Where a made pipeline goes.
        void setFolderPattern(String folderPattern);

        /// @return What a made pipeline is called.
        String getNamePattern();

        /// @param namePattern What a made pipeline is called.
        void setNamePattern(String namePattern);

        /// @param problems What is wrong with the factory as it stands; empty when nothing is.
        void setProblems(List<String> problems);
    }
}
