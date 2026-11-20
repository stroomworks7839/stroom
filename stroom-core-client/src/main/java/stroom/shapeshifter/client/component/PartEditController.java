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

import stroom.alert.client.event.AlertEvent;
import stroom.shapeshifter.client.model.PartLabels;
import stroom.shapeshifter.client.model.ProjectText;
import stroom.shapeshifter.config.BinaryCast;
import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.MatchExpression.Length;
import stroom.shapeshifter.config.MatchExpression.MatchPart;
import stroom.shapeshifter.config.PatternNode;
import stroom.widget.component.client.Controller;
import stroom.widget.component.client.ControllerDialog;
import stroom.widget.component.client.HasControllerNode;
import stroom.widget.popup.client.PopupSize;
import stroom.widget.popup.client.PopupType;
import stroom.widget.popup.client.event.HidePopupRequestEvent;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.List;

/// One part of a match sequence (design 39): a pattern, a take of a length, a seek by or to a
/// length, or a read of a binary cast — with the label a take or read binds. A length is a
/// count, a label's value, or a variable's. The pattern part is edited as its wire form here;
/// the tree editor edits whole matches, and a part's tree is a smaller thing.
public class PartEditController extends Controller {

    private final PartEdit view;
    // The labels the parts before this one bind, and those sure to hold an integer: what a
    // length can be taken from, and what is filled in for the author
    private List<String> earlierLabels = List.of();
    private List<String> earlierIntegers = List.of();
    // What a fill replaced and what it filled, so that choosing a count again gives it back
    private String beforeFill;
    private String filledWith;

    public enum Kind {
        PATTERN("pattern"),
        TAKE("take"),
        SEEK("seek"),
        READ("read");

        private final String spelling;

        Kind(final String spelling) {
            this.spelling = spelling;
        }

        public String spelling() {
            return spelling;
        }
    }

    public enum LengthKind {
        COUNT("count"),
        LABEL("label's value"),
        VAR("variable's value");

        private final String spelling;

        LengthKind(final String spelling) {
            this.spelling = spelling;
        }

        public String spelling() {
            return spelling;
        }
    }

    @Inject
    public PartEditController(final EventBus eventBus, final PartEdit view) {
        super(eventBus);
        this.view = view;
        initWidget(view);
        view.setOnLengthKind(this::onLengthKind);
    }

    /// Says what the parts before this one bind, for its length to be taken from: listed under
    /// the length when it is a label's value, and the one integer filled in. Called before
    /// [#read], which says it.
    ///
    /// @param earlierLabels The labels the parts before this one bind ([PartLabels#before]).
    /// @param earlierIntegers Those of them sure to hold an integer ([PartLabels#integersBefore]).
    public void setEarlierLabels(final List<String> earlierLabels, final List<String> earlierIntegers) {
        this.earlierLabels = earlierLabels;
        this.earlierIntegers = earlierIntegers;
    }

    // A length taken from a label: the one integer an earlier part reads is the one, where there
    // is one, and the hint says which labels there are. Taken from anything else again, what the
    // fill replaced is back, if the fill is still what the field holds.
    private void onLengthKind() {
        if (view.getLengthKind() == LengthKind.LABEL) {
            final String held = view.getLength();
            final String next = PartLabels.filled(held, earlierIntegers);
            if (!next.equals(held)) {
                beforeFill = held;
                filledWith = next;
                view.setLength(next);
            }
        } else if (filledWith != null && filledWith.equals(view.getLength())) {
            view.setLength(beforeFill);
            filledWith = null;
        }
        hint();
    }

    private void hint() {
        view.setLengthHint(view.getLengthKind() == LengthKind.LABEL
                ? PartLabels.hint(earlierLabels)
                : null);
    }

    /// A part to edit, or null for a new take.
    public void read(final MatchPart part) {
        final PartEdit v = view;
        v.setPattern("");
        v.setLengthKind(LengthKind.COUNT);
        v.setLength("1");
        v.setLabel("");
        v.setAbsolute(false);
        v.setCast(BinaryCast.UINT8);
        if (part == null) {
            v.setKind(Kind.TAKE);
        } else if (part instanceof MatchPart.Pattern pattern) {
            v.setKind(Kind.PATTERN);
            v.setPattern(ProjectText.printPatternNode(pattern.node()));
        } else if (part instanceof MatchPart.Take take) {
            v.setKind(Kind.TAKE);
            length(take.length());
            v.setLabel(take.label() == null
                    ? ""
                    : take.label());
        } else if (part instanceof MatchPart.Seek seek) {
            v.setKind(Kind.SEEK);
            length(seek.length());
            v.setAbsolute(seek.absolute());
        } else if (part instanceof MatchPart.Read read) {
            v.setKind(Kind.READ);
            v.setCast(read.as());
            v.setLabel(read.label() == null
                    ? ""
                    : read.label());
        }
        hint();
    }

    private void length(final Length length) {
        if (length instanceof Length.Literal literal) {
            view.setLengthKind(LengthKind.COUNT);
            view.setLength(String.valueOf(literal.count()));
        } else if (length instanceof Length.Label label) {
            view.setLengthKind(LengthKind.LABEL);
            view.setLength(label.label());
        } else if (length instanceof Length.Var var) {
            view.setLengthKind(LengthKind.VAR);
            view.setLength(var.name());
        }
    }

    /// The edited part, or null after telling the user what is wrong.
    public MatchPart write() {
        final PartEdit v = view;
        try {
            switch (v.getKind()) {
                case PATTERN:
                    final PatternNode node = ProjectText.parsePatternNode(v.getPattern());
                    return new MatchPart.Pattern(node);
                case TAKE:
                    return new MatchPart.Take(length(), blankToNull(v.getLabel()));
                case SEEK:
                    return new MatchPart.Seek(length(), v.isAbsolute());
                case READ:
                    if (v.getCast() == null) {
                        throw new ConfigException("A read needs a cast: what the bytes at the cursor mean");
                    }
                    return new MatchPart.Read(v.getCast(), blankToNull(v.getLabel()));
                default:
                    throw new ConfigException("Choose a kind");
            }
        } catch (final ConfigException e) {
            AlertEvent.fireWarn(this, e.getMessage(), null);
            return null;
        }
    }

    private Length length() {
        final String text = view.getLength().trim();
        if (text.isEmpty()) {
            throw new ConfigException("A length needs a count, a label or a variable");
        }
        switch (view.getLengthKind()) {
            case LABEL:
                return new Length.Label(text);
            case VAR:
                return new Length.Var(text);
            default:
                try {
                    return new Length.Literal(Integer.parseInt(text));
                } catch (final NumberFormatException e) {
                    throw new ConfigException("A count is a whole number: " + text);
                }
        }
    }

    private static String blankToNull(final String text) {
        return text == null || text.trim().isEmpty()
                ? null
                : text.trim();
    }

    /// @param opener  What shows it, which owns it until it is hidden.
    /// @param caption The dialog's caption.
    /// @param handler Told when OK or Cancel is pressed.
    public void show(final HasControllerNode opener,
                     final String caption,
                     final HidePopupRequestEvent.Handler handler) {
        ControllerDialog.showOnce(opener, this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(480, 420))
                .caption(caption)
                .onShow(e -> view.focus())
                .onHideRequest(handler)
                .fire();
    }

}
