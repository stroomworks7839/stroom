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
import stroom.shapeshifter.client.presenter.PartEditPresenter.PartEditView;
import stroom.shapeshifter.config.BinaryCast;
import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.MatchExpression.Length;
import stroom.shapeshifter.config.MatchExpression.MatchPart;
import stroom.shapeshifter.config.PatternNode;
import stroom.widget.popup.client.event.HidePopupRequestEvent;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupType;

import com.google.gwt.user.client.ui.Focus;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

/**
 * One part of a match sequence (design 39): a pattern, a take of a length, a seek by or to a
 * length, or a read of a binary cast — with the label a take or read binds. A length is a
 * count, a label's value, or a variable's. The pattern part is edited as its wire form here;
 * the tree editor edits whole matches, and a part's tree is a smaller thing.
 */
public class PartEditPresenter extends MyPresenterWidget<PartEditView> {

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
    public PartEditPresenter(final EventBus eventBus, final PartEditView view) {
        super(eventBus, view);
    }

    /** A part to edit, or null for a new take. */
    public void read(final MatchPart part) {
        final PartEditView v = getView();
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
    }

    private void length(final Length length) {
        if (length instanceof Length.Literal literal) {
            getView().setLengthKind(LengthKind.COUNT);
            getView().setLength(String.valueOf(literal.count()));
        } else if (length instanceof Length.Label label) {
            getView().setLengthKind(LengthKind.LABEL);
            getView().setLength(label.label());
        } else if (length instanceof Length.Var var) {
            getView().setLengthKind(LengthKind.VAR);
            getView().setLength(var.name());
        }
    }

    /** The edited part, or null after telling the user what is wrong. */
    public MatchPart write() {
        final PartEditView v = getView();
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
        final String text = getView().getLength().trim();
        if (text.isEmpty()) {
            throw new ConfigException("A length needs a count, a label or a variable");
        }
        switch (getView().getLengthKind()) {
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

    public void show(final String caption, final HidePopupRequestEvent.Handler handler) {
        ShowPopupEvent.builder(this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(480, 420))
                .caption(caption)
                .onShow(e -> getView().focus())
                .onHideRequest(handler)
                .fire();
    }

    public interface PartEditView extends View, Focus {

        Kind getKind();

        void setKind(Kind kind);

        String getPattern();

        void setPattern(String json);

        LengthKind getLengthKind();

        void setLengthKind(LengthKind kind);

        String getLength();

        void setLength(String length);

        String getLabel();

        void setLabel(String label);

        boolean isAbsolute();

        void setAbsolute(boolean absolute);

        BinaryCast getCast();

        void setCast(BinaryCast cast);
    }
}
