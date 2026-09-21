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

import stroom.dispatch.client.RestFactory;
import stroom.shapeshifter.client.presenter.SourceConfigPresenter.SourceConfigView;
import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.Dispatch;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.Project.SourceConfig;
import stroom.shapeshifter.shared.ShapeshifterResource;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.List;

/**
 * The source panel: what the {@code source} row of the template panel edits — the project's name
 * and version and its {@link SourceConfig}. Design 43 §3 Q5: there is no Settings tab; encoding,
 * buffer size and dispatch are part of the project and live here. Every field commits when it
 * changes, as everything in the editor does.
 */
public class SourceConfigPresenter
        extends MyPresenterWidget<SourceConfigView>
        implements SourceConfigUiHandlers {

    private static final ShapeshifterResource RESOURCE = GWT.create(ShapeshifterResource.class);

    private ProjectHost host;
    private boolean reading;

    @Inject
    public SourceConfigPresenter(final EventBus eventBus,
                                 final SourceConfigView view,
                                 final RestFactory restFactory) {
        super(eventBus, view);
        view.setUiHandlers(this);
        restFactory
                .create(RESOURCE)
                .method(ShapeshifterResource::encodings)
                .onSuccess(encodings -> {
                    // The list arriving is not an edit.
                    reading = true;
                    try {
                        getView().setEncodings(encodings);
                    } finally {
                        reading = false;
                    }
                })
                .taskMonitorFactory(this)
                .exec();
    }

    public void setHost(final ProjectHost host) {
        this.host = host;
    }

    public void refresh() {
        final Project project = host.getProject();
        reading = true;
        try {
            getView().setEnabled(project != null && !host.isReadOnly());
            if (project == null) {
                return;
            }
            final SourceConfig source = project.source();
            getView().setName(project.name());
            getView().setVersion(project.version());
            getView().setBufferSize(source.bufferSize());
            getView().setIgnoreErrors(source.ignoreErrors());
            getView().setEncoding(source.encoding());
            getView().setDispatch(source.dispatch());
            getView().setStrictValues(source.strictValues());
            getView().setMaxSequenceEntries(source.maxSequenceEntries());
        } finally {
            reading = false;
        }
    }

    @Override
    public void onChange() {
        if (reading || host == null || host.getProject() == null || host.isReadOnly()) {
            return;
        }
        final Project project = host.getProject();
        try {
            final SourceConfig source = new SourceConfig(
                    getView().getBufferSize(),
                    getView().isIgnoreErrors(),
                    blankToNull(getView().getEncoding()),
                    getView().getDispatch(),
                    getView().isStrictValues(),
                    getView().getMaxSequenceEntries());
            host.replace(new Project(getView().getName(), getView().getVersion(), source, project.templates()));
        } catch (final ConfigException | NumberFormatException e) {
            getView().setError(e.getMessage());
            return;
        }
        getView().setError(null);
    }

    private static String blankToNull(final String text) {
        return text == null || text.trim().isEmpty()
                ? null
                : text.trim();
    }

    public interface SourceConfigView extends View, HasUiHandlers<SourceConfigUiHandlers> {

        void setEnabled(boolean enabled);

        void setError(String error);

        String getName();

        void setName(String name);

        int getVersion();

        void setVersion(int version);

        int getBufferSize();

        void setBufferSize(int bufferSize);

        boolean isIgnoreErrors();

        void setIgnoreErrors(boolean ignoreErrors);

        String getEncoding();

        void setEncoding(String encoding);

        /** The engine's encodings by label; "auto", which a blank means, is among them. */
        void setEncodings(List<String> encodings);

        Dispatch getDispatch();

        void setDispatch(Dispatch dispatch);

        boolean isStrictValues();

        void setStrictValues(boolean strictValues);

        int getMaxSequenceEntries();

        void setMaxSequenceEntries(int maxSequenceEntries);
    }
}
