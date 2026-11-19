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
import stroom.shapeshifter.client.model.ControlEscapes;
import stroom.shapeshifter.client.model.GuardClause;
import stroom.shapeshifter.client.model.Instructions;
import stroom.shapeshifter.client.model.Modes;
import stroom.shapeshifter.client.model.SimpleTransforms;
import stroom.shapeshifter.client.model.SortKey;
import stroom.shapeshifter.client.model.Templates;
import stroom.shapeshifter.config.Codec;
import stroom.shapeshifter.config.Condition;
import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.OutputNode;
import stroom.shapeshifter.config.OutputNode.Append;
import stroom.shapeshifter.config.OutputNode.ApplyDirective;
import stroom.shapeshifter.config.OutputNode.ApplyTemplates;
import stroom.shapeshifter.config.OutputNode.Attribute;
import stroom.shapeshifter.config.OutputNode.Call;
import stroom.shapeshifter.config.OutputNode.CallTemplate;
import stroom.shapeshifter.config.OutputNode.Choose;
import stroom.shapeshifter.config.OutputNode.Clear;
import stroom.shapeshifter.config.OutputNode.Contains;
import stroom.shapeshifter.config.OutputNode.Decode;
import stroom.shapeshifter.config.OutputNode.Element;
import stroom.shapeshifter.config.OutputNode.EmitError;
import stroom.shapeshifter.config.OutputNode.EndsWith;
import stroom.shapeshifter.config.OutputNode.ForEach;
import stroom.shapeshifter.config.OutputNode.ForEachGroup;
import stroom.shapeshifter.config.OutputNode.FormatDate;
import stroom.shapeshifter.config.OutputNode.FormatNumber;
import stroom.shapeshifter.config.OutputNode.Holder;
import stroom.shapeshifter.config.OutputNode.If;
import stroom.shapeshifter.config.OutputNode.Insert;
import stroom.shapeshifter.config.OutputNode.Namespace;
import stroom.shapeshifter.config.OutputNode.Order;
import stroom.shapeshifter.config.OutputNode.Param;
import stroom.shapeshifter.config.OutputNode.ParseDate;
import stroom.shapeshifter.config.OutputNode.Put;
import stroom.shapeshifter.config.OutputNode.Remove;
import stroom.shapeshifter.config.OutputNode.Replace;
import stroom.shapeshifter.config.OutputNode.Sort;
import stroom.shapeshifter.config.OutputNode.StartsWith;
import stroom.shapeshifter.config.OutputNode.StringJoin;
import stroom.shapeshifter.config.OutputNode.Substring;
import stroom.shapeshifter.config.OutputNode.SubstringAfter;
import stroom.shapeshifter.config.OutputNode.SubstringBefore;
import stroom.shapeshifter.config.OutputNode.Switch;
import stroom.shapeshifter.config.OutputNode.SwitchCase;
import stroom.shapeshifter.config.OutputNode.Text;
import stroom.shapeshifter.config.OutputNode.Tokenize;
import stroom.shapeshifter.config.OutputNode.Translate;
import stroom.shapeshifter.config.OutputNode.ValueOf;
import stroom.shapeshifter.config.OutputNode.Variable;
import stroom.shapeshifter.config.RefExpression;
import stroom.shapeshifter.config.Severity;
import stroom.shapeshifter.config.json.JsonText;
import stroom.shapeshifter.config.json.ProjectJson;
import stroom.widget.component.client.Controller;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/// One instruction (design 18 §5.6: "each kind gets an editor shaped like the instruction it
/// edits"), shown in the card list in place of the card (design 47 D) and holding a draft until
/// it is taken: a kind and the fields that kind has, for the kinds an author writes constantly;
/// the wire form for the transforms and for any card whose reference is a path. A holder keeps
/// what its branches hold across an edit, and across a change of kind where the new kind has
/// a place for it. Conditions use the one clause editor.
public class InstructionEditController extends Controller {

    private final InstructionEdit view;

    private static final String WIRE = "(wire form)";

    /// The kind a new card has until one is chosen: what is typed says what it is (design 47
    /// A). Text is a text card, `$1` a value-of card, and a line that mixes them is the cards
    /// in the order it has them — so this is the one kind that can write more than one card,
    /// and the one that is never a card's own kind.
    public static final String AUTO = "auto";

    private List<List<OutputNode>> bodies = List.of();
    private OutputNode original;
    private List<GuardClause> clauses = new ArrayList<>();
    private List<SortKey> sortKeys = new ArrayList<>();
    private String conditionJson;
    // The names the card's template may read as they are now, for the offer to declare one
    private Supplier<List<String>> knownNames = List::of;
    // What a new apply-templates dispatches into before the author says, or null (design 47 I),
    // and whether it has been said: the default is a start, not a mode chosen again whenever the
    // kind is
    private String newMode;
    private boolean modeOffered;
    private Predicate<String> onDeclare = name -> false;

    @Inject
    public InstructionEditController(final EventBus eventBus, final InstructionEdit view) {
        super(eventBus);
        this.view = view;
        initWidget(view);
        view.getCondition().setOnChange((index, clause) -> {
            if (index >= 0 && index < clauses.size()) {
                clauses.set(index, clause);
            }
        });
        view.getCondition().setOnRemove(index -> {
            if (index >= 0 && index < clauses.size()) {
                clauses.remove(index);
                view.getCondition().setClauses(clauses, view.getCondition().getJoin());
            }
        });
        view.getCondition().setOnAdd(() -> {
            clauses = view.getCondition().getClauses();
            clauses.add(new GuardClause("", GuardClause.Op.EQ, "", null));
            view.getCondition().setClauses(clauses, view.getCondition().getJoin());
        });
        view.getCondition().setOnJson(text -> conditionJson = text);
        view.getSort().setOnChange((index, key) -> {
            if (index >= 0 && index < sortKeys.size()) {
                sortKeys.set(index, key);
            }
        });
        view.getSort().setOnRemove(index -> {
            if (index >= 0 && index < sortKeys.size()) {
                sortKeys.remove(index);
                view.getSort().setKeys(sortKeys);
            }
        });
        view.getSort().setOnAdd(() -> {
            sortKeys = view.getSort().getKeys();
            sortKeys.add(new SortKey("", Order.ASCENDING, null));
            view.getSort().setKeys(sortKeys);
        });
        view.setOnKind(this::showKind);
        view.setOnFieldTyped(this::offerToDeclare);
        view.setOnDeclare(this::declare);
    }

    /// A node to edit, or null for a new one of the kind given.
    ///
    /// @param node The node, or null for a new one.
    /// @param newKind A new one's kind.
    /// @param names The names the template may read: declared, its params, the functions.
    /// @param modes Every mode, for the Mode picker.
    /// @param newMode What a new `apply-templates` dispatches into before the author says: the one
    ///                mode nothing dispatches into yet, or null ([Modes#onlyUndispatched]).
    public void read(final OutputNode node, final String newKind, final List<String> names,
                     final List<String> modes, final String newMode) {
        original = node;
        this.newMode = newMode;
        this.modeOffered = false;
        bodies = node instanceof Holder holder
                ? holder.bodies()
                : List.of();
        view.getCondition().setNames(names);
        view.setModes(modes);
        clear();
        // A card that exists has a kind, and can be changed to another; only one that does
        // not exist yet can be left to what is typed.
        final List<String> offered = new ArrayList<>();
        if (node == null) {
            offered.add(AUTO);
        }
        offered.addAll(kinds());
        view.setKinds(offered);
        final String kind = node == null
                ? newKind
                : Instructions.kind(node);
        final boolean formable = node == null || FORMABLE.contains(kind) && spellable(node);
        if (!formable) {
            // The wire form, with the kind fixed: a transform, or a card whose reference is a
            // path and has no field spelling.
            showKind(WIRE);
            view.setKind(kind);
            view.setWire(JsonText.printPretty(ProjectJson.writeOutput(node)));
            view.setForced(true);
            view.setNote(FORMABLE.contains(kind)
                    ? "This " + kind + " holds a reference with no field spelling — a path, an accessor — so it"
                      + " is edited as its wire form."
                    : "A " + kind + " is edited as its wire form: " + Instructions.category(kind).label() + ".");
            return;
        }
        view.setForced(false);
        view.setKind(kind);
        showKind(kind);
        if (node != null) {
            fill(node);
        } else if (!AUTO.equals(kind) && !FORMABLE.contains(kind)) {
            view.setWire(skeleton(kind));
        }
        offerToDeclare();
    }

    private static final List<String> FORMABLE = Instructions.FORM_KINDS;

    /// The text fields the one view has; parse-date uses every one of them.
    private static final int FIELDS = 5;

    private void clear() {
        final InstructionEdit v = view;
        for (int i = 0; i < FIELDS; i++) {
            v.setField(i, "", "", false);
        }
        v.setChoice(List.of(), null, "", false);
        v.setFlag(0, "", false, false);
        v.setFlag(1, "", false, false);
        v.setMulti("", "", false);
        v.setSeverity(Severity.ERROR, false);
        v.setMode(null);
        v.setDispatch(null, false);
        v.setConditionVisible(false);
        v.setSortVisible(false);
        sortKeys = new ArrayList<>();
        v.getSort().setKeys(sortKeys);
        v.setWire("");
        v.setNote(null);
        clauses = new ArrayList<>();
        conditionJson = null;
    }

    /// An optional reference: shown where it is there and the form can spell it, and shown blank
    /// where the configuration leaves it out. `Put.key` is null for a set or a scalar and
    /// `ForEachGroup.groupBy` is null to group by the entry's own value, so treating an
    /// absent one as unshowable sent the ordinary case to the wire form.
    private static boolean optional(final RefExpression ref) {
        return ref == null || Instructions.spell(ref) != null;
    }

    /// Whether the form can say a kind's one word and its name: an empty word would come back
    /// as a missing field, and a name that is present but empty would come back as no name,
    /// turning a binding into a straight write.
    private static boolean sayable(final String word, final String name) {
        return word != null && !word.isEmpty() && !"".equals(name);
    }

    /// Whether a transform reads exactly one thing, and the form can spell it: the shape the
    /// one-field forms take, and a node holding more selects than that would not compile.
    private static boolean oneSelect(final List<RefExpression> select) {
        return select.size() == 1 && Instructions.spell(select.get(0)) != null;
    }

    /// Whether no value holds the `=` the replacement lines are split on.
    private static boolean noSeparator(final List<String> values) {
        for (final String value : values) {
            if (value.indexOf('=') >= 0) {
                return false;
            }
        }
        return true;
    }

    /// Whether every sort key's expression has a field spelling; a for-each need not sort.
    private static boolean spellableKeys(final List<Sort> sort) {
        for (final Sort key : sort) {
            if (Instructions.spell(key.by()) == null) {
                return false;
            }
        }
        return true;
    }

    /// Whether every reference the form would show has a field spelling.
    public static boolean spellable(final OutputNode node) {
        if (node instanceof ValueOf v) {
            return Instructions.spell(v.select()) != null;
        } else if (node instanceof EmitError e) {
            return Instructions.spell(e.message()) != null;
        } else if (node instanceof ApplyTemplates a) {
            return Instructions.spell(a.directive().select()) != null
                   && spellableParams(a.directive().withParam());
        } else if (node instanceof CallTemplate c) {
            return spellableParams(c.withParam());
        } else if (node instanceof StringJoin j) {
            for (final RefExpression select : j.select()) {
                if (Instructions.spell(select) == null) {
                    return false;
                }
            }
            return !j.select().isEmpty() && sayable(j.separator(), j.name());
        } else if (node instanceof Tokenize t) {
            return oneSelect(t.select()) && sayable(t.delimiter(), t.name());
        } else if (node instanceof SubstringBefore b) {
            return oneSelect(b.select()) && sayable(b.marker(), b.name());
        } else if (node instanceof SubstringAfter a) {
            return oneSelect(a.select()) && sayable(a.marker(), a.name());
        } else if (node instanceof StartsWith w) {
            return oneSelect(w.select()) && sayable(w.prefix(), w.name());
        } else if (node instanceof EndsWith w) {
            return oneSelect(w.select()) && sayable(w.suffix(), w.name());
        } else if (node instanceof Contains c) {
            return oneSelect(c.select()) && sayable(c.substring(), c.name());
        } else if (node instanceof FormatNumber f) {
            return oneSelect(f.select()) && sayable(f.picture(), f.name());
        } else if (node instanceof Replace r) {
            return oneSelect(r.select()) && sayable(r.pattern(), r.name());
        } else if (node instanceof Substring sub) {
            return oneSelect(sub.select());
        } else if (node instanceof FormatDate f) {
            return oneSelect(f.select());
        } else if (node instanceof ParseDate d) {
            return oneSelect(d.select())
                   && (d.reference() == null || Instructions.spell(d.reference()) != null);
        } else if (node instanceof Decode d) {
            return oneSelect(d.select());
        } else if (node instanceof Call c) {
            // A function of no arguments is legal — current-user takes none — so an empty list
            // is a form to show, not a reason to refuse one.
            for (final RefExpression select : c.select()) {
                if (Instructions.spell(select) == null) {
                    return false;
                }
            }
            return !"".equals(c.name());
        } else if (SimpleTransforms.isSimple(node)) {
            // Every select has to have a spelling, and there has to be one: the form writes the
            // selects a line at a time and refuses an empty list.
            for (final RefExpression select : SimpleTransforms.selectsOf(node)) {
                if (Instructions.spell(select) == null) {
                    return false;
                }
            }
            // A node holding more selects than its kind takes will not compile, and the form
            // has no way to show that it is wrong; and a name that is present but empty would
            // come back as no name at all, turning a binding into a straight write.
            return SimpleTransforms.arityHolds(node)
                   && !"".equals(SimpleTransforms.nameOf(node));
        } else if (node instanceof Translate t) {
            // One select, spellable, and no replacement carrying the separator the form splits
            // on. The corpus has none — 504 translates and not one '=' in a from or a to — but a
            // configuration that did would be silently cut in half by the form.
            // And only what the form can also write back: a nameless translate writes its
            // result straight out, which the form has no way to say — its Name is required —
            // and an empty from list is a pass-through the form would refuse to save. Both stay
            // on the wire form rather than opening a form whose OK can never succeed.
            return t.select().size() == 1
                   && Instructions.spell(t.select().get(0)) != null
                   && t.name() != null
                   && !"".equals(t.name())
                   && !t.from().isEmpty()
                   // A to list of a different length is meaning-preserving to the engine and
                   // not to the form, which shows one line a pair: saving would rewrite the
                   // stored lists rather than leave them be.
                   && t.to().size() == t.from().size()
                   && noSeparator(t.from())
                   && noSeparator(t.to());
        } else if (node instanceof Switch s) {
            return Instructions.spell(s.select()) != null;
        } else if (node instanceof ForEach f) {
            return Instructions.spell(f.select()) != null && spellableKeys(f.sort());
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

    /// The fields a kind has; labels say what each field is.
    private void showKind(final String kind) {
        final InstructionEdit v = view;
        for (int i = 0; i < FIELDS; i++) {
            // Emptied, not merely hidden: the five fields mean different things to different
            // kinds, and switching the kind box in an open card would otherwise carry a
            // parse-date's pattern into a trim's Name and save it there.
            v.setField(i, "", "", false);
        }
        v.setFieldPlaceholder(0, "");
        v.setMulti("", "", false);
        v.setMultiFirst(false);
        v.setChoiceVisible(false);
        v.setFlagVisible(0, false);
        v.setFlagVisible(1, false);
        v.setMultiVisible(false);
        v.setSeverityVisible(false);
        v.setModeVisible(false);
        v.setDispatchVisible(false);
        v.setConditionVisible(false);
        v.setSortVisible(false);
        v.setWireVisible(false);
        v.setNote(null);
        v.setDeclare(null);
        switch (kind) {
            case AUTO:
                field(0, "Line", "text, or $1 or $label for a capture group, or ${…} for any "
                                 + "reference, such as ${name}. A line that holds both is added as "
                                 + "the instructions it describes, in order; \\n is a line break "
                                 + "and $$ is a dollar, so $${ writes ${. Choose a kind for "
                                 + "anything else");
                v.setFieldPlaceholder(0, "text, or $1 for a capture");
                break;
            case "text":
                v.setMultiVisible(true);
                v.setMultiLabel("Text");
                break;
            case "value-of":
                field(0, "Select", "a name, $1 or $label for a capture group, \"quoted "
                                   + "text\" for a literal, or a call such as index(), size(xs) "
                                   + "or get(m, \"k\"), which may wear or \"-\" for a default or "
                                   + "as number for a cast; [i] or [matchCount()] after a name "
                                   + "says which match; several one after another are joined");
                break;
            case "translate":
                field(0, "Select", "what is translated: a name, $1 for a capture group, or a "
                                   + "function");
                field(1, "Name", "the name the result is bound to");
                v.setMultiVisible(true);
                v.setMultiLabel("Replacements, one per line: from = to. Whole substrings, not "
                                + "characters, applied in order; nothing after the = deletes the "
                                + "substring. Write a tab, newline or return as \\t, \\n, \\r");
                break;
            case "trim":
            case "lower-case":
            case "upper-case":
            case "normalize-space":
            case "add":
            case "divide":
            case "round":
            case "string-length":
            case "number":
            case "subtract":
            case "multiply":
            case "mod":
            case "floor":
            case "ceiling":
            case "abs":
                // One form for fifteen kinds, shaped by how many selects the kind takes
                // (design/17 §5). Saving the wrong number builds a configuration that will not
                // compile, so the form does not offer the wrong number: a kind that reads one
                // thing gets one field, divide gets two, and only add's fold gets a list.
                switch (SimpleTransforms.arity(kind)) {
                    case ONE:
                        field(0, "Select", "a name, $1 for a capture group, or a function");
                        field(1, "Name", NAME_HELP);
                        break;
                    case TWO:
                        field(0, "Left", "the left-hand input");
                        field(1, "Right", "the right-hand input");
                        field(2, "Name", NAME_HELP);
                        break;
                    default:
                        v.setMultiVisible(true);
                        v.setMultiLabel("Inputs, one per line: the operation folds over them "
                                        + "in order");
                        v.setMultiFirst(true);
                        field(0, "Name", NAME_HELP);
                        break;
                }
                break;
            case "string-join":
                // Several values and what goes between them: BodyCompiler compiles this one
                // without single(), because joining one value with a separator says nothing.
                // Emptying TRANSFORM_KINDS took away the wire form that was the only way to
                // author a multi-value join, so the form has to offer it.
                field(0, "Separator", "what goes between the values");
                field(1, "Name", NAME_HELP);
                v.setMultiVisible(true);
                v.setMultiLabel("Values, one per line: joined in order");
                v.setMultiFirst(true);
                break;
            case "tokenize":
            case "substring-before":
            case "substring-after":
            case "starts-with":
            case "ends-with":
            case "contains":
            case "format-number":
                // Select, the one word the kind turns on, and the name: eight kinds, one shape.
                field(0, "Select", "a name, $1 for a capture group, or a function");
                field(1, WORD_OF.get(kind), WORD_HELP.get(kind));
                field(2, "Name", NAME_HELP);
                break;
            case "replace":
                field(0, "Select", "a name, $1 for a capture group, or a function");
                field(1, "Find", "the text to look for, or a regular expression");
                field(2, "Replace with", "what to put in its place; blank deletes it");
                field(3, "Name", NAME_HELP);
                flag(0, "Find is a regular expression");
                break;
            case "substring":
                field(0, "Select", "a name, $1 for a capture group, or a function");
                field(1, "Start", "where it starts, counting from 1 at version 5 and from 0 "
                                  + "before (design/17 §7); blank starts at the beginning");
                field(2, "Length", "how many characters; blank runs to the end");
                field(3, "Name", NAME_HELP);
                break;
            case "format-date":
                field(0, "Select", "a name, $1 for a capture group, or a function");
                field(1, "Pattern", "how the date is written out");
                field(2, "Time zone", "the zone it is written in; blank is UTC");
                field(3, "Name", NAME_HELP);
                break;
            case "parse-date":
                field(0, "Select", "a name, $1 for a capture group, or a function");
                field(1, "Pattern", "how the date is written, or iso");
                field(2, "Time zone", "the zone it is read in; blank is UTC");
                field(3, "Relative to", "a date the year is taken from where the text has none; "
                                        + "blank uses the stream's own time");
                field(4, "Name", NAME_HELP);
                break;
            case "decode":
                // The codec's picker renders between the first field and the second, so the
                // form reads Select, Codec, Name — what is read, what is done, what it is
                // called, which is the order every other form here asks in.
                field(0, "Select", "a name, $1 for a capture group, or a function");
                v.setChoice(CODECS, null, "Codec", true);
                field(1, "Name", NAME_HELP);
                break;
            case "call":
                field(0, "Function", "the function to call");
                field(1, "Name", NAME_HELP);
                v.setMultiVisible(true);
                v.setMultiLabel("Arguments, one per line");
                v.setMultiFirst(true);
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
                if (original == null && !modeOffered) {
                    // A mode just added and dispatched from nowhere is what this is for
                    v.setMode(newMode);
                    modeOffered = true;
                }
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
                if (!(original instanceof If) && clauses.isEmpty()) {
                    // An if with no clause is not an instruction, so its first is there already,
                    // whether the card is new or is another kind made an if. An if read from the
                    // project has its own clauses, and the form fills them
                    clauses = new ArrayList<>(List.of(new GuardClause("", GuardClause.Op.EQ, "", null)));
                    v.getCondition().setClauses(clauses, v.getCondition().getJoin());
                }
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
                v.setSortVisible(true);
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
        view.setFieldVisible(index, true);
        view.setFieldLabel(index, label, help);
    }

    private void flag(final int index, final String label) {
        view.setFlagVisible(index, true);
        view.setFlagLabel(index, label);
    }

    private void fill(final OutputNode node) {
        final InstructionEdit v = view;
        if (node instanceof Text t) {
            v.setMulti(t.value());
        } else if (node instanceof ValueOf vo) {
            v.setField(0, Instructions.ref(vo.select()));
        } else if (SimpleTransforms.isSimple(node)) {
            final List<RefExpression> reads = SimpleTransforms.selectsOf(node);
            final String name = SimpleTransforms.nameOf(node) == null
                    ? ""
                    : SimpleTransforms.nameOf(node);
            switch (SimpleTransforms.arity(Instructions.kind(node))) {
                case ONE:
                    v.setField(0, Instructions.spell(reads.get(0)));
                    v.setField(1, name);
                    break;
                case TWO:
                    v.setField(0, Instructions.spell(reads.get(0)));
                    v.setField(1, Instructions.spell(reads.get(1)));
                    v.setField(2, name);
                    break;
                default:
                    final StringBuilder lines = new StringBuilder();
                    for (final RefExpression select : reads) {
                        if (lines.length() > 0) {
                            lines.append('\n');
                        }
                        lines.append(Instructions.spell(select));
                    }
                    v.setMulti(lines.toString());
                    v.setField(0, name);
                    break;
            }
        } else if (node instanceof StringJoin j) {
            v.setField(0, spellWord(j.separator()));
            v.setField(1, text(j.name()));
            final StringBuilder values = new StringBuilder();
            for (final RefExpression select : j.select()) {
                if (values.length() > 0) {
                    values.append('\n');
                }
                values.append(Instructions.spell(select));
            }
            v.setMulti(values.toString());
        } else if (node instanceof Tokenize t) {
            one(t.select(), t.delimiter(), t.name());
        } else if (node instanceof SubstringBefore b) {
            one(b.select(), b.marker(), b.name());
        } else if (node instanceof SubstringAfter a) {
            one(a.select(), a.marker(), a.name());
        } else if (node instanceof StartsWith w) {
            one(w.select(), w.prefix(), w.name());
        } else if (node instanceof EndsWith w) {
            one(w.select(), w.suffix(), w.name());
        } else if (node instanceof Contains c) {
            one(c.select(), c.substring(), c.name());
        } else if (node instanceof FormatNumber f) {
            one(f.select(), f.picture(), f.name());
        } else if (node instanceof Replace r) {
            v.setField(0, Instructions.spell(r.select().get(0)));
            v.setField(1, spellWord(r.pattern()));
            v.setField(2, spellWord(r.replacement()));
            v.setField(3, text(r.name()));
            v.setFlag(0, r.isRegex());
        } else if (node instanceof Substring sub) {
            v.setField(0, Instructions.spell(sub.select().get(0)));
            v.setField(1, sub.start() == null ? "" : String.valueOf(sub.start()));
            v.setField(2, sub.length() == null ? "" : String.valueOf(sub.length()));
            v.setField(3, text(sub.name()));
        } else if (node instanceof FormatDate f) {
            v.setField(0, Instructions.spell(f.select().get(0)));
            v.setField(1, spellWord(f.pattern()));
            v.setField(2, text(f.timezone()));
            v.setField(3, text(f.name()));
        } else if (node instanceof ParseDate d) {
            v.setField(0, Instructions.spell(d.select().get(0)));
            v.setField(1, spellWord(d.pattern()));
            v.setField(2, text(d.timezone()));
            v.setField(3, d.reference() == null ? "" : Instructions.spell(d.reference()));
            v.setField(4, text(d.name()));
        } else if (node instanceof Decode d) {
            v.setField(0, Instructions.spell(d.select().get(0)));
            v.setChoice(CODECS, d.codec().name().toLowerCase(Locale.ROOT), "Codec", true);
            v.setField(1, text(d.name()));
        } else if (node instanceof Call c) {
            v.setField(0, c.function());
            v.setField(1, text(c.name()));
            final StringBuilder args = new StringBuilder();
            for (final RefExpression select : c.select()) {
                if (args.length() > 0) {
                    args.append('\n');
                }
                args.append(Instructions.spell(select));
            }
            v.setMulti(args.toString());
        } else if (node instanceof Translate t) {
            v.setField(0, t.select().isEmpty()
                    ? ""
                    : Instructions.ref(t.select().get(0)));
            v.setField(1, t.name() == null
                    ? ""
                    : t.name());
            v.setMulti(replacements(t));
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
            sortKeys = new ArrayList<>();
            for (final Sort key : f.sort()) {
                sortKeys.add(SortKey.of(key));
            }
            v.getSort().setKeys(sortKeys);
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
        final GuardClause.Rows read = GuardClause.read(test);
        if (read != null) {
            clauses = new ArrayList<>(read.rows());
            view.getCondition().setClauses(clauses, read.join());
        } else {
            conditionJson = JsonText.printPretty(ProjectJson.writeCondition(test));
            view.getCondition().setJson(conditionJson);
        }
    }

    private static String paramLines(final List<Param> params) {
        final StringBuilder sb = new StringBuilder();
        for (final Param param : params) {
            sb.append(param.name()).append(" = ").append(Instructions.ref(param.value())).append('\n');
        }
        return sb.toString();
    }

    // A variable naming what nothing declares is offered the way to declare it; anything else
    // is offered nothing
    private void offerToDeclare() {
        final String name = view.getField(0).trim();
        view.setDeclare("variable".equals(view.getKind()) && Templates.undeclared(knownNames.get(), name)
                ? name
                : null);
    }

    // The offer taken. Asked again first, since the names may have changed since it was made;
    // and said again after, from what the project now holds, so that an offer the owner could
    // not carry out is still there rather than gone as if it had been
    private void declare() {
        final String name = view.getField(0).trim();
        if (Templates.undeclared(knownNames.get(), name)) {
            onDeclare.test(name);
        }
        offerToDeclare();
        view.focusFirst();
    }

    /// @param onDeclare Asked to declare a name on the card's template, as a scalar; answers
    ///                  whether it did.
    public void setOnDeclare(final Predicate<String> onDeclare) {
        this.onDeclare = onDeclare;
    }

    /// @param knownNames The names the card's template may read as they are now: declared, its
    ///                   params, the functions.
    public void setKnownNames(final Supplier<List<String>> knownNames) {
        this.knownNames = knownNames;
    }

    /// Something to start from in the wire form: a transform's shape, or a text.
    private static String skeleton(final String kind) {
        if (WIRE.equals(kind)) {
            return "{\"text\": \"\"}";
        }
        return "{\"" + kind + "\": {\"select\": {\"parts\": [{\"capture\": {\"var_id\": \"name\", \"group\": 0}}]},"
               + " \"name\": \"result\"}}";
    }

    /// The edited instruction, or null after telling the user what is wrong.
    public OutputNode write() {
        try {
            return build();
        } catch (final ConfigException e) {
            AlertEvent.fireWarn(this, e.getMessage(), null);
            return null;
        }
    }

    /// The edited instruction, or null when it cannot be written as it stands, said to nobody:
    /// for a draft being left behind rather than taken, where there is no one to tell.
    public OutputNode writeQuietly() {
        try {
            return build();
        } catch (final ConfigException e) {
            return null;
        }
    }

    /// @return Whether the card's kind is left to what is typed: [#getLine()] is then what
    ///         it holds, and there is no one instruction to write.
    public boolean isAuto() {
        return AUTO.equals(view.getKind());
    }

    /// @return The line typed, while the kind is left to it.
    public String getLine() {
        return view.getField(0);
    }

    /// The node this was opened on, or null for a new one.
    public OutputNode getOriginal() {
        return original;
    }

    /// @param onCommit Told when the draft is to be taken.
    public void setOnCommit(final Runnable onCommit) {
        view.setOnCommit(onCommit);
    }

    /// @param onCancel Told when the draft is to be dropped.
    public void setOnCancel(final Runnable onCancel) {
        view.setOnCancel(onCancel);
    }

    /// Puts the keyboard in the card: in its first field, or in its kind with the kinds offered.
    ///
    /// @param kind Whether the author asked for the kind.
    public void focus(final boolean kind) {
        if (kind) {
            view.chooseKind();
        } else {
            view.focusFirst();
        }
    }

    // What the fields say, as an instruction; a ConfigException says why they do not make one.
    private OutputNode build() {
        final InstructionEdit v = view;
        final String kind = v.getKind();
        if (v.isWireVisible() || WIRE.equals(kind)) {
            return ProjectJson.readOutput(JsonText.parse(v.getWire()));
        }
        switch (kind) {
            case AUTO:
                // A line is the cards it describes, which is for whoever holds the list to
                // add ([#getLine()]); asked for as one instruction, it is none.
                throw new ConfigException("Type a line, or choose what kind of instruction this is");
            case "text":
                return new Text(v.getMulti());
            case "value-of":
                return new ValueOf(ref(v.getField(0), "value-of needs a select"));
            case "trim":
            case "lower-case":
            case "upper-case":
            case "normalize-space":
            case "add":
            case "divide":
            case "round":
            case "string-length":
            case "number":
            case "subtract":
            case "multiply":
            case "mod":
            case "floor":
            case "ceiling":
            case "abs":
                // Blank is a real answer for the name: a transform without one writes its
                // result straight out, which 42 of the corpus's 55 trims do.
                switch (SimpleTransforms.arity(kind)) {
                    case ONE:
                        return SimpleTransforms.build(kind,
                                List.of(ref(v.getField(0), kind + " needs something to read")),
                                blankToNull(v.getField(1)));
                    case TWO:
                        return SimpleTransforms.build(kind,
                                List.of(ref(v.getField(0), kind + " needs a left-hand input"),
                                        ref(v.getField(1), kind + " needs a right-hand input")),
                                blankToNull(v.getField(2)));
                    default:
                        return SimpleTransforms.build(kind, selects(v.getMulti()),
                                blankToNull(v.getField(0)));
                }
            case "string-join":
                return new StringJoin(selects(v.getMulti()),
                        word(v.getField(0), "A string-join needs a separator"),
                        blankToNull(v.getField(1)));
            case "tokenize":
                return new Tokenize(selectOne(), word(v.getField(1),
                        "A tokenize needs a delimiter"), blankToNull(v.getField(2)));
            case "substring-before":
                return new SubstringBefore(selectOne(), word(v.getField(1),
                        "A substring-before needs a marker"), blankToNull(v.getField(2)));
            case "substring-after":
                return new SubstringAfter(selectOne(), word(v.getField(1),
                        "A substring-after needs a marker"), blankToNull(v.getField(2)));
            case "starts-with":
                return new StartsWith(selectOne(), word(v.getField(1),
                        "A starts-with needs a prefix"), blankToNull(v.getField(2)));
            case "ends-with":
                return new EndsWith(selectOne(), word(v.getField(1),
                        "An ends-with needs a suffix"), blankToNull(v.getField(2)));
            case "contains":
                return new Contains(selectOne(), word(v.getField(1),
                        "A contains needs a substring"), blankToNull(v.getField(2)));
            case "format-number":
                return new FormatNumber(selectOne(), word(v.getField(1),
                        "A format-number needs a picture"), blankToNull(v.getField(2)));
            case "replace":
                return new Replace(selectOne(),
                        word(v.getField(1), "A replace needs something to find"),
                        ControlEscapes.unescape(v.getField(2)), v.getFlag(0), blankToNull(v.getField(3)));
            case "substring":
                return new Substring(selectOne(),
                        integerOrNull(v.getField(1), "start"),
                        integerOrNull(v.getField(2), "length"),
                        blankToNull(v.getField(3)));
            case "format-date":
                return new FormatDate(selectOne(),
                        word(v.getField(1), "A format-date needs a pattern"),
                        blankToNull(v.getField(2)), blankToNull(v.getField(3)));
            case "parse-date":
                return new ParseDate(selectOne(),
                        word(v.getField(1), "A parse-date needs a pattern"),
                        blankToNull(v.getField(2)), refOrNull(v.getField(3)),
                        blankToNull(v.getField(4)));
            case "decode":
                return new Decode(selectOne(), codec(v.getChoice()),
                        blankToNull(v.getField(1)));
            case "call":
                return new Call(required(v.getField(0), "A call needs a function"),
                        selects(v.getMulti()), blankToNull(v.getField(1)));
            case "translate": {
                final List<String> from = new ArrayList<>();
                final List<String> to = new ArrayList<>();
                readReplacements(v.getMulti(), from, to);
                return new Translate(
                        List.of(ref(v.getField(0), "A translate needs a select")),
                        from, to, required(v.getField(1), "A translate needs a name"));
            }
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
                        blankToNull(v.getField(2)), sort(), body(0));
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
    }

    private Condition condition() {
        if (view.getCondition().isWireForm()) {
            final String text = view.getCondition().getJson();
            return ProjectJson.readCondition(JsonText.parse(text));
        }
        final List<GuardClause> filled = new ArrayList<>();
        for (final GuardClause clause : view.getCondition().getClauses()) {
            if (!clause.isBlank()) {
                filled.add(clause);
            }
        }
        final Condition condition = GuardClause.write(filled, view.getCondition().getJoin());
        if (condition == null) {
            throw new ConfigException("An if needs a test");
        }
        return condition;
    }

    /// The branch body kept from the node being edited, or empty for a new one.
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

    /// The one word each of the eight kinds turns on, as the model names it.
    private static final Map<String, String> WORD_OF = Map.of(
            "string-join", "Separator",
            "tokenize", "Delimiter",
            "substring-before", "Marker",
            "substring-after", "Marker",
            "starts-with", "Prefix",
            "ends-with", "Suffix",
            "contains", "Substring",
            "format-number", "Picture");

    /// What that word is for.
    private static final Map<String, String> WORD_HELP = Map.of(
            "string-join", "what goes between the values",
            "tokenize", "what separates one token from the next",
            "substring-before", "the text to stop at",
            "substring-after", "the text to start after",
            "starts-with", "the text it must start with",
            "ends-with", "the text it must end with",
            "contains", "the text it must hold",
            "format-number", "the picture the number is written to, as 0.0##");

    /// The codecs decode offers, spelt as the wire spells them — which is the constant's own
    /// name lowercased (`JsonFields.lowercase`), so the list is taken from the enum rather than
    /// written out and left to drift from it.
    private static final List<String> CODECS = codecs();

    private static List<String> codecs() {
        final List<String> codecs = new ArrayList<>();
        for (final Codec codec : Codec.values()) {
            codecs.add(codec.name().toLowerCase(Locale.ROOT));
        }
        return codecs;
    }

    /// What the Name field says, for every kind that has one.
    private static final String NAME_HELP =
            "what the result is called; blank writes it straight out";

    /// The one select a transform of this shape reads, from the first field.
    private List<RefExpression> selectOne() {
        return List.of(ref(view.getField(0), "a transform needs something to read"));
    }

    /// A whole number, or null for blank — a start or a length left out is "from the beginning"
    /// or "to the end" rather than zero.
    private static Integer integerOrNull(final String text, final String what) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(text.trim());
        } catch (final NumberFormatException e) {
            throw new ConfigException("A " + what + " is a whole number: " + text.trim());
        }
    }

    /// The codec a word names.
    private static Codec codec(final String chosen) {
        if (chosen == null || chosen.trim().isEmpty()) {
            throw new ConfigException("A decode needs a codec");
        }
        return Codec.valueOf(chosen.trim().toUpperCase(Locale.ROOT));
    }

    /// The three fields the eight one-word kinds share: what is read, the word, and the name.
    private void one(final List<RefExpression> select, final String word, final String name) {
        view.setField(0, Instructions.spell(select.get(0)));
        view.setField(1, spellWord(word));
        view.setField(2, text(name));
    }

    /// A model string as a field shows it: blank for absent.
    private static String text(final String value) {
        return value == null ? "" : value;
    }

    /// The selects a transform reads, one a line, as the form shows them.
    public static List<RefExpression> selects(final String text) {
        final List<RefExpression> selects = new ArrayList<>();
        for (final String line : text.split("\r?\n", -1)) {
            if (!line.trim().isEmpty()) {
                selects.add(ref(line, "a transform needs something to read"));
            }
        }
        if (selects.isEmpty()) {
            throw new ConfigException("A transform needs something to read");
        }
        return selects;
    }

    /// The replacements as the form shows them: one `from = to` a line, control characters
    /// spelt as `\t`, `\n` and `\r` because a text area cannot hold them (ControlEscapes, the
    /// delimiter form's own answer to this).
    ///
    /// @param translate The translate.
    /// @return The lines the form shows.
    public static String replacements(final Translate translate) {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < translate.from().size(); i++) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(ControlEscapes.escape(translate.from().get(i)))
                    .append(" = ")
                    .append(i < translate.to().size()
                            ? ControlEscapes.escape(translate.to().get(i))
                            : "");
        }
        return out.toString();
    }

    /// The form's replacements as the model holds them: two parallel lists, as the engine reads
    /// them. A line with nothing after the `=` deletes its substring, which is what an absent
    /// `to` entry means.
    public static List<List<String>> readReplacements(final String text) {
        final List<String> from = new ArrayList<>();
        final List<String> to = new ArrayList<>();
        readReplacements(text, from, to);
        return List.of(from, to);
    }

    private static void readReplacements(final String text, final List<String> from,
                                         final List<String> to) {
        for (final String raw : text.split("\r?\n", -1)) {
            if (raw.trim().isEmpty()) {
                continue;
            }
            final int at = raw.indexOf('=');
            if (at < 0) {
                throw new ConfigException("A replacement is 'from = to': " + raw.trim());
            }
            // Exactly the one space replacements() writes either side of the separator, and no
            // more: a value that is a space, or ends in one, is a replacement like any other —
            // `, ` for `,` is the ordinary case — and trimming would delete it silently.
            from.add(ControlEscapes.unescape(dropOneSpaceBefore(raw.substring(0, at))));
            to.add(ControlEscapes.unescape(dropOneSpaceAfter(raw.substring(at + 1))));
        }
        if (from.isEmpty()) {
            throw new ConfigException("A translate needs at least one replacement");
        }
    }

    /// The text before the separator, less the single space the form writes there.
    private static String dropOneSpaceBefore(final String text) {
        return text.endsWith(" ")
                ? text.substring(0, text.length() - 1)
                : text;
    }

    /// The text after the separator, less the single space the form writes there.
    private static String dropOneSpaceAfter(final String text) {
        return text.startsWith(" ")
                ? text.substring(1)
                : text;
    }

    /// A reference, or null for blank — where the model has its own answer for an omission.
    private static RefExpression refOrNull(final String text) {
        return text == null || text.trim().isEmpty()
                ? null
                : Instructions.readChecked(text);
    }

    private static RefExpression ref(final String text, final String message) {
        return Instructions.readChecked(required(text, message));
    }

    /// The sort rows as the ordering they spell. A row left blank has nothing to sort by.
    private List<Sort> sort() {
        final List<Sort> keys = new ArrayList<>();
        for (final SortKey key : view.getSort().getKeys()) {
            keys.add(new Sort(ref(key.getBy(), "a sort key needs something to sort by"),
                    key.getOrder(), key.getAs()));
        }
        return keys;
    }

    /// A key or position the configuration may leave out: blank is absent, not an error.
    private static RefExpression refOrTextOrNull(final String text) {
        return text == null || text.trim().isEmpty()
                ? null
                : refOrText(text, "");
    }

    /// A key or position: a number or quoted text is the literal, else a reference.
    private static RefExpression refOrText(final String text, final String message) {
        final String t = required(text, message);
        if (t.matches("-?[0-9]+")) {
            return RefExpression.text(t);
        }
        // Quoting is the spelling's to read, not this method's: testing the first and last
        // character called `"a" "b"` one literal of `a" "b`, and it never unescaped anything.
        return Instructions.readChecked(t);
    }

    /// A value the form must keep exactly: a separator, a marker, a pattern. Only an empty
    /// field is missing — a single space is a delimiter like any other, and `required` would
    /// both refuse it and, for a value merely edged with spaces, quietly change it.
    ///
    /// <p>Control characters come back through [ControlEscapes], as a translate's replacements
    /// do: a text box cannot hold a tab or a newline, so the form spells them.
    private static String word(final String text, final String message) {
        if (text == null || text.isEmpty()) {
            throw new ConfigException(message);
        }
        return ControlEscapes.unescape(text);
    }

    /// A value the form shows exactly, control characters spelt.
    private static String spellWord(final String value) {
        return value == null
                ? ""
                : ControlEscapes.escape(value);
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

    /// The kinds a card can be: the formed ones, the transforms, and the wire form.
    public static List<String> kinds() {
        final List<String> kinds = new ArrayList<>(Instructions.FORM_KINDS);
        kinds.addAll(Instructions.TRANSFORM_KINDS);
        kinds.add(WIRE);
        return kinds;
    }

}
