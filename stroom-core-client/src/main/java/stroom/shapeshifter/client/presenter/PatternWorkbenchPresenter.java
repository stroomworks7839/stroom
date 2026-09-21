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

package stroom.shapeshifter.client.presenter;

import stroom.shapeshifter.client.presenter.PatternWorkbenchPresenter.PatternWorkbenchView;
import stroom.shapeshifter.config.Template;
import stroom.svg.client.SvgPresets;
import stroom.widget.button.client.ButtonView;
import stroom.widget.util.client.MouseUtil;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

/**
 * The pattern workbench (design 18 §5.6, design 44): an in-place mode of the Design tab, not a
 * dialog and not full-screen. Its subject is a template's match; the sample to try it against
 * is on the left, the match editor — the kind and its form — on the right, and guard and limits
 * sit beneath, mechanism-independent, belonging to the workbench itself. There is no Apply or
 * Cancel — every field commits as it changes, as everywhere else — so closing, or retargeting to
 * another template, never has anything to lose.
 */
public class PatternWorkbenchPresenter extends MyPresenterWidget<PatternWorkbenchView> {

    private final SamplePresenter sample;
    private final MatchEditorPresenter matchEditor;
    private final GuardAndLimitsPresenter guardAndLimits;
    private final ButtonView closeButton;

    private ProjectHost host;
    private Runnable onClose;

    @Inject
    public PatternWorkbenchPresenter(final EventBus eventBus,
                                     final PatternWorkbenchView view,
                                     final SamplePresenter sample,
                                     final MatchEditorPresenter matchEditor,
                                     final GuardAndLimitsPresenter guardAndLimits) {
        super(eventBus, view);
        this.sample = sample;
        this.matchEditor = matchEditor;
        this.guardAndLimits = guardAndLimits;
        matchEditor.setOnGroupSelect(sample::isolate);
        matchEditor.setOnLabelSelect(sample::isolate);
        view.setSample(sample.getView());
        view.setEditor(matchEditor.getView());
        view.setGuardAndLimits(guardAndLimits.getView());
        closeButton = view.addButton(SvgPresets.CLOSE.title("Close the workbench"));
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(closeButton.addClickHandler(event -> {
            if (MouseUtil.isPrimary(event) && onClose != null) {
                onClose.run();
            }
        }));
    }

    public void setHost(final ProjectHost host) {
        this.host = host;
        sample.setHost(host);
        matchEditor.setHost(host);
        guardAndLimits.setHost(host);
    }

    public void setOnClose(final Runnable onClose) {
        this.onClose = onClose;
    }

    public void setTemplate(final String id) {
        final Template template = host.template(id);
        getView().setSubject(template == null
                ? ""
                : template.name());
        matchEditor.setTemplate(id);
        guardAndLimits.setTemplate(id);
        sample.setTemplate(id);
    }

    public interface PatternWorkbenchView extends View {

        ButtonView addButton(stroom.svg.client.Preset preset);

        void setSubject(String name);

        void setSample(View view);

        void setEditor(View view);

        void setGuardAndLimits(View view);
    }
}
