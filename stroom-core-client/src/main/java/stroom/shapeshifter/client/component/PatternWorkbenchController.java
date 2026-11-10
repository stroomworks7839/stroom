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

package stroom.shapeshifter.client.component;

import stroom.shapeshifter.client.model.ProjectHost;
import stroom.shapeshifter.config.Template;
import stroom.svg.shared.SvgImage;
import stroom.widget.button.client.ToolbarButton;
import stroom.widget.component.client.Controller;
import stroom.widget.util.client.MouseUtil;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

/**
 * The pattern workbench (design 18 §5.6, design 44): an in-place mode of the Design tab, not a
 * dialog and not full-screen. Its subject is a template's match; the sample to try it against
 * is on the left, the match editor — the kind and its form — on the right, and guard and limits
 * sit beneath, mechanism-independent, belonging to the workbench itself. There is no Apply or
 * Cancel — every field commits as it changes, as everywhere else — so closing, or retargeting to
 * another template, never has anything to lose.
 */
public class PatternWorkbenchController extends Controller {

    private final PatternWorkbench view;

    private final SampleController sample;
    private final MatchEditorController matchEditor;
    private final GuardAndLimitsController guardAndLimits;
    private final ToolbarButton closeButton;

    private ProjectHost host;
    private Runnable onClose;

    @Inject
    public PatternWorkbenchController(final EventBus eventBus,
                                     final PatternWorkbench view,
                                     final SampleController sample,
                                     final MatchEditorController matchEditor,
                                     final GuardAndLimitsController guardAndLimits) {
        super(eventBus);
        this.view = view;
        initWidget(view);
        this.sample = sample;
        this.matchEditor = matchEditor;
        this.guardAndLimits = guardAndLimits;
        matchEditor.setOnGroupSelect(sample::isolate);
        matchEditor.setOnLabelSelect(sample::isolate);
        view.setSample(sample);
        view.setEditor(matchEditor);
        view.setGuardAndLimits(guardAndLimits);
        // Enabled from the start: closing has nothing to decide (design 18 §5.6 - every field
        // has already committed), and SvgImage.CLOSE is a disabled preset.
        closeButton = view.getToolbar().button(SvgImage.CLOSE, "Close the workbench (Escape)", () -> {
        });
        own(closeButton.addClickHandler(event -> {
            if (MouseUtil.isPrimary(event) && onClose != null) {
                onClose.run();
            }
        }));

    }


    /** Closed: the match editor's drafts of the kinds not chosen go with the session. */
    public void closed() {
        matchEditor.forget();
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
        view.setSubject(template == null
                ? ""
                : template.name());
        view.showGuardAndLimits(true);
        matchEditor.setTemplate(id);
        guardAndLimits.setTemplate(id);
        sample.setTemplate(id);
    }

    /** A part of the project's library as the subject (design 44 §3): a tree with no template around it. */
    public void setPattern(final String name) {
        view.setSubject("part " + name);
        view.showGuardAndLimits(false);
        matchEditor.setPattern(name);
        sample.setPattern(name);
    }

}
