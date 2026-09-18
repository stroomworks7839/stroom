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

import stroom.alert.client.event.ConfirmEvent;
import stroom.shapeshifter.client.presenter.BodyPresenter.BodyView;
import stroom.shapeshifter.client.presenter.Instructions.Category;
import stroom.shapeshifter.config.Condition;
import stroom.shapeshifter.config.Declaration;
import stroom.shapeshifter.config.EngineVars;
import stroom.shapeshifter.config.OutputNode;
import stroom.shapeshifter.config.OutputNode.Choose;
import stroom.shapeshifter.config.OutputNode.Holder;
import stroom.shapeshifter.config.OutputNode.Switch;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.config.Template.ParamDecl;
import stroom.widget.menu.client.presenter.GroupHeading;
import stroom.widget.menu.client.presenter.IconMenuItem;
import stroom.widget.menu.client.presenter.Item;
import stroom.widget.menu.client.presenter.ShowMenuEvent;
import stroom.widget.popup.client.presenter.PopupPosition;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The body as a card list (design 18 §5.6; 43 §4.2): each instruction a card with its kind and
 * a summary, each holder's branches card lists of their own, nested one level in, each with its
 * own <i>+ instruction</i> line. Click a card to edit it; the actions move it, delete it, or add
 * after it; a <i>when</i> or <i>case</i> head is edited and pruned on its own line. Every edit
 * is a rewrite through {@link Bodies} landing on the host as the template's new body. What a
 * card shows about the run — the output it wrote, the children it dispatched — is phase B.
 */
public class BodyPresenter
        extends MyPresenterWidget<BodyView>
        implements BodyUiHandlers {

    private final InstructionEditPresenter instructionEditor;
    private final ConditionEditPresenter conditionEditor;

    private ProjectHost host;
    private String templateId;
    private List<OutputNode> body = List.of();

    @Inject
    public BodyPresenter(final EventBus eventBus,
                         final BodyView view,
                         final InstructionEditPresenter instructionEditor,
                         final ConditionEditPresenter conditionEditor) {
        super(eventBus, view);
        this.instructionEditor = instructionEditor;
        this.conditionEditor = conditionEditor;
        view.setUiHandlers(this);
    }

    public void setHost(final ProjectHost host) {
        this.host = host;
    }

    public void setTemplate(final String id) {
        this.templateId = id;
        final Template template = host.template(id);
        if (template == null) {
            body = List.of();
        } else {
            body = template.body();
        }
        getView().setEnabled(template != null && !host.isReadOnly());
        getView().setBody(body);
    }

    private void apply(final List<OutputNode> next) {
        final Template template = host.template(templateId);
        if (template == null || host.isReadOnly()) {
            return;
        }
        host.replace(host.withTemplate(Templates.withBody(template, next)));
    }

    private List<String> names() {
        final Template template = host.template(templateId);
        final Set<String> names = new LinkedHashSet<>();
        if (template != null) {
            for (final Declaration declaration : template.declarations()) {
                names.add(declaration.name());
            }
            for (final ParamDecl param : template.param()) {
                names.add(param.name());
            }
        }
        for (final EngineVars function : EngineVars.values()) {
            names.add(function.spelling());
        }
        return new ArrayList<>(names);
    }

    // ---- the cards ----

    @Override
    public void onEdit(final String path) {
        final int[] nodePath = Bodies.path(path);
        final OutputNode node = Bodies.get(body, nodePath);
        if (node == null || host.isReadOnly()) {
            return;
        }
        instructionEditor.read(node, null, names(), Modes.of(host.getProject()));
        instructionEditor.show("Edit " + Instructions.kind(node), e -> {
            if (e.isOk()) {
                final OutputNode edited = instructionEditor.write();
                if (edited != null) {
                    apply(Bodies.replace(body, nodePath, edited));
                    e.hide();
                }
            } else {
                e.hide();
            }
        });
    }

    @Override
    public void onRemove(final String path) {
        final int[] nodePath = Bodies.path(path);
        final OutputNode node = Bodies.get(body, nodePath);
        if (node == null || host.isReadOnly()) {
            return;
        }
        int held = 0;
        if (node instanceof Holder holder) {
            for (final List<OutputNode> branch : holder.bodies()) {
                held += branch.size();
            }
        }
        if (held == 0) {
            apply(Bodies.remove(body, nodePath));
            return;
        }
        final String message = "Remove this " + Instructions.kind(node) + " and the " + held + (held == 1
                ? " instruction"
                : " instructions") + " it holds?";
        ConfirmEvent.fire(this, message, ok -> {
            if (ok) {
                apply(Bodies.remove(body, nodePath));
            }
        });
    }

    @Override
    public void onMove(final String path, final int by) {
        if (host.isReadOnly()) {
            return;
        }
        final List<OutputNode> next = Bodies.move(body, Bodies.path(path), by);
        if (next != body) {
            apply(next);
        }
    }

    /** The add menu, grouped by category, for a list path and an index in it. */
    @Override
    public void onAdd(final String listPath, final int index, final int x, final int y) {
        if (host.isReadOnly()) {
            return;
        }
        final int[] list = Bodies.path(listPath);
        final List<Item> items = new ArrayList<>();
        int priority = 0;
        Category group = null;
        for (final String kind : InstructionEditPresenter.kinds()) {
            final Category category = kind.startsWith("(")
                    ? null
                    : Instructions.category(kind);
            if (category != group) {
                group = category;
                items.add(new GroupHeading(priority++, category == null
                        ? "any"
                        : category.label()));
            }
            items.add(new IconMenuItem.Builder()
                    .priority(priority++)
                    .text(kind)
                    .command(() -> add(list, index, kind))
                    .build());
        }
        ShowMenuEvent.builder()
                .items(items)
                .popupPosition(new PopupPosition(x, y))
                .fire(this);
    }

    private void add(final int[] list, final int index, final String kind) {
        instructionEditor.read(null, kind, names(), Modes.of(host.getProject()));
        instructionEditor.show("New " + kind, e -> {
            if (e.isOk()) {
                final OutputNode node = instructionEditor.write();
                if (node != null) {
                    apply(Bodies.insert(body, list, index, node));
                    e.hide();
                }
            } else {
                e.hide();
            }
        });
    }

    // ---- the branches ----

    @Override
    public void onEditBranch(final String path, final int branch) {
        final int[] nodePath = Bodies.path(path);
        final OutputNode node = Bodies.get(body, nodePath);
        if (host.isReadOnly() || !(node instanceof Holder holder) || !Bodies.branchIsOwn(holder, branch)) {
            return;
        }
        if (node instanceof Choose choose) {
            conditionEditor.read(choose.when().get(branch).test(), names());
            conditionEditor.show("Edit when", e -> {
                if (e.isOk()) {
                    final Condition test = conditionEditor.write();
                    if (test != null) {
                        apply(Bodies.replace(body, nodePath, Bodies.withWhen(choose, branch, test)));
                        e.hide();
                    }
                } else {
                    e.hide();
                }
            });
        } else if (node instanceof Switch s) {
            getView().promptCase(s.cases().get(branch).value(), value -> {
                if (value != null && !value.trim().isEmpty()) {
                    apply(Bodies.replace(body, nodePath, Bodies.withCase(s, branch, value.trim())));
                }
            });
        }
    }

    @Override
    public void onAddBranch(final String path) {
        final int[] nodePath = Bodies.path(path);
        final OutputNode node = Bodies.get(body, nodePath);
        if (host.isReadOnly()) {
            return;
        }
        if (node instanceof Choose choose) {
            conditionEditor.read(new Condition.IsFirst(), names());
            conditionEditor.show("New when", e -> {
                if (e.isOk()) {
                    final Condition test = conditionEditor.write();
                    if (test != null) {
                        apply(Bodies.replace(body, nodePath, Bodies.addWhen(choose, test)));
                        e.hide();
                    }
                } else {
                    e.hide();
                }
            });
        } else if (node instanceof Switch s) {
            getView().promptCase("", value -> {
                if (value != null && !value.trim().isEmpty()) {
                    apply(Bodies.replace(body, nodePath, Bodies.addCase(s, value.trim())));
                }
            });
        }
    }

    @Override
    public void onRemoveBranch(final String path, final int branch) {
        final int[] nodePath = Bodies.path(path);
        final OutputNode node = Bodies.get(body, nodePath);
        if (host.isReadOnly() || !(node instanceof Holder holder) || !Bodies.branchIsOwn(holder, branch)) {
            return;
        }
        final int held = holder.bodies().get(branch).size();
        final Runnable remove = () -> {
            if (node instanceof Choose choose) {
                apply(Bodies.replace(body, nodePath, Bodies.removeWhen(choose, branch)));
            } else if (node instanceof Switch s) {
                apply(Bodies.replace(body, nodePath, Bodies.removeCase(s, branch)));
            }
        };
        if (held == 0) {
            remove.run();
            return;
        }
        final String message = "Prune this branch and the " + held + (held == 1
                ? " instruction"
                : " instructions") + " it holds?";
        ConfirmEvent.fire(this, message, ok -> {
            if (ok) {
                remove.run();
            }
        });
    }

    public interface BodyView extends View, HasUiHandlers<BodyUiHandlers> {

        void setEnabled(boolean enabled);

        void setBody(List<OutputNode> body);

        /** Ask for a case value; the answer is null when cancelled. */
        void promptCase(String current, java.util.function.Consumer<String> then);
    }
}
