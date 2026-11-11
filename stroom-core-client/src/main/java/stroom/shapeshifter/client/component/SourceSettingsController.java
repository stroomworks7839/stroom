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

package stroom.shapeshifter.client.component;

import stroom.dispatch.client.RestFactory;
import stroom.shapeshifter.client.model.ProjectHost;
import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.Project.SourceConfig;
import stroom.shapeshifter.shared.ShapeshifterResource;
import stroom.widget.component.client.Contexts;
import stroom.widget.component.client.Controller;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;


/// The source panel: what the `source` row of the template panel edits — the project's name
/// and version and its [SourceSettings]. Design 43 §3 Q5: there is no Settings tab; encoding,
/// buffer size and dispatch are part of the project and live here. Every field commits when it
/// changes, as everything in the editor does.
public class SourceSettingsController extends Controller {

    // From Contexts.READ_ONLY: true until the document says otherwise, so a
    // controller with no owner fails closed rather than editable.
    private boolean readOnly = true;

    private final SourceSettings view;

    private static final ShapeshifterResource RESOURCE = GWT.create(ShapeshifterResource.class);

    private ProjectHost host;
    private boolean reading;

    @Inject
    public SourceSettingsController(final EventBus eventBus,
                                 final SourceSettings view,
                                 final RestFactory restFactory) {
        super(eventBus);
        this.view = view;
        initWidget(view);
        watch(Contexts.READ_ONLY, readOnly -> this.readOnly = readOnly);
        view.setOnChange(this::onChange);
        restFactory
                .create(RESOURCE)
                .method(ShapeshifterResource::encodings)
                .onSuccess(encodings -> {
                    // The list arriving is not an edit.
                    reading = true;
                    try {
                        view.setEncodings(encodings);
                    } finally {
                        reading = false;
                    }
                })
                .owner(this)
                .exec();
    }

    public void setHost(final ProjectHost host) {
        this.host = host;
    }

    public void refresh() {
        final Project project = host.getProject();
        reading = true;
        try {
            view.setEnabled(project != null && !readOnly);
            if (project == null) {
                return;
            }
            final SourceConfig source = project.source();
            view.setName(project.name());
            view.setVersion(project.version());
            view.setBufferSize(source.bufferSize());
            view.setIgnoreErrors(source.ignoreErrors());
            view.setEncoding(source.encoding());
            view.setDispatch(source.dispatch());
            view.setStrictValues(source.strictValues());
            view.setMaxSequenceEntries(source.maxSequenceEntries());
        } finally {
            reading = false;
        }
    }

    public void onChange() {
        if (reading || host == null || host.getProject() == null || readOnly) {
            return;
        }
        final Project project = host.getProject();
        try {
            final SourceConfig source = new SourceConfig(
                    view.getBufferSize(),
                    view.isIgnoreErrors(),
                    blankToNull(view.getEncoding()),
                    view.getDispatch(),
                    view.isStrictValues(),
                    view.getMaxSequenceEntries());
            host.replace(new Project(view.getName(), view.getVersion(), source, project.templates(),
                    project.patterns()));
        } catch (final ConfigException | NumberFormatException e) {
            view.setError(e.getMessage());
            return;
        }
        view.setError(null);
    }

    private static String blankToNull(final String text) {
        return text == null || text.trim().isEmpty()
                ? null
                : text.trim();
    }

}
