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

package stroom.shapeshifter.client.view;

import stroom.shapeshifter.client.presenter.ModeEditorPresenter.ModeEditorView;
import stroom.shapeshifter.client.presenter.ModeEditorUiHandlers;
import stroom.shapeshifter.client.presenter.ModeRowData;

import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

import java.util.List;

public class ModeEditorViewImpl
        extends ViewWithUiHandlers<ModeEditorUiHandlers>
        implements ModeEditorView, ModeRow.Listener {

    private final Widget widget;

    @UiField
    FlowPanel rows;
    @UiField
    Label none;

    @Inject
    public ModeEditorViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void setRows(final List<ModeRowData> data, final boolean editable) {
        rows.clear();
        none.setVisible(data.isEmpty());
        for (final ModeRowData row : data) {
            rows.add(new ModeRow(row, this, editable));
        }
    }

    @Override
    public void onRename(final String from, final String to) {
        if (getUiHandlers() != null) {
            getUiHandlers().onRename(from, to);
        }
    }

    @Override
    public void onRemove(final String mode) {
        if (getUiHandlers() != null) {
            getUiHandlers().onRemove(mode);
        }
    }

    public interface Binder extends UiBinder<Widget, ModeEditorViewImpl> {

    }
}
