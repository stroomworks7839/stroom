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

package stroom.pipelinefactory.client.view;

import stroom.entity.client.presenter.ReadOnlyChangeHandler;
import stroom.meta.shared.MetaFields;
import stroom.pipelinefactory.client.presenter.PipelineFactorySettingsPresenter.PipelineFactorySettingsView;
import stroom.pipelinefactory.client.presenter.PipelineFactoryUiHandlers;
import stroom.widget.tickbox.client.view.CustomCheckBox;

import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.View;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

import java.util.ArrayList;
import java.util.List;

/// The settings form of a Pipeline Factory. The key is a tick box per field a key may name (A119), so a
/// field no filter could select is never offered.
public class PipelineFactorySettingsViewImpl
        extends ViewWithUiHandlers<PipelineFactoryUiHandlers>
        implements PipelineFactorySettingsView, ReadOnlyChangeHandler {

    private final Widget widget;

    @UiField
    CustomCheckBox enabled;
    @UiField
    CustomCheckBox keyFeed;
    @UiField
    CustomCheckBox keyType;
    @UiField
    CustomCheckBox keyPipeline;
    @UiField
    SimplePanel template;
    @UiField
    TextBox folderPattern;
    @UiField
    TextBox namePattern;
    @UiField
    SimplePanel runAsUser;
    @UiField
    Label problems;
    @UiField
    SimplePanel streamFilter;
    @UiField
    SimplePanel properties;

    @Inject
    public PipelineFactorySettingsViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void setStreamFilterView(final View view) {
        streamFilter.setWidget(view.asWidget());
    }

    @Override
    public void setTemplateView(final View view) {
        template.setWidget(view.asWidget());
    }

    @Override
    public void setRunAsUserView(final View view) {
        runAsUser.setWidget(view.asWidget());
    }

    @Override
    public void setPropertiesView(final View view) {
        properties.setWidget(view.asWidget());
    }

    @Override
    public boolean isEnabled() {
        return enabled.getValue();
    }

    @Override
    public void setEnabled(final boolean enabled) {
        this.enabled.setValue(enabled);
    }

    @Override
    public List<String> getKey() {
        final List<String> key = new ArrayList<>();
        if (keyFeed.getValue()) {
            key.add(MetaFields.FEED.getFldName());
        }
        if (keyType.getValue()) {
            key.add(MetaFields.TYPE.getFldName());
        }
        if (keyPipeline.getValue()) {
            key.add(MetaFields.PIPELINE.getFldName());
        }
        return key;
    }

    @Override
    public void setKey(final List<String> key) {
        keyFeed.setValue(key.contains(MetaFields.FEED.getFldName()));
        keyType.setValue(key.contains(MetaFields.TYPE.getFldName()));
        keyPipeline.setValue(key.contains(MetaFields.PIPELINE.getFldName()));
    }

    @Override
    public String getFolderPattern() {
        return folderPattern.getValue();
    }

    @Override
    public void setFolderPattern(final String folderPattern) {
        this.folderPattern.setValue(folderPattern);
    }

    @Override
    public String getNamePattern() {
        return namePattern.getValue();
    }

    @Override
    public void setNamePattern(final String namePattern) {
        this.namePattern.setValue(namePattern);
    }

    @Override
    public void setProblems(final List<String> problems) {
        this.problems.setText(problems.isEmpty()
                ? ""
                : "Cannot be saved until put right: " + String.join("; ", problems) + ".");
        this.problems.setVisible(!problems.isEmpty());
    }

    @Override
    public void onReadOnly(final boolean readOnly) {
        final boolean editable = !readOnly;
        enabled.setEnabled(editable);
        keyFeed.setEnabled(editable);
        keyType.setEnabled(editable);
        keyPipeline.setEnabled(editable);
        folderPattern.setEnabled(editable);
        namePattern.setEnabled(editable);
    }

    private void fireChange() {
        if (getUiHandlers() != null) {
            getUiHandlers().onSettingChange();
        }
    }

    @UiHandler({"enabled", "keyFeed", "keyType", "keyPipeline"})
    public void onTick(final ValueChangeEvent<Boolean> event) {
        fireChange();
    }

    @UiHandler({"folderPattern", "namePattern"})
    public void onPattern(final ValueChangeEvent<String> event) {
        fireChange();
    }


    // --------------------------------------------------------------------------------


    /// Binds the form's layout.
    public interface Binder extends UiBinder<Widget, PipelineFactorySettingsViewImpl> {

    }
}
