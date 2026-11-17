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

package stroom.gwt.workbench.client.app.shapeshifter;

import stroom.gwt.workbench.framework.client.play.Query;
import stroom.gwt.workbench.framework.client.story.StoryContext;
import stroom.gwt.workbench.framework.client.story.StoryLayout;
import stroom.gwt.workbench.framework.client.story.StoryRegistry;
import stroom.pipeline.shared.SourceLocation;
import stroom.shapeshifter.client.component.ContentPane;
import stroom.shapeshifter.client.component.ContentPaneController;
import stroom.shapeshifter.client.model.Hot;
import stroom.shapeshifter.client.model.ProjectHost;
import stroom.shapeshifter.client.model.SampleSource;
import stroom.shapeshifter.client.model.TraceModel;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.shared.ShapeshifterTrace;
import stroom.shapeshifter.shared.ShapeshifterTrace.Capture;
import stroom.shapeshifter.shared.ShapeshifterTrace.Frame;

import com.google.gwt.user.client.ui.Widget;
import com.google.web.bindery.event.shared.SimpleEventBus;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/// The `App/Shapeshifter/ShapeshifterPane` stories: a `ContentPane` with no editor around it,
/// over a sample bound three generations deep.
///
/// Apart from the rest of the editor's stories because what these show is the pane's marks, and
/// in a screenshot of the whole tab the pane is a corner (design 18 §5.11, §5.12). They need no
/// REST at all — the pane is a controller and a host, and [PaneOnlyHost] is the host — so they
/// share none of [ShapeshifterEditorStories]' fixtures and would only make that file longer.
///
/// The fixture is [#nestedTrace()] and every story reads it at a different cursor, because the
/// two generations the pane marks are counted from the cursor and re-base as it walks: what is
/// drawn at a line is not what is drawn at a pair beneath it.
public final class ShapeshifterPaneStories {

    // A capture of the current level, which carries the rule; one from beneath it carries only
    // the tint and so has no class of its own (design 18 §5.11, ruled 2026-11-16). One class
    // rather than a tier per level, because the marks no longer say how deep anything is.
    private static final String CAPTURE_RULED = ".mark--capture.mark--ruled";

    // Any capture at all, whatever bound it.
    private static final String MARK_CAPTURE = ".mark--capture";

    // Captures of the fixture by the frame that bound them: the line taken whole, a key one
    // frame below it, and the short host name three binding generations down.
    private static final String CAPTURE_LINE = ".mark--capture[data-cap='2:0']";

    private static final String CAPTURE_KEY = ".mark--capture[data-cap='4:0']";

    // Named for the cursor it was written against: three binding generations below the
    // document, so past the cap from there — and one generation below a pair, so drawn when the
    // cursor is on one. The cap re-bases as the cursor walks, which is the point of it.
    private static final String CAPTURE_PAST_CAP = ".mark--capture[data-cap='8:0']";

    // The value of the first pair, ruled alongside its key when the cursor is on that pair:
    // the second rule a story needs to show one fading while the other is pointed at.
    private static final String CAPTURE_VALUE = ".mark--capture[data-cap='4:1']";

    // The content pane's own block of marks, which carries `pointing` while one of them is
    // lit. Scoped to the pane: `.content-box` is an app-wide class, and within Shapeshifter the
    // pattern workbench's matches pane carries it as well.
    private static final String CONTENT_BLOCK = ".content-pane .content-box";

    // The templates of the fixture. Kept apart from the strict_kv ids in
    // ShapeshifterEditorStories so that a selector written for one fixture cannot quietly match
    // the other.
    private static final String SOURCE_ID = "00000000-0000-0000-0000-000000000021";

    private static final String LINE_ID = "00000000-0000-0000-0000-000000000022";

    private static final String PAIR_ID = "00000000-0000-0000-0000-000000000023";

    private static final String HOST_ID = "00000000-0000-0000-0000-000000000024";

    private ShapeshifterPaneStories() {
        // Static utility
    }

    /// Adds the stories to the registry.
    ///
    /// @param registry The registry to add to.
    public static void addTo(final StoryRegistry registry) {
        registry.component("App/Shapeshifter/ShapeshifterPane", ShapeshifterPaneStories.class)
                .layout(StoryLayout.FULLSCREEN)
                // The pane on its own, over a sample bound three generations deep: a line taken
                // whole by the template that matched it, the key and value taken inside it by the
                // pairs beneath, and a short host name taken inside one of those. These are the
                // only stories with no editor around them — a ContentPane and a host of its own —
                // because what the pane does is meant to be looked at, and in a screenshot of the
                // whole tab it is a corner (design 18 §5.11, ruled 2026-11-16).
                //
                // At the document, which binds nothing: the tint and only the tint.
                .story("InputPaneTintAlone", ShapeshifterPaneStories::renderPaneOnly)
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(play.querySelector(MARK_CAPTURE)).not().toBeNull());
                    // The tint identifies captures and says nothing about depth, so a capture
                    // from one generation down and one from two look alike: same opacity, same
                    // kind of mark, no rule on either. That is the assertion three steps of
                    // opacity would have failed — opacity takes the glyphs along with the mark,
                    // and on the light theme the first step alone drops the weakest of the eight
                    // generated hues from 5.05:1 to 3.32:1, under AA, on text whose lightness the
                    // theme sets for no other purpose than to clear it.
                    play.expect(play.querySelector(CAPTURE_LINE).computedStyle("opacity")).toEqual("1");
                    play.expect(play.querySelector(CAPTURE_KEY).computedStyle("opacity")).toEqual("1");
                    play.expect(play.querySelector(CAPTURE_RULED)).toBeNull();
                    // Drawn, not absent: every mark carries a transparent rule at rest so that
                    // pointing animates its colour rather than its existence (§5.12.3). "No
                    // rule here" is therefore a colour, not a style.
                    play.expect(play.querySelector(CAPTURE_LINE).computedStyle("border-bottom-color"))
                            .toEqual("rgba(0, 0, 0, 0)");
                    // Two captures bound by different frames are different colours. They were the
                    // same colour until 2026-11-16, because the hue was the capture's index
                    // *within its own frame* and both of these are index 0 — survivable while one
                    // frame's captures were tinted, not survivable now the tint reaches two
                    // generations and the variables pane lists every frame at once.
                    // Read inside the predicate, because a query only resolves while a step runs.
                    play.expect(play.querySelector(CAPTURE_LINE).computedStyle("--hue"))
                            .toSatisfy("a colour of its own, not the key capture's",
                                    hue -> !hue.equals(
                                            play.querySelector(CAPTURE_KEY).computedStyle("--hue").get()));
                    // Three binding generations down is past the cap, so it is drawn nothing at
                    // all — not a fainter tint, nothing. Colouring an IP address and then
                    // recolouring each of its octets costs the first colouring its meaning, and
                    // the cap is what stops that; this capture becoming invisible until the reader
                    // descends to it is the accepted price (design 18 §5.11.1).
                    play.expect(play.querySelector(CAPTURE_PAST_CAP)).toBeNull();
                })

                // With the cursor on a line, the second channel appears: that line's own captures
                // are ruled, and the captures beneath it keep the tint and lose the rule. The two
                // channels disagreeing over a run of text *is* the information — tinted but not
                // ruled means "a capture, but not one of yours" (design 18 §5.11.2).
                .story("InputPaneRuleAlone", context -> renderPaneOnly(context, 2))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(play.querySelector(CAPTURE_RULED)).not().toBeNull());
                    final Query line = play.querySelector(CAPTURE_LINE);
                    play.expect(line).toHaveClass("mark--ruled");
                    play.expect(line.computedStyle("border-bottom-style")).toEqual("solid");
                    // The width every mark carries, ruled or not: nothing in the pane changes a
                    // rule's width, so the one property that animates is its colour.
                    play.expect(line.computedStyle("border-bottom-width")).toEqual("2px");
                    // Drawn in the capture's own hue rather than left transparent: where a capture
                    // is made only of whitespace the rule is the entire mark, so it has to clear
                    // WCAG 1.4.11's 3:1 on its own. At 50% of the hue it did not — 2.05:1 on the
                    // weakest generated hue against the light theme's control surface — which is
                    // why it is drawn at 75%.
                    play.expect(line.computedStyle("border-bottom-color"))
                            .not().toEqual("rgba(0, 0, 0, 0)");
                    // Square, not rounded: the 2px corner radius the mark carried was shaping a
                    // fill and then an outline, and with neither left it only tapered the ends
                    // of the rule — a mark trailing off rather than stopping where its bytes do.
                    play.expect(line.computedStyle("border-bottom-left-radius")).toEqual("0px");
                    // A capture from the pair beneath: tinted, unruled, and not faded for it
                    final Query key = play.querySelector(CAPTURE_KEY);
                    play.expect(key).not().toHaveClass("mark--ruled");
                    play.expect(key.computedStyle("border-bottom-color")).toEqual("rgba(0, 0, 0, 0)");
                    play.expect(key.computedStyle("opacity")).toEqual("1");
                    // Nothing is pointed at, so no rule is faded for anything.
                    play.expect(play.querySelector(CONTENT_BLOCK)).not().toHaveClass("pointing");
                })

                // Pointing at a ruled capture thickens its rule. The rule carries the hover
                // because the rule is the channel the pointer is asking about; the variables
                // pane's row lights at the same time, which is the `highlight` vocabulary and has
                // no pane to light here.
                //
                // A story of its own rather than the tail of the one above, because the
                // screenshot is taken once the play has ended and a hover is still in force then:
                // asserting rest and then hover in one story leaves no picture of the pane at
                // rest, which is the state it spends its life in.
                .story("InputPaneRulePointedAt", context -> renderPaneOnly(context, 2))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(play.querySelector(CAPTURE_RULED)).not().toBeNull());
                    final Query line = play.querySelector(CAPTURE_LINE);
                    // Drawn at 75% of its hue at rest, so that a whitespace-only capture's rule
                    // clears WCAG 1.4.11 on its own; pointing at it brings it to full strength.
                    // Kept rather than compared to a literal, because what the browser reports a
                    // colour-mix as is the browser's business, not this story's.
                    final String[] atRest = new String[1];
                    play.expect(line.computedStyle("border-bottom-color"))
                            .toSatisfy("drawn, at less than full strength", colour -> {
                                atRest[0] = String.valueOf(colour);
                                return !"rgba(0, 0, 0, 0)".equals(colour);
                            });
                    play.hover(line);
                    play.waitFor(() -> play.expect(line.computedStyle("border-bottom-color"))
                            .toSatisfy("stronger than it was at rest",
                                    colour -> !colour.equals(atRest[0])
                                              && !"rgba(0, 0, 0, 0)".equals(colour)));
                    play.expect(line.computedStyle("border-bottom-width")).toEqual("2px");
                    // Said by the block as well as by the mark, because the other rules fade
                    // and CSS cannot ask "is some other span hot" from a span (§5.12.3).
                    play.expect(play.querySelector(CONTENT_BLOCK)).toHaveClass("pointing");
                })

                // One rule on screen at a time: with the cursor on a pair, its key and its value
                // are both ruled, and pointing at the key fades the value's rule out. This is
                // the whole hover vocabulary now — the outline every mark drew on hover is gone,
                // because it reached 3px beyond the inline box, broke into a closed box per line
                // wherever a mark wrapped, and was drawn by CSS `:hover` on every ancestor of
                // the pointed-at element as well (design 18 §5.12.3, ruled 2026-11-17).
                .story("InputPaneRuleFaded", context -> renderPaneOnly(context, 4))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(play.querySelector(CAPTURE_VALUE)).not().toBeNull());
                    final Query key = play.querySelector(CAPTURE_KEY);
                    final Query value = play.querySelector(CAPTURE_VALUE);
                    play.expect(value).toHaveClass("mark--ruled");
                    play.expect(value.computedStyle("border-bottom-color"))
                            .not().toEqual("rgba(0, 0, 0, 0)");
                    // Both are ruled at rest, so "the key has a colour" proves nothing here:
                    // what pointing does is bring its rule to full strength and take the other's
                    // away.
                    final String[] keyAtRest = new String[1];
                    play.expect(key.computedStyle("border-bottom-color"))
                            .toSatisfy("ruled at rest", colour -> {
                                keyAtRest[0] = String.valueOf(colour);
                                return !"rgba(0, 0, 0, 0)".equals(colour);
                            });
                    play.hover(key);
                    play.waitFor(() -> play.expect(key.computedStyle("border-bottom-color"))
                            .toSatisfy("stronger than it was at rest",
                                    colour -> !colour.equals(keyAtRest[0])
                                              && !"rgba(0, 0, 0, 0)".equals(colour)));
                    // Still the current level's — the rule's meaning is untouched, it is simply
                    // not being drawn while another one is being asked about.
                    play.expect(value).toHaveClass("mark--ruled");
                    // Waited for, not read straight off: the fade is a .15s transition, so the
                    // line after the hover catches it part-way down.
                    play.waitFor(() -> play.expect(value.computedStyle("border-bottom-color"))
                            .toEqual("rgba(0, 0, 0, 0)"));
                })

                // ...and a capture from below the cursor, which has no rule at rest, is given
                // one while it is pointed at (ruled 2026-11-17). "The rule means this one is
                // yours" has to hold at rest; while the pointer is on something, the hot rule is
                // the only rule on screen and plainly means "this one". The alternative was a
                // hover that draws nothing on the thing under the pointer, which reads as not
                // having registered.
                .story("InputPaneDeepCapturePointedAt", context -> renderPaneOnly(context, 4))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(play.querySelector(CAPTURE_PAST_CAP)).not().toBeNull());
                    final Query deep = play.querySelector(CAPTURE_PAST_CAP);
                    play.expect(deep).not().toHaveClass("mark--ruled");
                    play.expect(deep.computedStyle("border-bottom-color")).toEqual("rgba(0, 0, 0, 0)");
                    play.hover(deep);
                    play.waitFor(() -> play.expect(deep.computedStyle("border-bottom-color"))
                            .not().toEqual("rgba(0, 0, 0, 0)"));
                    play.expect(deep.computedStyle("border-bottom-width")).toEqual("2px");
                    // and the current level's two rules are the ones that have gone quiet,
                    // once the .15s fade has run
                    play.waitFor(() -> play.expect(
                                    play.querySelector(CAPTURE_KEY).computedStyle("border-bottom-color"))
                            .toEqual("rgba(0, 0, 0, 0)"));
                    play.waitFor(() -> play.expect(
                                    play.querySelector(CAPTURE_VALUE).computedStyle("border-bottom-color"))
                            .toEqual("rgba(0, 0, 0, 0)"));
                });
    }

    /// The content pane by itself, over [#nestedTrace()]: no editor, no tabs, no panel — the one
    /// thing the pane's marks are for is being looked at, and everything else in the tab is in
    /// the way of seeing them.
    ///
    /// The pane is a controller with a host, so the story brings a host of its own rather than
    /// the document's. It is small because the pane asks little of it: a trace, a cursor, a
    /// sample source so the pane knows there is data, and a colour per template.
    ///
    /// @param context The story's context, which disposes the controller.
    /// @return The pane.
    private static Widget renderPaneOnly(final StoryContext context) {
        return renderPaneOnly(context, TraceModel.ROOT);
    }

    /// The pane by itself with the cursor on a chosen frame, since which frame is selected is
    /// what decides whether anything is ruled at all (design 18 §5.11.2).
    private static Widget renderPaneOnly(final StoryContext context, final long cursor) {
        final ContentPane pane = new ContentPane();
        final ContentPaneController controller =
                new ContentPaneController(new SimpleEventBus()).attach(pane);
        context.addCleanUp(controller::dispose);
        final PaneOnlyHost host = new PaneOnlyHost(new TraceModel(nestedTrace()), controller, cursor);
        controller.setHost(host);
        return controller.asWidget();
    }

    // Two lines, each taken whole by the template that matched it, each holding two pairs that
    // take a key and a value out of it, and one of those values holding a short host name. Three
    // binding generations is the point: with the cursor anywhere, two of them are tinted and the
    // third is drawn nothing, and with the cursor on a line exactly one of them is ruled. Built
    // in Java rather than as JSON because every offset here has to agree with every other, and a
    // constructor says which number is which where a wall of JSON does not.
    private static ShapeshifterTrace nestedTrace() {
        final String input = "host=web01 port=8080\nlevel=warn id=42\n";
        final List<Frame> frames = Arrays.asList(
                frame(1, 0, SOURCE_ID, "source", 0, 0, 0, input.length()),
                // "host=web01 port=8080\n" and "level=warn id=42\n"
                frame(2, 1, LINE_ID, "line", 0, 1, 0, 21),
                frame(3, 1, LINE_ID, "line", 1, 1, 21, 17),
                // the pairs within the first line, then within the second
                frame(4, 2, PAIR_ID, "pair", 0, 2, 0, 10),
                frame(5, 2, PAIR_ID, "pair", 1, 2, 11, 9),
                frame(6, 3, PAIR_ID, "pair", 0, 2, 0, 10),
                frame(7, 3, PAIR_ID, "pair", 1, 2, 11, 5),
                // and a template beneath the first pair that reads the host's short name out
                // of its value, so the pane has a capture three deep to leave unruled
                frame(8, 4, HOST_ID, "hostname", 0, 3, 5, 5));
        final List<Capture> captures = Arrays.asList(
                // Each line, taken whole: the first binding generation, since neither the
                // document nor the source frame above them binds anything
                capture(2, "line", "host=web01 port=8080", 0, 20),
                capture(3, "line", "level=warn id=42", 0, 16),
                // and the key and value inside each pair, one frame below
                capture(4, "key", "host", 0, 4),
                capture(4, "value", "web01", 5, 5),
                capture(5, "key", "port", 0, 4),
                capture(5, "value", "8080", 5, 4),
                capture(6, "key", "level", 0, 5),
                capture(6, "value", "warn", 6, 4),
                capture(7, "key", "id", 0, 2),
                capture(7, "value", "42", 3, 2),
                // inside the value capture of pair #0, which is itself inside the line capture:
                // the third binding generation, and so past the cap from any cursor above it
                capture(8, "short", "web", 0, 3));
        return new ShapeshifterTrace(true, true, input, "", frames, captures,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), 7L,
                Collections.emptyList(), Collections.emptyList(), 412_000L);
    }

    private static Frame frame(final long id, final long parentId, final String templateId,
                               final String templateName, final int matchIndex, final int depth,
                               final int contentOffset, final int contentLength) {
        // The content is left null so that TraceModel cuts it out of the parent's, which is the
        // only way these offsets can be wrong in the fixture and right on the screen.
        return new Frame(id, parentId, templateId, templateName, matchIndex, depth,
                contentOffset, contentLength, contentOffset, contentLength, null);
    }

    private static Capture capture(final long frameId, final String name, final String value,
                                   final int contentOffset, final int contentLength) {
        return new Capture(frameId, name, value, "scalar", contentOffset, contentLength);
    }

    /// What the content pane needs of a host, and nothing else (design 18 §5.1). The editor's own
    /// host carries a project, a document, modes and a run; a pane shown by itself has none of
    /// those and would gain nothing from a mock of them.
    ///
    /// The one method with a body worth reading is [#hover(Hot)]. In the editor, pointing at a
    /// mark asks the host to light what the request names, and the host tells every surface;
    /// here the only surface is the pane, so the request goes straight back to it. That is what
    /// makes the hover reachable in a story at all — a real pointer's hover has lapsed by the
    /// time a screenshot is taken.
    private static final class PaneOnlyHost implements ProjectHost {

        private final TraceModel trace;
        private final ContentPaneController pane;
        private long cursor;

        private PaneOnlyHost(final TraceModel trace, final ContentPaneController pane, final long cursor) {
            this.trace = trace;
            this.pane = pane;
            this.cursor = cursor;
        }

        @Override
        public Project getProject() {
            // The pane never asks: it draws a trace, and takes its colours from colour(String).
            return null;
        }

        @Override
        public void replace(final Project project) {
            // Nothing here edits.
        }

        @Override
        public String colour(final String templateId) {
            // The palette by position, as the editor's host gives it, written out because
            // Colours.hue is not this package's to call.
            if (LINE_ID.equals(templateId)) {
                return "hsl(47, 62%, var(--mark__lightness))";
            }
            if (PAIR_ID.equals(templateId)) {
                return "hsl(94, 62%, var(--mark__lightness))";
            }
            return "hsl(0, 62%, var(--mark__lightness))";
        }

        @Override
        public void setColour(final String templateId, final String colour) {
            // Nothing here edits.
        }

        @Override
        public List<String> modes() {
            return Collections.emptyList();
        }

        @Override
        public void declareMode(final String mode) {
            // Nothing here edits.
        }

        @Override
        public void forgetMode(final String mode) {
            // Nothing here edits.
        }

        @Override
        public String getSample() {
            return trace.content(TraceModel.ROOT);
        }

        @Override
        public String getKeptSampleText() {
            return trace.content(TraceModel.ROOT);
        }

        @Override
        public SourceLocation getKeptSampleLocation() {
            return null;
        }

        @Override
        public SampleSource getSampleSource() {
            // Not null, or the pane shows its empty state instead of the trace.
            return SampleSource.pasted(trace.content(TraceModel.ROOT));
        }

        @Override
        public void setSampleSource(final SampleSource source) {
            // The sample is the fixture's.
        }

        @Override
        public void run() {
            // The trace is the fixture's; there is nothing to run.
        }

        @Override
        public TraceModel trace() {
            return trace;
        }

        @Override
        public boolean isStale() {
            return false;
        }

        @Override
        public long cursor() {
            return cursor;
        }

        @Override
        public void setCursor(final long frameId) {
            cursor = frameId;
            pane.refresh();
        }

        @Override
        public boolean canGoBack() {
            return false;
        }

        @Override
        public boolean canGoForward() {
            return false;
        }

        @Override
        public void goBack() {
            // There is nowhere to go back to.
        }

        @Override
        public void goForward() {
            // There is nowhere to go forward to.
        }

        @Override
        public void hover(final Hot hot) {
            pane.setHot(hot);
        }
    }
}
