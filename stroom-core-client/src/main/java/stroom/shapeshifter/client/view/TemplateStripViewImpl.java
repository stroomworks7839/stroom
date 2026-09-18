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

import stroom.shapeshifter.client.presenter.TemplateStripPresenter.TemplateStripView;
import stroom.shapeshifter.client.presenter.TemplateStripUiHandlers;

import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.FocusPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.ThinSplitLayoutPanel;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.View;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

public class TemplateStripViewImpl
        extends ViewWithUiHandlers<TemplateStripUiHandlers>
        implements TemplateStripView {

    private final Widget widget;

    @UiField
    ThinSplitLayoutPanel layout;
    @UiField
    Label title;
    @UiField
    Label chip;
    @UiField
    Label name;
    @UiField
    Label mode;
    @UiField
    Label note;
    @UiField
    FlowPanel matchLine;
    @UiField
    FocusPanel matchChip;
    @UiField
    Label matchKind;
    @UiField
    Label matchSummary;
    @UiField
    Label guardLimits;
    @UiField
    SimplePanel content;
    @UiField
    ThinSplitLayoutPanel details;
    @UiField
    SimplePanel declarations;
    @UiField
    SimplePanel captures;

    @Inject
    public TemplateStripViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @UiHandler("chip")
    void onChip(final ClickEvent e) {
        editIdentity();
    }

    @UiHandler("name")
    void onName(final ClickEvent e) {
        editIdentity();
    }

    @UiHandler("matchChip")
    void onMatchChip(final ClickEvent e) {
        openWorkbench();
    }

    @UiHandler("guardLimits")
    void onGuardLimits(final ClickEvent e) {
        openWorkbench();
    }

    private void editIdentity() {
        if (getUiHandlers() != null) {
            getUiHandlers().onEditIdentity();
        }
    }

    private void openWorkbench() {
        if (getUiHandlers() != null) {
            getUiHandlers().onOpenWorkbench();
        }
    }

    @Override
    public void setHeader(final String titleText, final String colour, final String nameText, final String modeText,
                          final String noteText) {
        title.setText(titleText);
        chip.setVisible(colour != null);
        if (colour != null) {
            chip.getElement().getStyle().setBackgroundColor(colour);
        }
        name.setText(nameText);
        name.setStyleDependentName("editable", colour != null);
        mode.setText(modeText);
        note.setText(noteText);
    }

    @Override
    public void setMatch(final String kind, final String summary, final String guardAndLimits) {
        matchKind.setText(kind);
        matchSummary.setText(summary);
        matchSummary.setTitle(summary);
        guardLimits.setText(guardAndLimits);
    }

    @Override
    public void setMatchVisible(final boolean visible) {
        matchLine.setVisible(visible);
    }

    @Override
    public void setContent(final View view) {
        content.setWidget(view == null
                ? null
                : view.asWidget());
        content.setVisible(view != null);
    }

    @Override
    public void setDetails(final View declarationsView, final View capturesView) {
        declarations.setWidget(declarationsView.asWidget());
        captures.setWidget(capturesView.asWidget());
    }

    @Override
    public void setDetailsVisible(final boolean visible) {
        layout.setWidgetHidden(details, !visible);
    }

    public interface Binder extends UiBinder<Widget, TemplateStripViewImpl> {

    }
}
