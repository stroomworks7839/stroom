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
import stroom.shapeshifter.config.Declaration;
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

/// A template's declarations (design 35): scalar, list, map or set, declared once, bound into by
/// captures and read by the body; scope flows to child templates. Listed as a section of the
/// template, each a row that is clicked to edit; add, edit and remove through
/// [DeclarationEditController].
public class DeclarationsController extends Controller {

    // From Contexts.READ_ONLY: true until the document says otherwise, so a
    // controller with no owner fails closed rather than editable.
    private boolean readOnly = true;

    private DefinitionList view;

    private final Provider<DeclarationEditController> editPresenterProvider;

    private ProjectHost host;
    private String templateId;

    @Inject
    public DeclarationsController(final EventBus eventBus,
                             final Provider<DeclarationEditController> editPresenterProvider) {
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
    public DeclarationsController attach(final DefinitionList view) {
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
            for (final Declaration declaration : template.declarations()) {
                rows.add(new Row(declaration.name(), describe(declaration)));
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
        final DeclarationEditController editor = editPresenterProvider.get();
        editor.read(null);
        editor.show(this, "New Declaration", e -> {
            if (e.isOk()) {
                final Declaration declaration = editor.write();
                if (declaration != null) {
                    final List<Declaration> declarations = new ArrayList<>(template.declarations());
                    declarations.add(declaration);
                    host.replace(host.withTemplate(Templates.withDeclarations(template, declarations)));
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
        if (template == null || readOnly || index >= template.declarations().size()) {
            return;
        }
        final Declaration existing = template.declarations().get(index);
        final DeclarationEditController editor = editPresenterProvider.get();
        editor.read(existing);
        editor.show(this, "Edit Declaration", e -> {
            if (e.isOk()) {
                final Declaration declaration = editor.write();
                if (declaration != null) {
                    final List<Declaration> declarations = new ArrayList<>(template.declarations());
                    declarations.set(index, declaration);
                    host.replace(host.withTemplate(Templates.withDeclarations(template, declarations)));
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
        if (template == null || readOnly || index >= template.declarations().size()) {
            return;
        }
        final Declaration existing = template.declarations().get(index);
        final String message = "Remove declaration '" + existing.name() + "'? Captures and body references to"
                               + " it will no longer compile.";
        ConfirmEvent.fire(this, message, ok -> {
            if (ok) {
                final List<Declaration> declarations = new ArrayList<>(template.declarations());
                declarations.remove(index);
                host.replace(host.withTemplate(Templates.withDeclarations(template, declarations)));
            }
        });
    }

    // What a declaration is, beside its name: its type, and for a map how much it starts with.
    private static String describe(final Declaration declaration) {
        final String type = declaration.type().name().toLowerCase(Locale.ROOT);
        return declaration.type() == Declaration.Type.MAP
                ? type + " · " + declaration.entries().size() + " entries"
                : type;
    }
}
