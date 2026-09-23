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

import stroom.docref.DocRef;
import stroom.shapeshifter.client.presenter.ShapeshifterAiStepPresenter.DocumentOpener;
import stroom.shapeshifter.client.presenter.ShapeshifterAiStepPresenter.ShapeshifterAiStepView;

import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.Anchor;
import com.google.gwt.user.client.ui.HTML;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewImpl;

public class ShapeshifterAiStepViewImpl extends ViewImpl implements ShapeshifterAiStepView {

    private final Widget widget;

    @UiField
    Label decision;
    @UiField
    HTML summary;
    @UiField
    Anchor fragment;
    @UiField
    HTML verdicts;
    @UiField
    HTML transcript;

    private DocumentOpener opener;
    private DocRef fragmentRef;

    @Inject
    public ShapeshifterAiStepViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void setDecision(final String text) {
        decision.setText(text);
    }

    @Override
    public void setSummary(final SafeHtml html) {
        summary.setHTML(html);
    }

    @Override
    public void setVerdicts(final SafeHtml html) {
        verdicts.setHTML(html);
    }

    @Override
    public void setTranscript(final SafeHtml html) {
        transcript.setHTML(html);
    }

    @Override
    public void setFragment(final DocRef fragmentRef) {
        this.fragmentRef = fragmentRef;
        fragment.setText(fragmentRef == null
                ? ""
                : fragmentRef.getName());
        fragment.setVisible(fragmentRef != null);
    }

    @Override
    public void setDocumentOpener(final DocumentOpener opener) {
        this.opener = opener;
    }

    @UiHandler("fragment")
    public void onFragmentClick(final ClickEvent event) {
        if (opener != null && fragmentRef != null) {
            opener.open(fragmentRef);
        }
    }


    // --------------------------------------------------------------------------------


    public interface Binder extends UiBinder<Widget, ShapeshifterAiStepViewImpl> {

    }
}
