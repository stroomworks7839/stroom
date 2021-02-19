/*
 * Copyright 2016 Crown Copyright
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

package stroom.search.elastic.client.view;

import stroom.entity.client.presenter.ReadOnlyChangeHandler;
import stroom.item.client.ItemListBox;
import stroom.search.elastic.client.presenter.ElasticIndexSettingsPresenter.ElasticIndexSettingsView;
import stroom.search.elastic.client.presenter.ElasticIndexSettingsUiHandlers;
import stroom.search.solr.shared.SolrConnectionConfig.InstanceType;
import stroom.widget.layout.client.view.ResizeSimplePanel;
import stroom.widget.tickbox.client.view.TickBox;

import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.TextArea;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.View;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class ElasticIndexSettingsViewImpl extends ViewWithUiHandlers<ElasticIndexSettingsUiHandlers> implements ElasticIndexSettingsView, ReadOnlyChangeHandler {
    private final Widget widget;

    @UiField
    TextArea description;
    @UiField
    TextBox collection;
    @UiField
    ItemListBox<InstanceType> instanceType;
    @UiField
    TextArea ElasticUrls;
    @UiField
    TickBox useZk;
    @UiField
    TextArea zkHosts;
    @UiField
    TextArea zkPath;
    @UiField
    Button testConnection;
    @UiField
    ResizeSimplePanel retentionExpressionPanel;

    @Inject
    public ElasticIndexSettingsViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);

        instanceType.addItem(InstanceType.SINGLE_NOOE);
        instanceType.addItem(InstanceType.Elastic_CLOUD);

        description.addKeyDownHandler(e -> fireChange());
        collection.addKeyDownHandler(e -> fireChange());
        instanceType.addSelectionHandler(e -> fireChange());
        ElasticUrls.addKeyDownHandler(e -> fireChange());
        useZk.addValueChangeHandler(e -> fireChange());
        zkHosts.addKeyDownHandler(e -> fireChange());
        zkPath.addKeyDownHandler(e -> fireChange());
    }

    private void fireChange() {
        if (getUiHandlers() != null) {
            getUiHandlers().onChange();
        }
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public String getDescription() {
        return description.getText().trim();
    }

    @Override
    public void setDescription(final String description) {
        if (description == null) {
            this.description.setText("");
        } else {
            this.description.setText(description);
        }
    }

    @Override
    public String getCollection() {
        return collection.getText().trim();
    }

    @Override
    public void setCollection(final String collection) {
        this.collection.setText(collection);
    }

    @Override
    public InstanceType getInstanceType() {
        return instanceType.getSelectedItem();
    }

    @Override
    public void setInstanceType(final InstanceType instanceType) {
        this.instanceType.setSelectedItem(instanceType);
    }

    @Override
    public List<String> getElasticUrls() {
        return Arrays.stream(ElasticUrls.getText().split("\n")).collect(Collectors.toList());
    }

    @Override
    public void setElasticUrls(final List<String> ElasticUrls) {
        if (ElasticUrls == null) {
            this.ElasticUrls.setText("");
        } else {
            this.ElasticUrls.setText(String.join("\n", ElasticUrls));
        }
    }

    @Override
    public boolean isUseZk() {
        return useZk.getBooleanValue();
    }

    @Override
    public void setUseZk(final boolean useZk) {
        this.useZk.setBooleanValue(useZk);
    }

    @Override
    public List<String> getZkHosts() {
        return Arrays.stream(zkHosts.getText().split("\n")).collect(Collectors.toList());
    }

    @Override
    public void setZkHosts(final List<String> zkHosts) {
        if (zkHosts == null) {
            this.zkHosts.setText("");
        } else {
            this.zkHosts.setText(String.join("\n", zkHosts));
        }
    }

    @Override
    public String getZkPath() {
        return zkPath.getText();
    }

    @Override
    public void setZkPath(final String zkPath) {
        if (zkPath == null) {
            this.zkPath.setText("");
        } else {
            this.zkPath.setText(zkPath);
        }
    }

    @Override
    public void setRententionExpressionView(final View view) {
        retentionExpressionPanel.setWidget(view.asWidget());
    }

    @Override
    public void onReadOnly(final boolean readOnly) {
        description.setEnabled(!readOnly);
        instanceType.setEnabled(!readOnly);
        ElasticUrls.setEnabled(!readOnly);
        useZk.setEnabled(!readOnly);
        zkHosts.setEnabled(!readOnly);
        zkPath.setEnabled(!readOnly);
    }

    @UiHandler("testConnection")
    public void onTestConnectionClick(final ClickEvent event) {
        if (getUiHandlers() != null) {
            getUiHandlers().onTestConnection();
        }
    }

    public interface Binder extends UiBinder<Widget, ElasticIndexSettingsViewImpl> {
    }
}
