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

import stroom.svg.shared.SvgImage;
import stroom.widget.button.client.LinkButton;

import com.google.gwt.safehtml.shared.SafeHtmlBuilder;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;

/// A button that shows and hides what follows it, its text after the arrow the explorer's tree
/// shows the same with: pointing right at what is hidden, and down over what is shown
/// (`ExplorerCell`, the reference, ruled 2026-11-20). One arrow, then, for every fold in the
/// editor: a pane's title bar ([CollapsiblePane]) and a section of the template panel.
///
/// A screen reader hears the same from `aria-expanded`; the arrow is hidden from it, so the
/// button is named by its text alone. A note, if there is one, follows the text more quietly:
/// what a folded section holds, so it says something while it is folded.
public class DisclosureButton extends LinkButton {

    private static final String STYLE_NAME = "disclosure-button";

    private String text = "";
    private String note;
    private boolean expanded = true;

    public DisclosureButton() {
        addStyleName(STYLE_NAME);
        render();
    }

    /// @param text What the button shows and hides, which is also its name.
    @Override
    public void setText(final String text) {
        this.text = text == null
                ? ""
                : text;
        render();
    }

    @Override
    public String getText() {
        return text;
    }

    /// @param note What to say after the text, more quietly, or null for nothing.
    public void setNote(final String note) {
        this.note = note;
        render();
    }

    /// @param expanded Whether what the button shows and hides is shown.
    public void setExpanded(final boolean expanded) {
        this.expanded = expanded;
        render();
    }

    /// @return Whether what the button shows and hides is shown.
    public boolean isExpanded() {
        return expanded;
    }

    private void render() {
        final SvgImage arrow = expanded
                ? SvgImage.ARROW_DOWN
                : SvgImage.ARROW_RIGHT;
        final SafeHtmlBuilder html = new SafeHtmlBuilder()
                .appendHtmlConstant("<span class=\"" + SvgImage.BASE_CLASS_NAME + " " + arrow.getClassName()
                                    + " disclosure-button__arrow\" aria-hidden=\"true\">")
                .append(SafeHtmlUtils.fromTrustedString(arrow.getSvg()))
                .appendHtmlConstant("</span><span class=\"disclosure-button__text\">")
                .appendEscaped(text)
                .appendHtmlConstant("</span>");
        if (note != null && !note.isEmpty()) {
            // The space keeps the two words apart in the button's name; the flex row ignores it
            html.appendHtmlConstant(" <span class=\"disclosure-button__note\">")
                    .appendEscaped(note)
                    .appendHtmlConstant("</span>");
        }
        setHTML(html.toSafeHtml());
        setAriaExpanded(expanded);
    }
}
