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

import stroom.alert.client.event.AlertEvent;
import stroom.alert.client.event.ConfirmEvent;
import stroom.shapeshifter.client.presenter.ModeEditorPresenter.ModeEditorView;
import stroom.shapeshifter.config.Project;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupType;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The mode editor (design 18 §5.6): the one place modes are renamed and removed. A rename
 * moves the mode's templates and every apply-templates site naming it; removal is of empty
 * modes only, with a warning when sites still dispatch into it. A mode is created by giving it
 * to a template - modes are not declared, they exist through their templates and sites - so
 * there is nothing to add here. Edits are live, like the rest of the editor.
 */
public class ModeEditorPresenter
        extends MyPresenterWidget<ModeEditorView>
        implements ModeEditorUiHandlers {

    private ProjectHost host;

    @Inject
    public ModeEditorPresenter(final EventBus eventBus, final ModeEditorView view) {
        super(eventBus, view);
        view.setUiHandlers(this);
    }

    public void setHost(final ProjectHost host) {
        this.host = host;
    }

    public void show() {
        refresh();
        ShowPopupEvent.builder(this)
                .popupType(PopupType.CLOSE_DIALOG)
                .popupSize(PopupSize.resizable(520, 360))
                .caption("Modes")
                .onHideRequest(e -> e.hide())
                .fire();
    }

    private void refresh() {
        final Project project = host.getProject();
        final List<ModeRowData> rows = new ArrayList<>();
        if (project != null) {
            for (final String mode : Modes.of(project)) {
                rows.add(new ModeRowData(mode, Modes.templateCount(project, mode),
                        Modes.applySiteCount(project, mode)));
            }
        }
        getView().setRows(rows, !host.isReadOnly());
    }

    @Override
    public void onRename(final String from, final String to) {
        final Project project = host.getProject();
        final String name = to == null
                ? ""
                : to.trim();
        if (project == null || host.isReadOnly() || name.equals(from)) {
            return;
        }
        if (name.isEmpty()) {
            AlertEvent.fireWarn(this, "A mode needs a name; to dispatch into the root, give the templates no mode",
                    null);
            refresh();
            return;
        }
        if (Modes.of(project).contains(name)) {
            AlertEvent.fireWarn(this, "There is already a mode named '" + name + "'; renaming into it would"
                                      + " merge the two", null);
            refresh();
            return;
        }
        host.replace(Modes.rename(project, from, name));
        refresh();
    }

    @Override
    public void onRemove(final String mode) {
        final Project project = host.getProject();
        if (project == null || host.isReadOnly() || Modes.templateCount(project, mode) > 0) {
            return;
        }
        final int sites = Modes.applySiteCount(project, mode);
        final String message = sites == 0
                ? "Remove mode '" + mode + "'?"
                : "Remove mode '" + mode + "'? " + sites + (sites == 1
                        ? " apply-templates site still dispatches"
                        : " apply-templates sites still dispatch") + " into it; those become dispatches into the"
                  + " root, and the messages will say so.";
        ConfirmEvent.fire(this, message, ok -> {
            if (ok) {
                host.replace(Modes.removeSites(project, mode));
                refresh();
            }
        });
    }

    public interface ModeEditorView extends View, HasUiHandlers<ModeEditorUiHandlers> {

        void setRows(List<ModeRowData> rows, boolean editable);
    }
}
