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

import stroom.shapeshifter.client.presenter.TemplatePanelPresenter.TemplatePanelView;
import stroom.shapeshifter.client.presenter.TemplatePanelUiHandlers;
import stroom.shapeshifter.client.presenter.TemplateRowData;
import stroom.svg.client.Preset;
import stroom.widget.button.client.ButtonPanel;
import stroom.widget.button.client.ButtonView;

import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class TemplatePanelViewImpl
        extends ViewWithUiHandlers<TemplatePanelUiHandlers>
        implements TemplatePanelView, TemplateRow.Listener {

    private final Widget widget;
    private final List<TemplateRow> rows = new ArrayList<>();

    @UiField
    ButtonPanel toolbar;
    @UiField
    FlowPanel list;

    @Inject
    public TemplatePanelViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public ButtonView addButton(final Preset preset) {
        return toolbar.addButton(preset);
    }

    @Override
    public void setRows(final List<TemplateRowData> data) {
        list.clear();
        rows.clear();
        String section = null;
        for (final TemplateRowData row : data) {
            if (row.isSection()) {
                // A section heading. SETTINGS and DATA are destinations and so are rows that
                // render as headings; TEMPLATES heads the list and is a label (design 44 §5m).
                section = null;
                if (row.getId() == null) {
                    final Label head = new Label(row.getName());
                    head.setStyleName("ss-panel-section");
                    list.add(head);
                } else {
                    final TemplateRow widget = new TemplateRow(row, this);
                    widget.addStyleName("ss-panel-section ss-panel-section--link");
                    rows.add(widget);
                    list.add(widget);
                }
                continue;
            }
            if (row.getSection() != null && !Objects.equals(section, row.getSection())) {
                section = row.getSection();
                final Label head = new Label(section);
                head.setStyleName("ss-mode-head");
                list.add(head);
            }
            final TemplateRow widget = new TemplateRow(row, this);
            rows.add(widget);
            list.add(widget);
        }
    }

    @Override
    public void setHot(final String id) {
        for (final TemplateRow row : rows) {
            row.setHot(id != null && Objects.equals(row.getData().getId(), id));
        }
    }

    @Override
    public void setSelected(final String id) {
        for (final TemplateRow row : rows) {
            row.setSelected(Objects.equals(row.getData().getId(), id));
        }
    }

    @Override
    public void onSelect(final String id) {
        if (getUiHandlers() != null) {
            getUiHandlers().onSelect(id);
        }
    }

    @Override
    public void onHover(final String id) {
        getUiHandlers().onHover(id);
    }

    @Override
    public void onOpen(final String id) {
        if (getUiHandlers() != null) {
            getUiHandlers().onOpen(id);
        }
    }

    public interface Binder extends UiBinder<Widget, TemplatePanelViewImpl> {

    }
}
