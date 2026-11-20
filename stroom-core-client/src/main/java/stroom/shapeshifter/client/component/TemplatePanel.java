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

import stroom.shapeshifter.client.model.TemplateRowData;
import stroom.widget.button.client.LinkButton;
import stroom.widget.button.client.Toolbar;
import stroom.widget.component.client.Component;
import stroom.widget.text.client.Label;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.FocusPanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/// The panel's list: the project's sections, its templates under their modes, and its parts.
///
/// **A mode's list ends with `+ template`, and the modes end with `+ mode`** (design 47 B):
/// the same add link the declarations and the captures have (the owner, 2026-11-18). Pressed,
/// a link adds — a template called `new template`, a mode called `new mode` — and what was
/// added is shown beside this list with its name open to be changed. Nothing is named here.
///
/// **A mode's heading selects the mode**, whose own pane is where it is named; the root's is
/// only a heading.
///
/// **Four headings of one height, two of which fold** (ruled 2026-11-20). Settings and Data are
/// destinations, selected as a row is. Templates and Patterns are what they hold, and pressing
/// one folds it away or brings it back, with the explorer's arrow saying which
/// ([DisclosureButton]). What is folded stays folded while the panel is refreshed. A selection
/// inside a folded section stays where it is, and the heading is marked as holding it, so
/// folding loses nothing and changes nothing beside the panel.
public class TemplatePanel
        extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    private Consumer<String> onSelect = id -> {
    };
    private Consumer<String> onOpen = id -> {
    };
    private Consumer<String> onHover = id -> {
    };

    private final List<TemplateRow> rows = new ArrayList<>();
    // The add links on screen, by their ids
    // The headings on screen that select a mode, by the ids they select
    private final Map<String, FocusPanel> heads = new HashMap<>();
    // The sections that fold, as they are on screen
    private final List<Group> groups = new ArrayList<>();
    // The sections folded away, by name: kept across a refresh, which rebuilds the list
    private final Set<String> folded = new HashSet<>();
    // The row or heading selected, which a folded section may hold
    private String selected;

    @UiField
    Toolbar toolbar;
    @UiField
    FlowPanel list;

    public TemplatePanel() {
        initWidget(BINDER.createAndBindUi(this));
    }


    /// @return The toolbar its controller adds its actions to.
    public Toolbar getToolbar() {
        return toolbar;
    }

    /// Shows the rows, keeping folded what was folded.
    ///
    /// @param data The rows, in order: a section, then what it holds.
    public void setRows(final List<TemplateRowData> data) {
        list.clear();
        rows.clear();
        heads.clear();
        groups.clear();
        // Where the next row goes: the list, or the section that holds it
        FlowPanel into = list;
        Group group = null;
        String section = null;
        for (final TemplateRowData row : data) {
            if (row.isSection()) {
                // A section heading. Settings and Data are destinations and so are rows that
                // render as headings; Templates and Patterns hold what follows them, and fold
                // (design 44 §5m, ruled 2026-11-20).
                section = null;
                if (row.getId() == null) {
                    group = newGroup(row);
                    into = group.body;
                } else {
                    group = null;
                    into = list;
                    final TemplateRow widget = newRow(row);
                    widget.addStyleName("template-panel__section template-panel__section--link");
                    rows.add(widget);
                    list.add(widget);
                }
                continue;
            }
            if (row.getSection() != null && !Objects.equals(section, row.getSection())) {
                section = row.getSection();
                into.add(head(row));
                if (group != null && row.getSectionId() != null) {
                    group.ids.add(row.getSectionId());
                }
            }
            if (row.isFresh()) {
                into.add(addLink(row));
                continue;
            }
            final TemplateRow widget = newRow(row);
            rows.add(widget);
            into.add(widget);
            if (group != null) {
                group.ids.add(row.getId());
            }
        }
        markFolded();
    }

    // A section that folds: its heading, and under it what it holds
    private Group newGroup(final TemplateRowData row) {
        final Group group = new Group(row.getName());
        group.heading.addStyleName("template-panel__section template-panel__section--fold");
        group.heading.setText(row.getName());
        group.heading.setNote(row.getCount());
        group.heading.setOnClick(() -> {
            if (!folded.remove(group.name)) {
                folded.add(group.name);
            }
            fold(group);
            markFolded();
        });
        fold(group);
        list.add(group.heading);
        list.add(group.body);
        groups.add(group);
        return group;
    }

    private void fold(final Group group) {
        final boolean isFolded = folded.contains(group.name);
        group.heading.setExpanded(!isFolded);
        group.heading.setTitle((isFolded
                ? "Show the "
                : "Hide the ") + group.name.toLowerCase());
        group.body.setVisible(!isFolded);
    }

    // A folded section holding the selection says so, as the selection itself is out of sight
    private void markFolded() {
        for (final Group group : groups) {
            group.heading.setStyleName("template-panel__section--holds-selection",
                    folded.contains(group.name) && selected != null && group.ids.contains(selected));
        }
    }

    // A mode's heading: what the mode is, and under it, quieter, what is true of it — a mode
    // nothing dispatches into is the case the note was built for (design 46 §5.4). A named
    // mode's heading is pressed to select the mode; the root's is only a heading.
    private Widget head(final TemplateRowData row) {
        final FlowPanel headLine = new FlowPanel();
        headLine.setStyleName("template-panel__mode-head");
        final Label name = new Label(row.getSection());
        name.setStyleName("template-panel__mode-name");
        headLine.add(name);
        if (row.getSectionNote() != null) {
            final Label note = new Label(row.getSectionNote());
            note.setStyleName("template-panel__mode-note");
            headLine.add(note);
        }
        final String id = row.getSectionId();
        if (id == null) {
            return headLine;
        }
        final FocusPanel head = new FocusPanel(headLine);
        head.setStyleName("template-panel__mode-row");
        head.setTitle("The mode: its name, and the templates in it");
        head.addClickHandler(event -> onSelect.accept(id));
        head.addKeyDownHandler(event -> {
            if (event.getNativeKeyCode() == KeyCodes.KEY_ENTER || event.getNativeKeyCode() == ' ') {
                event.preventDefault();
                onSelect.accept(id);
            }
        });
        heads.put(id, head);
        return head;
    }

    // An add link, on a line of its own: `+ template` under a mode's templates, `+ mode` under
    // the modes. The same link the declarations and the captures are added with.
    private FlowPanel addLink(final TemplateRowData row) {
        final LinkButton add = new LinkButton(row.getName());
        add.addStyleName("add-button");
        add.setTitle(row.getTitle());
        add.setOnClick(() -> onSelect.accept(row.getId()));
        final FlowPanel line = new FlowPanel();
        line.setStyleName(row.getSection() != null
                ? "template-panel__add"
                : "template-panel__add template-panel__add--mode");
        line.add(add);
        return line;
    }

    public void setHot(final String id) {
        for (final TemplateRow row : rows) {
            row.setHot(id != null && Objects.equals(row.getData().getId(), id));
        }
    }

    public void setSelected(final String id) {
        selected = id;
        markFolded();
        for (final TemplateRow row : rows) {
            row.setSelected(Objects.equals(row.getData().getId(), id));
        }
        for (final Map.Entry<String, FocusPanel> head : heads.entrySet()) {
            head.getValue().setStyleName("template-panel__mode-row--selected", head.getKey().equals(id));
        }
    }


    // One place where a row is made and wired, for the two call sites that make them
    private TemplateRow newRow(final TemplateRowData row) {
        final TemplateRow widget = new TemplateRow(row);
        widget.setOnSelect(id -> onSelect.accept(id));
        widget.setOnOpen(id -> onOpen.accept(id));
        widget.setOnHover(id -> onHover.accept(id));
        return widget;
    }

    /// @param onSelect Told when select.
    public void setOnSelect(final Consumer<String> onSelect) {
        this.onSelect = onSelect;
    }

    /// @param onOpen Told when open.
    public void setOnOpen(final Consumer<String> onOpen) {
        this.onOpen = onOpen;
    }

    /// @param onHover Told when hover.
    public void setOnHover(final Consumer<String> onHover) {
        this.onHover = onHover;
    }

    interface Binder extends UiBinder<Widget, TemplatePanel> {

    }

    /// A section that folds: its heading, what it holds, and the ids of the rows and headings in
    /// it, which a selection may be one of.
    private static final class Group {

        private final String name;
        private final DisclosureButton heading = new DisclosureButton();
        private final FlowPanel body = new FlowPanel();
        private final Set<String> ids = new HashSet<>();

        private Group(final String name) {
            this.name = name;
        }
    }
}
