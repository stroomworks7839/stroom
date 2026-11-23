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

import stroom.widget.button.client.Toolbar;
import stroom.widget.component.client.Component;
import stroom.widget.layout.client.SplitPane;
import stroom.widget.text.client.Label;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.IsWidget;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;

public class PatternWorkbench extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);


    @UiField
    Label kind;
    @UiField
    NameEdit subject;
    @UiField
    Toolbar buttons;
    @UiField
    SimplePanel sample;
    @UiField
    SimplePanel editor;
    @UiField
    SplitPane match;
    @UiField
    CollapsiblePane guardPane;
    @UiField
    SimplePanel guardAndLimits;

    private final FoldingPanes matchPanes;
    // Whether the user has folded or opened the guard and limits while the workbench is open:
    // until then they are open when there is something in them, and after it they stay as the
    // user left them
    private Boolean guardFolded;

    public PatternWorkbench() {
        initWidget(BINDER.createAndBindUi(this));
        matchPanes = new FoldingPanes(match)
                .add(editor, "*")
                .add(guardPane, "210px");
        matchPanes.setOnFold(pane -> guardFolded = pane.isCollapsed());
    }


    /// @return The toolbar its controller adds its actions to.
    public Toolbar getToolbar() {
        return buttons;
    }

    /// @param kindText What the subject is, when that needs saying: `part`, or nothing for a
    ///                 template.
    /// @param name Its name.
    /// @param editable Whether the name is changed here: a part's is.
    public void setSubject(final String kindText, final String name, final boolean editable) {
        kind.setText(kindText);
        kind.setVisible(!kindText.isEmpty());
        subject.setName(name);
        subject.setEditable(editable, "Edit the name", "Pattern part name");
    }

    /// @return The subject's name, which for a part is edited where it reads.
    public NameEdit getSubject() {
        return subject;
    }

    public void setSample(final IsWidget view) {
        sample.setWidget(view.asWidget());
    }

    public void setEditor(final IsWidget view) {
        editor.setWidget(view.asWidget());
    }

    public void setGuardAndLimits(final IsWidget view) {
        guardAndLimits.setWidget(view.asWidget());
    }

    /// @param shown Whether the subject has a guard and limits: a template has, a part has not.
    public void showGuardAndLimits(final boolean shown) {
        match.setPaneHidden(guardPane, !shown);
    }

    /// @param summary What the guard and limits hold, said on their title bar so that it is
    ///                read when they are folded away, e.g. `no guard · max 1`.
    public void describeGuardAndLimits(final String summary) {
        guardPane.setNote(summary);
    }

    /// Forgets whether the user folded or opened the guard and limits, as the workbench closes:
    /// opened again, they are open when there is something in them.
    public void forgetGuardFold() {
        guardFolded = null;
    }

    /// Folds the guard and limits away when they hold nothing and opens them when they hold
    /// something, for a subject just shown — unless the user has folded or opened them, which
    /// is kept.
    ///
    /// @param empty Whether they hold nothing: no guard and no limits.
    public void foldGuardAndLimits(final boolean empty) {
        matchPanes.fold(guardPane, guardFolded == null
                ? empty
                : guardFolded);
    }

    /// Opens the guard and limits, whatever they hold: asked for from the template, they are what
    /// the workbench is open for. Not recorded as the user's fold, which would hold for every
    /// template shown after it; the next template shown is folded by what it holds.
    public void openGuardAndLimits() {
        matchPanes.fold(guardPane, false);
    }

    interface Binder extends UiBinder<Widget, PatternWorkbench> {

    }
}
