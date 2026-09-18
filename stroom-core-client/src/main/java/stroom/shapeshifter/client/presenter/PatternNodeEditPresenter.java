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
import stroom.shapeshifter.client.presenter.PatternNodeEditPresenter.PatternNodeEditView;
import stroom.shapeshifter.config.BinaryCast;
import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.PatternNode;
import stroom.shapeshifter.config.Template.RegexFlags;
import stroom.shapeshifter.shared.ShapeshifterLibrary;
import stroom.widget.popup.client.event.HidePopupRequestEvent;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupType;

import com.google.gwt.user.client.ui.Focus;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.List;

/**
 * One node of a pattern tree (design 38's vocabulary): its kind and the fields that kind has,
 * a label and, on a labelled node, a binary cast. A node being edited keeps what it holds
 * across a change of kind wherever the new kind can hold it — a list's items, a container's
 * body — and is told when it cannot.
 */
public class PatternNodeEditPresenter extends MyPresenterWidget<PatternNodeEditView> {

    /** The node kinds, spelt as the wire format spells them. */
    public enum Kind {
        TAG("tag"),
        TAKE_WHILE("take_while"),
        TAKE_UNTIL("take_until"),
        TAKE_THROUGH("take_through"),
        TAKE("take"),
        ANY("any"),
        REGEX("regex"),
        REF("ref"),
        SEQUENCE("sequence"),
        CHOICE("choice"),
        OPTIONAL("optional"),
        REPEAT("repeat"),
        PEEK("peek"),
        NOT("not");

        private final String spelling;

        Kind(final String spelling) {
            this.spelling = spelling;
        }

        public String spelling() {
            return spelling;
        }

        public boolean isList() {
            return this == SEQUENCE || this == CHOICE;
        }

        public boolean isSingle() {
            return this == OPTIONAL || this == REPEAT || this == PEEK || this == NOT;
        }

        public boolean isContainer() {
            return isList() || isSingle();
        }
    }

    private List<PatternNode> children = List.of();

    @Inject
    public PatternNodeEditPresenter(final EventBus eventBus, final PatternNodeEditView view) {
        super(eventBus, view);
    }

    public void setLibrary(final ShapeshifterLibrary library) {
        final List<String> names = new ArrayList<>();
        if (library != null) {
            for (final ShapeshifterLibrary.Entry entry : library.getEntries()) {
                names.add(entry.getName());
            }
        }
        getView().setLibrary(names);
    }

    /** A node to edit, or null for a new one; {@code body} pre-fills what a new container wraps. */
    public void read(final PatternNode node, final PatternNode body) {
        final PatternNodeEditView v = getView();
        v.setText("");
        v.setCount("1");
        v.setMin("");
        v.setMax("");
        v.setGreedy(true);
        v.setCaseInsensitive(false);
        v.setDotAll(false);
        v.setRef(null);
        v.setLabel("");
        v.setCast(null);
        children = body == null
                ? List.of()
                : List.of(body);
        if (node == null) {
            v.setKind(body == null
                    ? Kind.TAG
                    : Kind.OPTIONAL);
            v.setChildrenNote(body == null
                    ? null
                    : "Wraps " + Templates.describe(body));
            return;
        }
        children = PatternNodes.children(node);
        if (node instanceof PatternNode.Labelled labelled) {
            v.setLabel(labelled.label());
            v.setCast(labelled.as());
        }
        final PatternNode bare = PatternNodes.bare(node);
        if (bare instanceof PatternNode.Tag tag) {
            v.setKind(Kind.TAG);
            v.setText(tag.text());
        } else if (bare instanceof PatternNode.TakeWhile take) {
            v.setKind(Kind.TAKE_WHILE);
            v.setText(take.classExpression());
            v.setMin(String.valueOf(take.min()));
            v.setMax(bound(take.max()));
        } else if (bare instanceof PatternNode.TakeUntil until) {
            v.setKind(until.inclusive()
                    ? Kind.TAKE_THROUGH
                    : Kind.TAKE_UNTIL);
            v.setText(until.terminator());
        } else if (bare instanceof PatternNode.Take take) {
            v.setKind(take.count() == 1
                    ? Kind.ANY
                    : Kind.TAKE);
            v.setCount(String.valueOf(take.count()));
        } else if (bare instanceof PatternNode.Regex regex) {
            v.setKind(Kind.REGEX);
            v.setText(regex.pattern());
            v.setCaseInsensitive(regex.flags().caseInsensitive());
            v.setDotAll(regex.flags().dotAll());
        } else if (bare instanceof PatternNode.Ref ref) {
            v.setKind(Kind.REF);
            v.setRef(ref.name());
        } else if (bare instanceof PatternNode.Sequence) {
            v.setKind(Kind.SEQUENCE);
        } else if (bare instanceof PatternNode.Choice) {
            v.setKind(Kind.CHOICE);
        } else if (bare instanceof PatternNode.Optional) {
            v.setKind(Kind.OPTIONAL);
        } else if (bare instanceof PatternNode.Repeat repeat) {
            v.setKind(Kind.REPEAT);
            v.setMin(String.valueOf(repeat.min()));
            v.setMax(bound(repeat.max()));
            v.setGreedy(repeat.greedy());
        } else if (bare instanceof PatternNode.Peek) {
            v.setKind(Kind.PEEK);
        } else if (bare instanceof PatternNode.Not) {
            v.setKind(Kind.NOT);
        }
        v.setChildrenNote(children.isEmpty()
                ? null
                : "Holds " + children.size() + (children.size() == 1
                        ? " node"
                        : " nodes") + "; a kind without children drops them");
    }

    private static String bound(final int max) {
        return max == PatternNode.Repeat.UNBOUNDED
                ? ""
                : String.valueOf(max);
    }

    /** The edited node, or null after telling the user what is wrong. */
    public PatternNode write() {
        final PatternNodeEditView v = getView();
        try {
            final Kind kind = v.getKind();
            final PatternNode bare;
            switch (kind) {
                case TAG:
                    bare = new PatternNode.Tag(required(v.getText(), "A tag needs its text"));
                    break;
                case TAKE_WHILE:
                    bare = new PatternNode.TakeWhile(required(v.getText(), "take_while needs a character class"),
                            integer(v.getMin(), 1, "min"), unbounded(v.getMax(), "max"));
                    break;
                case TAKE_UNTIL:
                    bare = new PatternNode.TakeUntil(required(v.getText(), "take_until needs a terminator"), false);
                    break;
                case TAKE_THROUGH:
                    bare = new PatternNode.TakeUntil(required(v.getText(), "take_through needs a terminator"), true);
                    break;
                case TAKE:
                    bare = new PatternNode.Take(integer(v.getCount(), 1, "count"));
                    break;
                case ANY:
                    bare = new PatternNode.Take(1);
                    break;
                case REGEX:
                    bare = new PatternNode.Regex(required(v.getText(), "A regex needs a pattern"),
                            new RegexFlags(v.isCaseInsensitive(), v.isDotAll()));
                    break;
                case REF:
                    bare = new PatternNode.Ref(required(v.getRef(), "A ref names a library pattern"));
                    break;
                case SEQUENCE:
                    bare = new PatternNode.Sequence(children);
                    break;
                case CHOICE:
                    bare = new PatternNode.Choice(children);
                    break;
                case OPTIONAL:
                    bare = new PatternNode.Optional(body());
                    break;
                case REPEAT:
                    bare = new PatternNode.Repeat(body(), integer(v.getMin(), 0, "min"), unbounded(v.getMax(), "max"),
                            v.isGreedy());
                    break;
                case PEEK:
                    bare = new PatternNode.Peek(body());
                    break;
                case NOT:
                    bare = new PatternNode.Not(body());
                    break;
                default:
                    throw new ConfigException("Choose a kind");
            }
            final String label = v.getLabel().trim();
            if (label.isEmpty()) {
                return bare;
            }
            return new PatternNode.Labelled(bare, label, v.getCast());
        } catch (final ConfigException e) {
            AlertEvent.fireWarn(this, e.getMessage(), null);
            return null;
        }
    }

    private PatternNode body() {
        if (children.isEmpty()) {
            return PatternNodes.PLACEHOLDER;
        }
        return children.size() == 1
                ? children.get(0)
                : new PatternNode.Sequence(children);
    }

    private static String required(final String text, final String message) {
        if (text == null || text.isEmpty()) {
            throw new ConfigException(message);
        }
        return text;
    }

    private static int integer(final String text, final int fallback, final String what) {
        if (text == null || text.trim().isEmpty()) {
            return fallback;
        }
        try {
            final int value = Integer.parseInt(text.trim());
            if (value < 0) {
                throw new ConfigException(what + " cannot be negative");
            }
            return value;
        } catch (final NumberFormatException e) {
            throw new ConfigException(what + " is a whole number: " + text);
        }
    }

    private static int unbounded(final String text, final String what) {
        return text == null || text.trim().isEmpty()
                ? PatternNode.Repeat.UNBOUNDED
                : integer(text, PatternNode.Repeat.UNBOUNDED, what);
    }

    public void show(final String caption, final HidePopupRequestEvent.Handler handler) {
        ShowPopupEvent.builder(this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(480, 460))
                .caption(caption)
                .onShow(e -> getView().focus())
                .onHideRequest(handler)
                .fire();
    }

    public interface PatternNodeEditView extends View, Focus {

        Kind getKind();

        void setKind(Kind kind);

        String getText();

        void setText(String text);

        String getCount();

        void setCount(String count);

        String getMin();

        void setMin(String min);

        String getMax();

        void setMax(String max);

        boolean isGreedy();

        void setGreedy(boolean greedy);

        boolean isCaseInsensitive();

        void setCaseInsensitive(boolean caseInsensitive);

        boolean isDotAll();

        void setDotAll(boolean dotAll);

        String getRef();

        void setRef(String ref);

        void setLibrary(List<String> names);

        String getLabel();

        void setLabel(String label);

        BinaryCast getCast();

        void setCast(BinaryCast cast);

        /** What the node holds, or null: shown so a change of kind is made knowingly. */
        void setChildrenNote(String note);
    }
}
