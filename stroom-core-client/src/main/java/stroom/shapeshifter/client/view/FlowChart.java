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

package stroom.shapeshifter.client.view;

import stroom.shapeshifter.shared.ConfigureRole;
import stroom.shapeshifter.shared.FlowExit;
import stroom.shapeshifter.shared.FlowPath;
import stroom.shapeshifter.shared.FlowPath.Visit;
import stroom.shapeshifter.shared.FlowProblem;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.shapeshifter.shared.StepGuard;
import stroom.shapeshifter.shared.StepOutcome;
import stroom.shapeshifter.shared.SupervisorTurn;
import stroom.shapeshifter.shared.Transition;
import stroom.util.shared.NullSafe;
import stroom.widget.util.client.ElementUtil;

import com.google.gwt.dom.client.Element;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.HTML;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/// The learning flow drawn as a flowchart (design 05 §3; A84, A89, A90): the steps down a vertical
/// spine in their order, each followed by the decision that judges its candidates; the defaults the
/// grammar does not write drawn dashed — a pass down to the next step, a shortfall re-asking the step,
/// spent candidates leaving by the step's exit — and the transitions the flow declares drawn solid in
/// lanes, back edges on the left and forward jumps on the right; Start above, End below, and the exits
/// as the connector symbols P and G. Inline SVG from the flow alone: positions follow the order and are
/// never stored. A problem paints the step it names; a recorded attempt's turns draw its path over the
/// flow, the visits numbered and the edges taken highlighted.
public class FlowChart extends Composite {

    private static final String SVG_NS = "http://www.w3.org/2000/svg";
    private static final int WIDTH = 780;
    private static final int CX = 380;
    private static final int HALF = 110;
    private static final int BOX_H = 46;
    private static final int PITCH = 150;
    private static final int DIA = 28;
    private static final int TERMINAL_HALF = 90;
    private static final int TERMINAL_H = 34;
    private static final int TOP = 24;
    private static final int FIRST = 96;
    private static final int LANE_GAP = 48;
    private static final int LANE_PITCH = 30;
    private static final String DATA_STEP = "data-step";
    private static final String DATA_EDGE = "data-edge";
    private static final String DATA_NODE = "data-node";
    public static final String START = "start";
    public static final String END = "end";

    private final HTML html = new HTML();
    private List<FlowStep> steps = new ArrayList<>();
    private List<FlowProblem> problems = new ArrayList<>();
    private List<SupervisorTurn> turns = new ArrayList<>();
    private Integer selected;
    private String selectedTerminal;
    private Consumer<Integer> onSelect;
    private Consumer<String> onTerminalSelect;
    private BiConsumer<Integer, String> onEdgeSelect;

    public FlowChart() {
        initWidget(html);
        html.addStyleName("flow-chart");
        // One handler for every step: the step clicked is the nearest group above the target that names
        // one, as the pathways tree does.
        html.addClickHandler(event -> {
            final Element target = event.getNativeEvent().getEventTarget().cast();
            if (target == null) {
                return;
            }
            final Element edge = ElementUtil.findParent(target,
                    element -> NullSafe.isNonBlankString(element.getAttribute(DATA_EDGE)), 6);
            if (edge != null && onEdgeSelect != null) {
                onEdgeSelect.accept(Integer.parseInt(edge.getAttribute(DATA_STEP)), edge.getAttribute(DATA_EDGE));
                return;
            }
            final Element node = ElementUtil.findParent(target,
                    element -> NullSafe.isNonBlankString(element.getAttribute(DATA_STEP)), 6);
            if (node != null && onSelect != null) {
                onSelect.accept(Integer.parseInt(node.getAttribute(DATA_STEP)));
                return;
            }
            final Element terminal = ElementUtil.findParent(target,
                    element -> NullSafe.isNonBlankString(element.getAttribute(DATA_NODE)), 6);
            if (terminal != null && onTerminalSelect != null) {
                onTerminalSelect.accept(terminal.getAttribute(DATA_NODE));
            }
        });
    }

    /// Scale the drawing down to the pane's width where the pane is narrower than the drawing, as the
    /// Supervisor's is; by default the drawing keeps its size and the pane scrolls.
    public void fitToPane() {
        html.addStyleName("flow-chart--fit");
    }

    /// Who is told which step was clicked, by its index.
    public void setOnSelect(final Consumer<Integer> onSelect) {
        this.onSelect = onSelect;
    }

    /// Who is told that Start or End was clicked: [#START] or [#END].
    public void setOnTerminalSelect(final Consumer<String> onTerminalSelect) {
        this.onTerminalSelect = onTerminalSelect;
    }

    /// Who is told which declared transition was clicked: the step's index and the outcome's name, or
    /// `spent`.
    public void setOnEdgeSelect(final BiConsumer<Integer, String> onEdgeSelect) {
        this.onEdgeSelect = onEdgeSelect;
    }

    /// Draw the flow, with what is wrong with it painted on the steps it names, and a recorded attempt's
    /// path over it where turns are given.
    public void show(final List<FlowStep> steps,
                     final List<FlowProblem> problems,
                     final List<SupervisorTurn> turns,
                     final Integer selected) {
        this.steps = new ArrayList<>(NullSafe.list(steps));
        this.problems = new ArrayList<>(NullSafe.list(problems));
        this.turns = new ArrayList<>(NullSafe.list(turns));
        this.selected = selected;
        render();
    }

    /// Paint one step as the selected one, the rest as they were.
    public void setSelected(final Integer selected) {
        this.selected = selected;
        this.selectedTerminal = null;
        render();
    }

    /// Paint Start or End as the selected one, and no step.
    public void setSelectedTerminal(final String terminal) {
        this.selected = null;
        this.selectedTerminal = terminal;
        render();
    }

    /// Nothing drawn.
    public void clear() {
        steps = new ArrayList<>();
        problems = new ArrayList<>();
        turns = new ArrayList<>();
        selected = null;
        html.setHTML("");
    }

    private void render() {
        final int count = steps.size();
        final int endY = FIRST + count * PITCH;
        final int height = endY + TERMINAL_H + 40;
        final StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"").append(SVG_NS).append("\" class=\"flow-svg\" width=\"").append(WIDTH)
                .append("\" height=\"").append(height).append("\" viewBox=\"0 0 ").append(WIDTH).append(' ')
                .append(height).append("\">");

        final Map<String, Integer> idIndex = new HashMap<>();
        for (int i = 0; i < count; i++) {
            idIndex.put(steps.get(i).effectiveId(), i);
        }
        final List<Visit> visits = FlowPath.visits(steps, turns);
        final Set<String> pathKeys = new HashSet<>();
        final Set<Integer> visited = new HashSet<>();
        final Map<Integer, List<Integer>> visitNumbers = new HashMap<>();
        for (final Visit visit : visits) {
            visited.add(visit.getStep());
            if (visit.getEdge() != null) {
                pathKeys.add(visit.getEdge());
            }
            visitNumbers.computeIfAbsent(visit.getStep(), key -> new ArrayList<>()).add(visit.getTurn());
        }
        final Map<Integer, List<String>> problemsByStep = new HashMap<>();
        for (final FlowProblem problem : problems) {
            if (problem.getStep() != null) {
                problemsByStep.computeIfAbsent(problem.getStep(), key -> new ArrayList<>()).add(problem.getMessage());
            }
        }

        // Start, and its edge down to the first step.
        terminal(svg, TOP, START, "START  ·  a shape with no rule");
        if (count > 0) {
            edge(svg, "start", false, pathKeys, "M" + CX + ' ' + (TOP + TERMINAL_H) + " V" + FIRST, null, 0, 0,
                    null, CX, FIRST, "down");
        }
        terminal(svg, endY, END, "END  ·  bound and served");

        int rightLane = 0;
        int leftLane = 0;
        for (int i = 0; i < count; i++) {
            final FlowStep step = steps.get(i);
            final int boxX = CX - HALF;
            final int boxY = FIRST + i * PITCH;
            final int boxMidY = boxY + BOX_H / 2;
            final int diaY = boxY + BOX_H + 46;
            final boolean last = i == count - 1;

            // The step.
            final StringBuilder classes = new StringBuilder("flow-node flow-node--step");
            if (selected != null && selected == i) {
                classes.append(" flow-node--selected");
            }
            if (problemsByStep.containsKey(i)) {
                classes.append(" flow-node--bad");
            }
            if (visited.contains(i)) {
                classes.append(" flow-node--onpath");
            }
            svg.append("<g class=\"").append(classes).append("\" ").append(DATA_STEP).append("=\"").append(i)
                    .append("\">");
            if (problemsByStep.containsKey(i)) {
                svg.append("<title>").append(esc(String.join("; ", problemsByStep.get(i)))).append("</title>");
            }
            svg.append("<rect class=\"flow-box\" x=\"").append(boxX).append("\" y=\"").append(boxY)
                    .append("\" width=\"").append(HALF * 2).append("\" height=\"").append(BOX_H)
                    .append("\" rx=\"3\"/>");
            text(svg, boxX + 12, boxY + 19, "start", "flow-title", title(step));
            text(svg, boxX + 12, boxY + 36, "start", "flow-sub", subtitle(step));
            text(svg, boxX + HALF * 2 - 10, boxY + 15, "end", "flow-badge", step.getCandidates() != null
                    ? "×" + step.getCandidates()
                    : "× max");
            svg.append("</g>");

            // Down to the decision.
            edge(svg, FlowPath.edgeKey(i, "judge"), false, pathKeys,
                    "M" + CX + ' ' + (boxY + BOX_H) + " V" + (diaY - DIA), null, 0, 0, null, CX, diaY - DIA, "down");
            svg.append("<g class=\"flow-decision").append(visited.contains(i)
                    ? " flow-decision--onpath"
                    : "").append("\" ").append(DATA_STEP).append("=\"").append(i).append("\">");
            svg.append("<polygon points=\"").append(CX).append(',').append(diaY - DIA).append(' ')
                    .append(CX + DIA + 8).append(',').append(diaY).append(' ').append(CX).append(',')
                    .append(diaY + DIA).append(' ').append(CX - DIA - 8).append(',').append(diaY).append("\"/>");
            text(svg, CX, diaY + 4, "middle", "flow-decision-text", "judged");
            svg.append("</g>");

            final Transition onPassed = step.transitionOn(StepOutcome.PASSED);
            final Transition onSpent = step.transitionOnSpent();
            // A pass falls through to the next step, or to End from the last.
            if (onPassed == null) {
                final int targetY = last
                        ? endY
                        : boxY + PITCH;
                edge(svg, FlowPath.edgeKey(i, FlowPath.PASSED), false, pathKeys,
                        "M" + CX + ' ' + (diaY + DIA) + " V" + targetY, "passed", CX + 8, diaY + DIA + 11,
                        "start", CX, targetY, "down");
            }
            // A shortfall re-asks the step with its next candidate.
            if (step.getCandidates() == null || step.getCandidates() != 1) {
                final int loopX = boxX - 22;
                edge(svg, FlowPath.edgeKey(i, FlowPath.REASK), false, pathKeys,
                        "M" + (CX - DIA - 8) + ' ' + diaY + " H" + loopX + " V" + (boxY + 12) + " H" + boxX,
                        "shortfall: re-ask", loopX - 6, (diaY + boxY + 12) / 2, "end", boxX, boxY + 12, "right");
            }
            // Spent candidates leave by the step's default exit, where the flow names none. Each exit
            // from one decision takes the next place down, so two never overlap.
            int stubs = 0;
            if (onSpent == null) {
                exitStub(svg, FlowPath.edgeKey(i, FlowPath.SPENT), false, pathKeys, diaY, stubs++,
                        defaultExit(step), FlowPath.SPENT);
            }
            // The transitions the flow declares.
            for (final Transition transition : step.getTransitions()) {
                final String name = transition.onSpent()
                        ? FlowPath.SPENT
                        : transition.getOn().getDisplayValue();
                final String key = FlowPath.edgeKey(i, name);
                if (transition.exits()) {
                    final FlowExit named = transition.namedExit();
                    exitStub(svg, key, named != null, pathKeys, diaY, stubs++, named != null
                            ? named
                            : defaultExit(step), name + " → " + (named != null
                            ? named.getDisplayValue()
                            : Transition.ABANDON));
                    continue;
                }
                if (Transition.END.equals(transition.getGoTo())) {
                    final int lane = CX + HALF + LANE_GAP + (rightLane++) * LANE_PITCH;
                    final int targetY = endY + TERMINAL_H / 2;
                    edge(svg, key, true, pathKeys, "M" + (CX + DIA + 8) + ' ' + diaY + " H" + lane + " V" + targetY
                                                   + " H" + (CX + TERMINAL_HALF), name + " → end", lane + 6,
                            (diaY + targetY) / 2, "start", CX + TERMINAL_HALF, targetY, "left");
                    continue;
                }
                final Integer target = idIndex.get(transition.getGoTo());
                if (target == null) {
                    continue;
                }
                final int targetBoxY = FIRST + target * PITCH;
                if (target > i) {
                    final int lane = CX + HALF + LANE_GAP + (rightLane++) * LANE_PITCH;
                    final int targetY = targetBoxY + BOX_H / 2;
                    edge(svg, key, true, pathKeys, "M" + (CX + DIA + 8) + ' ' + diaY + " H" + lane + " V" + targetY
                                                   + " H" + (CX + HALF), name + " → " + transition.getGoTo(),
                            lane + 6, (diaY + targetY) / 2, "start", CX + HALF, targetY, "left");
                } else {
                    final int lane = CX - HALF - LANE_GAP - (leftLane++) * LANE_PITCH;
                    final int targetY = targetBoxY + 34;
                    edge(svg, key, true, pathKeys, "M" + (CX - DIA - 8) + ' ' + diaY + " H" + lane + " V" + targetY
                                                   + " H" + boxX, name + " → " + transition.getGoTo(), lane - 6,
                            (diaY + targetY) / 2, "end", boxX, targetY, "right");
                }
            }
            // The attempt's visits, numbered down the step's left edge.
            final List<Integer> numbers = visitNumbers.get(i);
            if (numbers != null) {
                for (int j = 0; j < numbers.size(); j++) {
                    final int cy = boxY + 10 + j * 18;
                    svg.append("<g class=\"flow-visit\"><circle cx=\"").append(boxX - 10).append("\" cy=\"").append(cy)
                            .append("\" r=\"8\"/>");
                    text(svg, boxX - 10, cy + 3, "middle", "flow-visit-text", String.valueOf(numbers.get(j)));
                    svg.append("</g>");
                }
            }
        }
        svg.append("</svg>");
        html.setHTML(SafeHtmlUtils.fromTrustedString(svg.toString()));
    }

    private static FlowExit defaultExit(final FlowStep step) {
        return step.getRole() == ConfigureRole.PARSER
                ? FlowExit.GIVE_UP
                : FlowExit.PARK;
    }

    private static String title(final FlowStep step) {
        final String kind = step.getKind() == QuestionKind.CONFIGURE && step.getRole() != null
                ? "Configure " + step.getRole().getDisplayValue()
                : step.getKind().getDisplayValue();
        return step.getId() != null
                ? kind + "   (" + step.getId() + ")"
                : kind;
    }

    private static String subtitle(final FlowStep step) {
        if (!step.getChecks().isEmpty()) {
            final List<String> names = new ArrayList<>();
            step.getChecks().forEach(check -> names.add(check.getDisplayValue()));
            return "checks: " + String.join(", ", names);
        }
        if (step.getKind() == QuestionKind.TARGET && step.getKinds() != null) {
            return step.getKinds() + " record kinds";
        }
        if (step.getWhen() != null && step.getWhen() != StepGuard.ALWAYS) {
            return "when " + step.getWhen().getDisplayValue();
        }
        return "judged by its kind’s checks";
    }

    private void terminal(final StringBuilder svg, final int y, final String which, final String label) {
        svg.append("<g class=\"flow-node flow-node--terminal flow-node--").append(which)
                .append(which.equals(selectedTerminal)
                        ? " flow-node--selected"
                        : "").append("\" ").append(DATA_NODE).append("=\"").append(which)
                .append("\"><rect class=\"flow-box\" x=\"")
                .append(CX - TERMINAL_HALF).append("\" y=\"").append(y).append("\" width=\"").append(TERMINAL_HALF * 2)
                .append("\" height=\"").append(TERMINAL_H).append("\" rx=\"17\"/>");
        text(svg, CX, y + 21, "middle", "flow-title", label);
        svg.append("</g>");
    }

    /// An edge: its path, an arrowhead where it arrives, and a label beside it where it has one.
    private static void edge(final StringBuilder svg,
                             final String key,
                             final boolean declared,
                             final Set<String> pathKeys,
                             final String d,
                             final String label,
                             final int labelX,
                             final int labelY,
                             final String anchor,
                             final int arrowX,
                             final int arrowY,
                             final String direction) {
        svg.append("<g class=\"flow-edge ").append(declared
                ? "flow-edge--declared"
                : "flow-edge--implied").append(pathKeys.contains(key)
                ? " flow-edge--onpath"
                : "").append('"');
        edgeData(svg, key, declared);
        svg.append("><path d=\"").append(d).append("\"/>");
        arrow(svg, arrowX, arrowY, direction);
        if (label != null) {
            label(svg, labelX, labelY, anchor, label);
        }
        svg.append("</g>");
    }

    /// A short line from the decision to a connector symbol: P parks, G gives up.
    private static void exitStub(final StringBuilder svg,
                                 final String key,
                                 final boolean declared,
                                 final Set<String> pathKeys,
                                 final int diaY,
                                 final int place,
                                 final FlowExit exit,
                                 final String label) {
        final int x = CX + DIA + 8;
        final int ex = x + 46 + place * 12;
        final int ey = diaY + 26 + place * 22;
        svg.append("<g class=\"flow-edge ").append(declared
                ? "flow-edge--declared"
                : "flow-edge--implied").append(pathKeys.contains(key)
                ? " flow-edge--onpath"
                : "").append('"');
        edgeData(svg, key, declared);
        svg.append("><path d=\"M").append(x).append(' ').append(diaY).append(" L").append(ex - 8)
                .append(' ').append(ey - 4).append("\"/>");
        svg.append("<g class=\"flow-exit").append(exit == FlowExit.GIVE_UP
                ? " flow-exit--give"
                : "").append("\"><circle cx=\"").append(ex).append("\" cy=\"").append(ey).append("\" r=\"9\"/>");
        text(svg, ex, ey + 3, "middle", "flow-exit-text", exit == FlowExit.GIVE_UP
                ? "G"
                : "P");
        svg.append("</g>");
        text(svg, ex + 13, ey + 3, "start", "flow-label", label);
        svg.append("</g>");
    }

    /// A declared edge says which step and which transition it is, so that a click on it can select the
    /// transition in the step's form.
    private static void edgeData(final StringBuilder svg, final String key, final boolean declared) {
        if (!declared) {
            return;
        }
        final int bar = key.indexOf('|');
        svg.append(' ').append(DATA_STEP).append("=\"").append(esc(key.substring(0, bar))).append("\" ")
                .append(DATA_EDGE).append("=\"").append(esc(key.substring(bar + 1))).append('"');
    }

    private static void arrow(final StringBuilder svg, final int x, final int y, final String direction) {
        final String d;
        switch (direction) {
            case "down":
                d = "M" + (x - 4) + ' ' + (y - 6) + " L" + x + ' ' + y + " L" + (x + 4) + ' ' + (y - 6);
                break;
            case "left":
                d = "M" + (x + 6) + ' ' + (y - 4) + " L" + x + ' ' + y + " L" + (x + 6) + ' ' + (y + 4);
                break;
            case "right":
                d = "M" + (x - 6) + ' ' + (y - 4) + " L" + x + ' ' + y + " L" + (x - 6) + ' ' + (y + 4);
                break;
            default:
                d = "M" + (x - 4) + ' ' + (y + 6) + " L" + x + ' ' + y + " L" + (x + 4) + ' ' + (y + 6);
                break;
        }
        svg.append("<path class=\"flow-arrow\" d=\"").append(d).append("\"/>");
    }

    /// A label with a backing so that it reads over whatever edge it sits beside.
    private static void label(final StringBuilder svg,
                              final int x,
                              final int y,
                              final String anchor,
                              final String label) {
        final int width = (int) (label.length() * 5.6) + 8;
        final int rectX = "end".equals(anchor)
                ? x - width + 2
                : "middle".equals(anchor)
                        ? x - width / 2
                        : x - 4;
        svg.append("<rect class=\"flow-label-back\" x=\"").append(rectX).append("\" y=\"").append(y - 8)
                .append("\" width=\"").append(width).append("\" height=\"16\" rx=\"3\"/>");
        text(svg, x, y + 3, anchor, "flow-label", label);
    }

    private static void text(final StringBuilder svg,
                             final int x,
                             final int y,
                             final String anchor,
                             final String cls,
                             final String content) {
        svg.append("<text class=\"").append(cls).append("\" x=\"").append(x).append("\" y=\"").append(y)
                .append("\" text-anchor=\"").append(anchor).append("\">").append(esc(content)).append("</text>");
    }

    private static String esc(final String text) {
        return SafeHtmlUtils.htmlEscape(text == null
                ? ""
                : text);
    }
}
