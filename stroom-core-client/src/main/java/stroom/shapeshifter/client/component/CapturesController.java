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

import stroom.alert.client.event.ConfirmEvent;
import stroom.shapeshifter.client.component.DefinitionList.Row;
import stroom.shapeshifter.client.model.ProjectHost;
import stroom.shapeshifter.client.model.Templates;
import stroom.shapeshifter.config.CaptureBinding;
import stroom.shapeshifter.config.CaptureBinding.CaptureSource;
import stroom.shapeshifter.config.Template;
import stroom.widget.component.client.Contexts;
import stroom.widget.component.client.Controller;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/// A template's captures: what binds a pattern's group or label — or a selection over the values
/// in scope — into a declared name, with a cast. Listed as a section of the template, each a
/// row that is clicked to edit; add, edit and remove through [CaptureEditController].
public class CapturesController extends Controller {

    // From Contexts.READ_ONLY: true until the document says otherwise, so a
    // controller with no owner fails closed rather than editable.
    private boolean readOnly = true;

    private DefinitionList view;

    private final Provider<CaptureEditController> editPresenterProvider;

    private ProjectHost host;
    private String templateId;

    @Inject
    public CapturesController(final EventBus eventBus,
                             final Provider<CaptureEditController> editPresenterProvider) {
        super(eventBus);
        watch(Contexts.READ_ONLY, readOnly -> {
            this.readOnly = readOnly;
            enable();
        });
        this.editPresenterProvider = editPresenterProvider;
    }

    /// Attaches this to the section of the template that lists them, which is then this
    /// controller's widget.
    ///
    /// @param view The section.
    /// @return This, for its owner to keep.
    public CapturesController attach(final DefinitionList view) {
        this.view = Objects.requireNonNull(view, "view");
        initWidget(view);
        view.setOnAdd(this::onAdd);
        view.setOnEdit(this::onEdit);
        view.setOnRemove(this::onRemove);
        enable();
        return this;
    }

    public void setHost(final ProjectHost host) {
        this.host = host;
    }

    public void setTemplate(final String id) {
        this.templateId = id;
        final Template template = host.template(id);
        final List<Row> rows = new ArrayList<>();
        if (template != null) {
            for (final CaptureBinding capture : template.captures()) {
                rows.add(new Row(capture.name(), describe(capture)));
            }
        }
        view.setRows(rows);
        enable();
    }

    private void enable() {
        if (view != null) {
            view.setEnabled(host != null && !readOnly && templateId != null);
        }
    }

    private void onAdd() {
        final Template template = host.template(templateId);
        if (template == null || readOnly) {
            return;
        }
        final CaptureEditController editor = editPresenterProvider.get();
        editor.read(host.getProject(), template, new CaptureBinding("", new CaptureSource.Group(1), null));
        editor.show(this, "New Capture", e -> {
            if (e.isOk()) {
                final CaptureBinding capture = editor.write();
                if (capture != null) {
                    final List<CaptureBinding> captures = new ArrayList<>(template.captures());
                    captures.add(capture);
                    host.replace(host.withTemplate(Templates.withCaptures(template, captures)));
                    e.hide();
                } else {
                    // Nothing was written, so the dialog stays open — and its OK button has to come back
                    // out of its busy state, or the alert leaves it spinning for good.
                    e.reset();
                }
            } else {
                e.hide();
            }
        });
    }

    private void onEdit(final int index) {
        final Template template = host.template(templateId);
        if (template == null || readOnly || index >= template.captures().size()) {
            return;
        }
        final CaptureBinding existing = template.captures().get(index);
        final CaptureEditController editor = editPresenterProvider.get();
        editor.read(host.getProject(), template, existing);
        editor.show(this, "Edit Capture", e -> {
            if (e.isOk()) {
                final CaptureBinding capture = editor.write();
                if (capture != null) {
                    final List<CaptureBinding> captures = new ArrayList<>(template.captures());
                    captures.set(index, capture);
                    host.replace(host.withTemplate(Templates.withCaptures(template, captures)));
                    e.hide();
                } else {
                    // Nothing was written, so the dialog stays open — and its OK button has to come back
                    // out of its busy state, or the alert leaves it spinning for good.
                    e.reset();
                }
            } else {
                e.hide();
            }
        });
    }

    private void onRemove(final int index) {
        final Template template = host.template(templateId);
        if (template == null || readOnly || index >= template.captures().size()) {
            return;
        }
        final CaptureBinding existing = template.captures().get(index);
        final String message = "Remove capture '" + existing.name() + "'?";
        ConfirmEvent.fire(this, message, ok -> {
            if (ok) {
                final List<CaptureBinding> captures = new ArrayList<>(template.captures());
                captures.remove(index);
                host.replace(host.withTemplate(Templates.withCaptures(template, captures)));
            }
        });
    }

    // What a capture is, beside its name: where the value comes from, and what it is cast to.
    private static String describe(final CaptureBinding capture) {
        final String from = "← " + Templates.describe(capture.select());
        return capture.as() == null
                ? from
                : from + " as " + capture.as().name().toLowerCase(Locale.ROOT);
    }
}
