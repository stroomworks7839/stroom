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
import stroom.shapeshifter.client.presenter.InstructionEditPresenter.InstructionEditView;
import stroom.shapeshifter.client.view.ClauseListPanel;
import stroom.shapeshifter.config.Condition;
import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.Dispatch;
import stroom.shapeshifter.config.OutputNode;
import stroom.shapeshifter.config.OutputNode.Append;
import stroom.shapeshifter.config.OutputNode.ApplyDirective;
import stroom.shapeshifter.config.OutputNode.ApplyTemplates;
import stroom.shapeshifter.config.OutputNode.Attribute;
import stroom.shapeshifter.config.OutputNode.CallTemplate;
import stroom.shapeshifter.config.OutputNode.Choose;
import stroom.shapeshifter.config.OutputNode.Clear;
import stroom.shapeshifter.config.OutputNode.Element;
import stroom.shapeshifter.config.OutputNode.EmitError;
import stroom.shapeshifter.config.OutputNode.ForEach;
import stroom.shapeshifter.config.OutputNode.ForEachGroup;
import stroom.shapeshifter.config.OutputNode.Holder;
import stroom.shapeshifter.config.OutputNode.If;
import stroom.shapeshifter.config.OutputNode.Insert;
import stroom.shapeshifter.config.OutputNode.Namespace;
import stroom.shapeshifter.config.OutputNode.Param;
import stroom.shapeshifter.config.OutputNode.Put;
import stroom.shapeshifter.config.OutputNode.Remove;
import stroom.shapeshifter.config.OutputNode.Switch;
import stroom.shapeshifter.config.OutputNode.SwitchCase;
import stroom.shapeshifter.config.OutputNode.Text;
import stroom.shapeshifter.config.OutputNode.ValueOf;
import stroom.shapeshifter.config.OutputNode.Variable;
import stroom.shapeshifter.config.RefExpression;
import stroom.shapeshifter.config.Severity;
import stroom.shapeshifter.config.json.JsonText;
import stroom.shapeshifter.config.json.ProjectJson;
import stroom.widget.popup.client.event.HidePopupRequestEvent;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupType;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.List;

/**
 * One instruction (design 18 §5.6: "each kind gets an editor shaped like the instruction it
 * edits"): a kind and the fields that kind has, for the kinds an author writes constantly;
 * the wire form for the transforms and for any card whose reference is a path. A holder keeps
 * what its branches hold across an edit, and across a change of kind where the new kind has
 * a place for it. Conditions use the one clause editor.
 */
public class InstructionEditPresenter extends MyPresenterWidget<InstructionEditView> {

    private static final String WIRE = "(wire form)";

    private List<List<OutputNode>> bodies = List.of();
    private OutputNode original;
    private List<GuardClause> clauses = new ArrayList<>();
    private String conditionJson;

    @Inject
    public InstructionEditPresenter(final EventBus eventBus, final InstructionEditView view) {
        super(eventBus, view);
        view.getCondition().setListener(new ClauseListPanel.Listener() {
            @Override
            public void onClauseChange(final int index, final GuardClause clause) {
                if (index >= 0 && index < clauses.size()) {
                    clauses.set(index, clause);
                }
            }

            @Override
            public void onClauseRemove(final int index) {
                if (index >= 0 && index < clauses.size()) {
                    clauses.remove(index);
                    view.getCondition().setClauses(clauses);
                }
            }

            @Override
            public void onClauseAdd() {
                clauses = view.getCondition().getClauses();
                clauses.add(new GuardClause("", GuardClause.Op.EQ, "", null));
                view.getCondition().setClauses(clauses);
            }

            @Override
            public void onJson(final String text) {
                conditionJson = text;
            }
        });
        view.setOnKind(this::showKind);
    }

    /** A node to edit, or null for a new one of the kind given. */
    public void read(final OutputNode node, final String newKind, final List<String> names,
                     final List<String> modes) {
        original = node;
        bodies = node instanceof Holder holder
                ? holder.bodies()
                : List.of();
        getView().getCondition().setNames(names);
        getView().setModes(modes);
        clear();
        final String kind = node == null
                ? newKind
                : Instructions.kind(node);
        final boolean formable = node == null || FORMABLE.contains(kind) && spellable(node);
        if (!formable) {
            // The wire form, with the kind fixed: a transform, or a card whose reference is a
            // path and has no field spelling.
            showKind(WIRE);
            getView().setKind(kind);
            getView().setWire(JsonText.printPretty(ProjectJson.writeOutput(node)));
            getView().setForced(true);
            getView().setNote(FORMABLE.contains(kind)
                    ? "This " + kind + " holds a reference with no field spelling — a path, an accessor — so it"
                      + " is edited as its wire form."
                    : "A " + kind + " is edited as its wire form: " + Instructions.category(kind).label() + ".");
            return;
        }
        getView().setForced(false);
        getView().setKind(kind);
        showKind(kind);
        if (node != null) {
            fill(node);
        } else if (!FORMABLE.contains(kind)) {
            getView().setWire(skeleton(kind));
        }
    }

    private static final List<String> FORMABLE = Instructions.FORM_KINDS;

    private void clear() {
        final InstructionEditView v = getView();
        for (int i = 0; i < 4; i++) {
            v.setField(i, "", "", false);
        }
        v.setFlag(0, "", false, false);
        v.setFlag(1, "", false, false);
        v.setMulti("", "", false);
        v.setSeverity(Severity.ERROR, false);
        v.setMode(null);
        v.setDispatch(null, false);
        v.setConditionVisible(false);
        v.setWire("");
        v.setNote(null);
        clauses = new ArrayList<>();
        conditionJson = null;
    }

    /**
     * An optional reference: shown where it is there and the form can spell it, and shown blank
     * where the configuration leaves it out. {@code Put.key} is null for a set or a scalar and
     * {@code ForEachGroup.groupBy} is null to group by the entry's own value, so treating an
     * absent one as unshowable sent the ordinary case to the wire form.
     */
    private static boolean optional(final RefExpression ref) {
        return ref == null || Instructions.spell(ref) != null;
    }

    /** Whether every reference the form would show has a field spelling. */
    static boolean spellable(final OutputNode node) {
        if (node instanceof ValueOf v) {
            return Instructions.spell(v.select()) != null;
        } else if (node instanceof EmitError e) {
            return Instructions.spell(e.message()) != null;
        } else if (node instanceof ApplyTemplates a) {
            return Instructions.spell(a.directive().select()) != null
                   && spellableParams(a.directive().withParam());
        } else if (node instanceof CallTemplate c) {
            return spellableParams(c.withParam());
        } else if (node instanceof Switch s) {
            return Instructions.spell(s.select()) != null;
        } else if (node instanceof ForEach f) {
            return Instructions.spell(f.select()) != null && f.sort().isEmpty();
        } else if (node instanceof ForEachGroup g) {
            return Instructions.spell(g.select()) != null && optional(g.groupBy());
        } else if (node instanceof Append a) {
            return Instructions.spell(a.target()) != null && Instructions.spell(a.select()) != null;
        } else if (node instanceof Insert i) {
            return Instructions.spell(i.target()) != null && Instructions.spell(i.position()) != null
                   && Instructions.spell(i.select()) != null;
        } else if (node instanceof Put p) {
            return Instructions.spell(p.target()) != null && optional(p.key())
                   && Instructions.spell(p.select()) != null;
        } else if (node instanceof Remove r) {
            return Instructions.spell(r.target()) != null && Instructions.spell(r.key()) != null;
        } else if (node instanceof Clear c) {
            return Instructions.spell(c.target()) != null;
        }
        return true;
    }

    private static boolean spellableParams(final List<Param> params) {
        for (final Param param : params) {
            if (Instructions.spell(param.value()) == null) {
                return false;
            }
        }
        return true;
    }

    /** The fields a kind has; labels say what each field is. */
    private void showKind(final String kind) {
        final InstructionEditView v = getView();
        for (int i = 0; i < 4; i++) {
            v.setFieldVisible(i, false);
        }
        v.setFlagVisible(0, false);
        v.setFlagVisible(1, false);
        v.setMultiVisible(false);
        v.setSeverityVisible(false);
        v.setModeVisible(false);
        v.setDispatchVisible(false);
        v.setConditionVisible(false);
        v.setWireVisible(false);
        v.setNote(null);
        switch (kind) {
            case "text":
                v.setMultiVisible(true);
                v.setMultiLabel("Text");
                break;
            case "value-of":
                field(0, "Select", "a name, $1 or $label for a capture group, \"quoted "
                                   + "text\" for a literal, or a call such as index(), size(xs) "
                                   + "or get(m, \"k\"); several one after another are joined");
                break;
            case "element":
                field(0, "Name", null);
                field(1, "Namespace", "blank for none");
                flag(0, "Omit if empty");
                v.setNote("What the element holds is the card list beneath it.");
                break;
            case "attribute":
                field(0, "Name", null);
                flag(0, "Omit if empty");
                v.setNote("The attribute's value is the card list beneath it.");
                break;
            case "namespace":
                field(0, "Prefix", "blank for the default namespace");
                field(1, "URI", null);
                break;
            case "emit-error":
                v.setSeverityVisible(true);
                field(0, "Message", "a declared name, or a function");
                break;
            case "apply-templates":
                field(0, "Select", "what is dispatched: a declared name, $1 for a capture "
                                   + "group, or a function; blank is this match's whole content");
                v.setModeVisible(true);
                field(2, "Max depth", "how deep recursion goes before the engine calls it a runaway");
                flag(0, "Ignore errors");
                v.setDispatchVisible(true);
                v.setMultiVisible(true);
                v.setMultiLabel("With params, one per line: name = value");
                break;
            case "call-template":
                field(0, "Template name", null);
                v.setMultiVisible(true);
                v.setMultiLabel("With params, one per line: name = value");
                break;
            case "if":
                v.setConditionVisible(true);
                v.setNote("What runs when the test holds is the card list beneath it.");
                break;
            case "choose":
                v.setNote("A choose is its branches: add, edit and prune them on the card, each with its own"
                          + " condition and card list.");
                break;
            case "switch":
                field(0, "Select", "the value switched on: a declared name, or a function");
                v.setMultiVisible(true);
                v.setMultiLabel("Cases, one value per line; a case's cards stay with its position");
                break;
            case "variable":
                field(0, "Name", "what the body beneath is bound to");
                break;
            case "for-each":
                field(0, "Select", "the collection iterated: a declared name, or a function");
                field(1, "As", "the name each entry is bound to");
                field(2, "As key", "for a map, the name each key is bound to; blank for none");
                break;
            case "for-each-group":
                field(0, "Select", "the collection grouped: a declared name, or a function");
                field(1, "Group by", "a declared name, or a function; blank groups by the "
                                     + "entry's own value");
                break;
            case "append":
                field(0, "Target", "the list or set appended to");
                field(1, "Select", "what is appended");
                break;
            case "insert":
                field(0, "Target", "the list inserted into");
                field(1, "Position", "a declared name, a function, or a number");
                field(2, "Select", "what is inserted");
                break;
            case "put":
                field(0, "Target", "the map put into");
                field(1, "Key", "a declared name, a function, or a literal; blank for a set "
                                + "or a scalar, which take no key");
                field(2, "Select", "what is put");
                break;
            case "remove":
                field(0, "Target", "the collection removed from");
                field(1, "Key", "the key or position removed");
                break;
            case "clear":
                field(0, "Target", "the collection cleared");
                break;
            default:
                v.setWireVisible(true);
                v.setNote(WIRE.equals(kind)
                        ? "Any instruction, as its wire form."
                        : "A " + kind + " is edited as its wire form: " + Instructions.category(kind).label()
                          + ".");
                break;
        }
    }

    private void field(final int index, final String label, final String help) {
        getView().setFieldVisible(index, true);
        getView().setFieldLabel(index, label, help);
    }

    private void flag(final int index, final String label) {
        getView().setFlagVisible(index, true);
        getView().setFlagLabel(index, label);
    }

    private void fill(final OutputNode node) {
        final InstructionEditView v = getView();
        if (node instanceof Text t) {
            v.setMulti(t.value());
        } else if (node instanceof ValueOf vo) {
            v.setField(0, Instructions.ref(vo.select()));
        } else if (node instanceof Element e) {
            v.setField(0, e.name());
            v.setField(1, e.namespace() == null
                    ? ""
                    : e.namespace());
            v.setFlag(0, e.omitIfEmpty());
        } else if (node instanceof Attribute a) {
            v.setField(0, a.name());
            v.setFlag(0, a.omitIfEmpty());
        } else if (node instanceof Namespace n) {
            v.setField(0, n.prefix() == null
                    ? ""
                    : n.prefix());
            v.setField(1, n.uri());
        } else if (node instanceof EmitError e) {
            v.setSeverity(e.severity());
            v.setField(0, Instructions.ref(e.message()));
        } else if (node instanceof ApplyTemplates a) {
            final ApplyDirective d = a.directive();
            v.setField(0, Instructions.ref(d.select()));
            v.setMode(d.mode());
            v.setField(2, String.valueOf(d.maxDepth()));
            v.setFlag(0, d.ignoreErrors());
            v.setDispatch(d.dispatch());
            v.setMulti(paramLines(d.withParam()));
        } else if (node instanceof CallTemplate c) {
            v.setField(0, c.name());
            v.setMulti(paramLines(c.withParam()));
        } else if (node instanceof If i) {
            condition(i.test());
        } else if (node instanceof Switch s) {
            v.setField(0, Instructions.ref(s.select()));
            final StringBuilder sb = new StringBuilder();
            for (final SwitchCase c : s.cases()) {
                sb.append(c.value()).append('\n');
            }
            v.setMulti(sb.toString());
        } else if (node instanceof Variable var) {
            v.setField(0, var.name());
        } else if (node instanceof ForEach f) {
            v.setField(0, Instructions.ref(f.select()));
            v.setField(1, f.as());
            v.setField(2, f.asKey() == null
                    ? ""
                    : f.asKey());
        } else if (node instanceof ForEachGroup g) {
            v.setField(0, Instructions.ref(g.select()));
            v.setField(1, Instructions.ref(g.groupBy()));
        } else if (node instanceof Append ap) {
            v.setField(0, Instructions.ref(ap.target()));
            v.setField(1, Instructions.ref(ap.select()));
        } else if (node instanceof Insert in) {
            v.setField(0, Instructions.ref(in.target()));
            v.setField(1, Instructions.ref(in.position()));
            v.setField(2, Instructions.ref(in.select()));
        } else if (node instanceof Put p) {
            v.setField(0, Instructions.ref(p.target()));
            v.setField(1, Instructions.ref(p.key()));
            v.setField(2, Instructions.ref(p.select()));
        } else if (node instanceof Remove r) {
            v.setField(0, Instructions.ref(r.target()));
            v.setField(1, Instructions.ref(r.key()));
        } else if (node instanceof Clear c) {
            v.setField(0, Instructions.ref(c.target()));
        } else {
            v.setWire(JsonText.printPretty(ProjectJson.writeOutput(node)));
        }
    }

    private void condition(final Condition test) {
        final List<GuardClause> rows = GuardClause.read(test);
        if (rows != null) {
            clauses = new ArrayList<>(rows);
            getView().getCondition().setClauses(clauses);
        } else {
            conditionJson = JsonText.printPretty(ProjectJson.writeCondition(test));
            getView().getCondition().setJson(conditionJson);
        }
    }

    private static String paramLines(final List<Param> params) {
        final StringBuilder sb = new StringBuilder();
        for (final Param param : params) {
            sb.append(param.name()).append(" = ").append(Instructions.ref(param.value())).append('\n');
        }
        return sb.toString();
    }

    /** Something to start from in the wire form: a transform's shape, or a text. */
    private static String skeleton(final String kind) {
        if (WIRE.equals(kind)) {
            return "{\"text\": \"\"}";
        }
        return "{\"" + kind + "\": {\"select\": {\"parts\": [{\"capture\": {\"var_id\": \"name\", \"group\": 0}}]},"
               + " \"name\": \"result\"}}";
    }

    /** The edited instruction, or null after telling the user what is wrong. */
    public OutputNode write() {
        final InstructionEditView v = getView();
        try {
            final String kind = v.getKind();
            if (v.isWireVisible() || WIRE.equals(kind)) {
                return ProjectJson.readOutput(JsonText.parse(v.getWire()));
            }
            switch (kind) {
                case "text":
                    return new Text(v.getMulti());
                case "value-of":
                    return new ValueOf(ref(v.getField(0), "value-of needs a select"));
                case "element":
                    return new Element(required(v.getField(0), "An element needs a name"),
                            blankToNull(v.getField(1)), v.getFlag(0), body(0));
                case "attribute":
                    return new Attribute(required(v.getField(0), "An attribute needs a name"), v.getFlag(0),
                            body(0));
                case "namespace":
                    return new Namespace(blankToNull(v.getField(0)), required(v.getField(1),
                            "A namespace needs a URI"));
                case "emit-error":
                    return new EmitError(v.getSeverity(), ref(v.getField(0), "emit-error needs a message"));
                case "apply-templates":
                    return new ApplyTemplates(new ApplyDirective(
                            refOrNull(v.getField(0)), blankToNull(v.getMode()),
                            params(v.getMulti()), integer(v.getField(2), ApplyDirective.DEFAULT_MAX_DEPTH,
                                    "max depth"), v.getFlag(0), v.getDispatch()));
                case "call-template":
                    return new CallTemplate(required(v.getField(0), "call-template needs a template name"),
                            params(v.getMulti()));
                case "if":
                    return new If(condition(), body(0));
                case "choose":
                    return original instanceof Choose
                            ? original
                            : new Choose(List.of(), List.of());
                case "switch": {
                    final List<SwitchCase> cases = new ArrayList<>();
                    int b = 0;
                    for (final String line : v.getMulti().split("\n")) {
                        if (!line.trim().isEmpty()) {
                            cases.add(new SwitchCase(line.trim(), body(b++)));
                        }
                    }
                    final Switch was = original instanceof Switch s
                            ? s
                            : null;
                    return new Switch(ref(v.getField(0), "switch needs a select"), cases, was == null
                            ? List.of()
                            : was.defaultBody());
                }
                case "variable":
                    return new Variable(required(v.getField(0), "A variable needs a name"), body(0));
                case "for-each":
                    return new ForEach(ref(v.getField(0), "for-each needs a select"),
                            required(v.getField(1), "for-each needs a name to bind each entry to"),
                            blankToNull(v.getField(2)), List.of(), body(0));
                case "for-each-group":
                    // Blank groups by the entry's own value, which is what the model means
                    // by a null group-by.
                    return new ForEachGroup(ref(v.getField(0), "for-each-group needs a select"),
                            refOrNull(v.getField(1)), body(0));
                case "append":
                    return new Append(ref(v.getField(0), "append needs a target"),
                            ref(v.getField(1), "append needs a select"));
                case "insert":
                    return new Insert(ref(v.getField(0), "insert needs a target"),
                            refOrText(v.getField(1), "insert needs a position"),
                            ref(v.getField(2), "insert needs a select"));
                case "put":
                    // Blank is a set or a scalar, which take no key.
                    return new Put(ref(v.getField(0), "put needs a target"),
                            refOrTextOrNull(v.getField(1)),
                            ref(v.getField(2), "put needs a select"));
                case "remove":
                    return new Remove(ref(v.getField(0), "remove needs a target"),
                            refOrText(v.getField(1), "remove needs a key"));
                case "clear":
                    return new Clear(ref(v.getField(0), "clear needs a target"));
                default:
                    return ProjectJson.readOutput(JsonText.parse(v.getWire()));
            }
        } catch (final ConfigException e) {
            AlertEvent.fireWarn(this, e.getMessage(), null);
            return null;
        }
    }

    private Condition condition() {
        if (getView().getCondition().isWireForm()) {
            final String text = getView().getCondition().getJson();
            return ProjectJson.readCondition(JsonText.parse(text));
        }
        final List<GuardClause> filled = new ArrayList<>();
        for (final GuardClause clause : getView().getCondition().getClauses()) {
            if (!clause.isBlank()) {
                filled.add(clause);
            }
        }
        final Condition condition = GuardClause.write(filled);
        if (condition == null) {
            throw new ConfigException("An if needs a test");
        }
        return condition;
    }

    /** The branch body kept from the node being edited, or empty for a new one. */
    private List<OutputNode> body(final int branch) {
        return branch < bodies.size()
                ? bodies.get(branch)
                : List.of();
    }

    private static List<Param> params(final String lines) {
        final List<Param> params = new ArrayList<>();
        for (final String line : lines.split("\n")) {
            if (line.trim().isEmpty()) {
                continue;
            }
            final int eq = line.indexOf('=');
            if (eq < 0) {
                throw new ConfigException("A param is 'name = value': " + line);
            }
            params.add(new Param(line.substring(0, eq).trim(),
                    ref(line.substring(eq + 1), "A param needs a value")));
        }
        return params;
    }

    /** A reference, or null for blank — where the model has its own answer for an omission. */
    private static RefExpression refOrNull(final String text) {
        return text == null || text.trim().isEmpty()
                ? null
                : Instructions.read(text);
    }

    private static RefExpression ref(final String text, final String message) {
        return Instructions.read(required(text, message));
    }

    /** A key or position the configuration may leave out: blank is absent, not an error. */
    private static RefExpression refOrTextOrNull(final String text) {
        return text == null || text.trim().isEmpty()
                ? null
                : refOrText(text, "");
    }

    /** A key or position: a number or quoted text is the literal, else a reference. */
    private static RefExpression refOrText(final String text, final String message) {
        final String t = required(text, message);
        if (t.matches("-?[0-9]+")) {
            return RefExpression.text(t);
        }
        // Quoting is the spelling's to read, not this method's: testing the first and last
        // character called `"a" "b"` one literal of `a" "b`, and it never unescaped anything.
        return Instructions.read(t);
    }

    private static String required(final String text, final String message) {
        if (text == null || text.trim().isEmpty()) {
            throw new ConfigException(message);
        }
        return text.trim();
    }

    private static String blankToNull(final String text) {
        return text == null || text.trim().isEmpty()
                ? null
                : text.trim();
    }

    private static int integer(final String text, final int fallback, final String what) {
        if (text == null || text.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (final NumberFormatException e) {
            throw new ConfigException(what + " is a whole number: " + text);
        }
    }

    public void show(final String caption, final HidePopupRequestEvent.Handler handler) {
        ShowPopupEvent.builder(this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(640, 520))
                .caption(caption)
                .onHideRequest(handler)
                .fire();
    }

    /** The kinds the dialog offers: the formed ones, the transforms, and the wire form. */
    public static List<String> kinds() {
        final List<String> kinds = new ArrayList<>(Instructions.FORM_KINDS);
        kinds.addAll(Instructions.TRANSFORM_KINDS);
        kinds.add(WIRE);
        return kinds;
    }

    public interface InstructionEditView extends View {

        String getKind();

        void setKind(String kind);

        void setOnKind(java.util.function.Consumer<String> onKind);

        void setModes(List<String> modes);

        /** The mode a dispatch goes into: blank is the root. */
        String getMode();

        void setMode(String mode);

        void setModeVisible(boolean visible);

        /** A card whose reference has no field spelling: the wire form, and the kind fixed. */
        void setForced(boolean forced);

        String getField(int index);

        void setField(int index, String value);

        void setField(int index, String label, String value, boolean visible);

        void setFieldLabel(int index, String label, String help);

        void setFieldVisible(int index, boolean visible);

        boolean getFlag(int index);

        void setFlag(int index, boolean value);

        void setFlag(int index, String label, boolean value, boolean visible);

        void setFlagLabel(int index, String label);

        void setFlagVisible(int index, boolean visible);

        String getMulti();

        void setMulti(String value);

        void setMulti(String label, String value, boolean visible);

        void setMultiLabel(String label);

        void setMultiVisible(boolean visible);

        Severity getSeverity();

        void setSeverity(Severity severity);

        void setSeverity(Severity severity, boolean visible);

        void setSeverityVisible(boolean visible);

        Dispatch getDispatch();

        void setDispatch(Dispatch dispatch);

        void setDispatch(Dispatch dispatch, boolean visible);

        void setDispatchVisible(boolean visible);

        ClauseListPanel getCondition();

        void setConditionVisible(boolean visible);

        String getWire();

        void setWire(String json);

        boolean isWireVisible();

        void setWireVisible(boolean visible);

        void setNote(String note);
    }
}
