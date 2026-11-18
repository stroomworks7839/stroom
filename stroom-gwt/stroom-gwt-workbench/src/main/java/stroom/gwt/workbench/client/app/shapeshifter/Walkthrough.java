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

import stroom.docref.DocRef;
import stroom.gwt.workbench.client.app.rest.JsonValues;
import stroom.gwt.workbench.client.app.rest.RecordedRequest;
import stroom.gwt.workbench.client.app.rest.RestFixtures;
import stroom.gwt.workbench.client.app.rest.RestReply;
import stroom.gwt.workbench.client.app.screen.StroomDom;
import stroom.gwt.workbench.client.widgets.tree.ExplorerFixture;
import stroom.gwt.workbench.client.widgets.tree.TreeFixtures;
import stroom.gwt.workbench.framework.client.play.Play;
import stroom.gwt.workbench.framework.client.play.Spy;
import stroom.gwt.workbench.framework.client.play.TextMatch;
import stroom.gwt.workbench.framework.client.story.ComponentStories;
import stroom.gwt.workbench.framework.client.story.StoryLayout;
import stroom.gwt.workbench.framework.client.story.StoryRegistry;
import stroom.shapeshifter.shared.ShapeshifterDoc;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/// What every rung of design 46's ladder needs, so that a rung is only its own steps.
///
/// **A story per step.** The test runner takes exactly one screenshot per story, after the play
/// has finished, and names it after the story — there is no way to photograph the middle of a
/// play. So [#register] makes one story per stage, each replaying the stages before it and
/// stopping at its own. Measured on rung 1: about 5.4s of fixed setup per story and 17ms per
/// replayed step. The steps are written once, in the rung's own list.
///
/// **What a rung can prove.** Every reply the editor gets is canned, so a walkthrough shows that
/// the UI *can build* a configuration — never that the configuration runs. That division is
/// design 46 §2's: authorability here, correctness in the engine's own tests. A rung's last
/// stage is the keystone (§3): the project the editor is holding, read off the wire with
/// [#projectBuilt], compared with the engine's own fixture.
///
/// **Why the panes stay empty until the end.**
/// [stroom.shapeshifter.client.component.ShapeshifterDesignController] runs the project over the
/// sample on a debounce after *every* edit once a sample exists, and the preview reply is canned.
/// Running mid-build would photograph a finished configuration beside a half-written one, so a
/// rung is served [#emptyTrace] until its last stage, which gets the real one. "Ran, and nothing
/// matched" is true while nothing dispatches, and understates rather than overstates afterwards.
final class Walkthrough {

    /// ShapeshifterResource.preview(): sent on every edit once there is a sample to run over,
    /// where validate is sent when there is not.
    static final String PREVIEW_PATH = "/shapeshifter/v1/preview";

    // A rung starts from a document the author has just created, so the fixture is a document
    // with no project in it at all. ShapeshifterEditor.parse() reads blank text as
    // ProjectText.empty(), which is Templates.ensureDocument of a nameless project — so what
    // arrives on screen is one template named "document", matching the source, with an
    // apply-templates that selects nothing (design 44 §5m). The author extends that; they do not
    // make it, which is the correction design 46 §5.3 records.
    private static final DocRef BLANK_REF =
            new DocRef(ShapeshifterDoc.TYPE, "ss-blank", "New Project");

    private static final String BLANK_DOC = "{\n"
                                            + "  \"type\": \"" + ShapeshifterDoc.TYPE + "\","
                                            + " \"uuid\": \"ss-blank\", \"name\": \"New Project\",\n"
                                            + "  \"description\": null,\n"
                                            + "  \"data\": \"\",\n"
                                            + "  \"sampleText\": null,\n"
                                            + "  \"sampleKind\": \"PASTED\"\n"
                                            + "}";

    private Walkthrough() {
        // Static utility
    }

    /// A run that read the sample and matched nothing: the honest answer for a project that is
    /// still being written. TraceModel copes with no frames at all — ROOT is a frame id whether
    /// or not the run produced one.
    ///
    /// @param input The sample the author pasted, echoed back as a run would.
    /// @return The trace, as JSON.
    static String emptyTrace(final String input) {
        return "{\n"
               + "  \"compiled\": true, \"sampleRead\": true,\n"
               + "  \"input\": " + ShapeshifterEditorStories.asJsonString(input) + ",\n"
               + "  \"output\": \"\",\n"
               + "  \"frames\": [], \"captures\": [],\n"
               + "  \"groups\": [], \"attempts\": [], \"guards\": [],\n"
               + "  \"attemptsSeen\": 0, \"timings\": [],\n"
               + "  \"outputs\": [], \"instructions\": [], \"messages\": [],\n"
               + "  \"runNanos\": 0\n"
               + "}";
    }

    /// The routes a rung is served. There is no PUT route: Save cannot be driven from a story, so
    /// a fixture for it would be one nothing ever reaches.
    ///
    /// @param trace The preview reply, which says how far the build has got.
    /// @return The routes.
    static RestFixtures routes(final String trace) {
        return TreeFixtures.explorerRoutes(
                        new ExplorerFixture(ExplorerFixture.folder("System",
                                ExplorerFixture.doc("New Project", ShapeshifterDoc.TYPE))))
                .get("/shapeshifter/v1/ss-blank", RestReply.json(BLANK_DOC))
                .post(ShapeshifterEditorStories.VALIDATE_PATH,
                        RestReply.json(ShapeshifterEditorStories.VALIDATION))
                .post(PREVIEW_PATH, RestReply.json(trace))
                .post("/shapeshifter/v1/print", RestReply.json(ShapeshifterEditorStories.PRINTED))
                .post("/shapeshifter/v1/patternInfo",
                        RestReply.json(ShapeshifterEditorStories.PATTERN_INFO))
                .get("/shapeshifter/v1/library", RestReply.json(ShapeshifterEditorStories.LIBRARY))
                .get("/shapeshifter/v1/encodings",
                        RestReply.json(ShapeshifterEditorStories.ENCODINGS))
                .post("/permission/doc/v1/fetchDocumentUserPermissions",
                        RestReply.json(ShapeshifterEditorStories.DOC_PERMISSIONS))
                .post("/meta/v1/find", RestReply.json(ShapeshifterEditorStories.META_ROWS))
                .build();
    }

    /// Adds a rung's stories: one per stage, each replaying the stages before it, the last served
    /// the real trace.
    ///
    /// @param registry The registry to add to.
    /// @param title The component title the stories sit under.
    /// @param source The rung's own class, for the story source link.
    /// @param stages The rung's steps, in the author's order.
    /// @param building The routes for every stage but the last.
    /// @param built The routes for the last.
    static void register(final StoryRegistry registry, final String title, final Class<?> source,
                         final List<Stage> stages, final RestFixtures building,
                         final RestFixtures built) {
        ComponentStories stories = registry.component(title, source).layout(StoryLayout.FULLSCREEN);
        for (int i = 0; i < stages.size(); i++) {
            final int upTo = i;
            final RestFixtures fixtures = upTo == stages.size() - 1
                    ? built
                    : building;
            stories = stories
                    .story(stages.get(upTo).getName(), context ->
                            ShapeshifterEditorStories.render(context, false, fixtures, BLANK_REF))
                    .withPlay(play -> walkTo(play, stages, upTo));
        }
    }

    // Every stage up to and including this story's own. The ones before it are replayed rather
    // than assumed: a story that started from a saved state would be testing the save.
    private static void walkTo(final Play play, final List<Stage> stages, final int upTo) {
        final Play screen = play.screen();
        for (int i = 0; i <= upTo; i++) {
            stages.get(i).getWalk().accept(play, screen);
        }
        ShapeshifterEditorStories.expectNoProblems(play);
    }

    /// The project the editor last put on the wire. An edit goes to validate while there is no
    /// sample to run over and to preview once there is
    /// ([stroom.shapeshifter.client.component.ShapeshifterDesignController]), so this reads
    /// whichever fired last rather than assuming either. Validate's body is the project;
    /// preview's carries it as a field beside the sample.
    ///
    /// Template ids are generated, so they are taken out of the comparison rather than pinned —
    /// an id is the one part of a configuration no author chooses.
    ///
    /// @param requests The request spy.
    /// @return The project JSON, ids removed, or null if none has been sent.
    static String projectBuilt(final Spy requests) {
        final List<List<Object>> calls = requests.getCalls();
        for (int i = calls.size() - 1; i >= 0; i--) {
            final RecordedRequest request =
                    RecordedRequest.parse(String.valueOf(calls.get(i).get(0)));
            if (!"POST".equals(request.getMethod())) {
                continue;
            }
            if (ShapeshifterEditorStories.VALIDATE_PATH.equals(request.getPath())) {
                return ShapeshifterEditorStories.withoutIds(request.getBody());
            }
            if (PREVIEW_PATH.equals(request.getPath())) {
                final Object body = JsonValues.parse(request.getBody());
                final Object project = body instanceof Map
                        ? ((Map<?, ?>) body).get("project")
                        : null;
                if (project != null && !isExperiment(String.valueOf(project))) {
                    return ShapeshifterEditorStories.withoutIds(String.valueOf(project));
                }
            }
        }
        return null;
    }

    /// The field of a new card whose kind is left to what is typed, by its label: what the
    /// body's New row shows once it is opened.
    static final String ENTRY = "Line";

    /// The body's New row opened, and a line typed into it and added with Enter (design 47 A):
    /// text is a text card, `$1` a value-of card, and a line that mixes them is split into the
    /// sequence.
    ///
    /// @param play The play.
    /// @param line The line, with a line break spelt `\n` as the field takes it.
    static void enter(final Play play, final String line) {
        play.click(ShapeshifterEditorStories.newRow(play));
        enterNext(play, line);
    }

    /// A line put in above a card of the body: the card is the selected row, the toolbar opens
    /// a new card above it, and the line is typed there. It lands where it belongs, so there
    /// is nothing to move afterwards.
    ///
    /// @param play The play.
    /// @param kind The kind of the card it goes above, which the body has one of.
    /// @param line The line, with a line break spelt `\n` as the field takes it.
    static void enterAbove(final Play play, final String kind, final String line) {
        play.click(ShapeshifterEditorStories.cardLine(play, kind));
        play.click(play.getByRole("button", "Insert an instruction above the selected one"));
        enterNext(play, line);
    }

    /// A line typed after another: a new card is open after the one before it, with the
    /// keyboard in it, so there is nothing to open first.
    ///
    /// Typed at the keyboard rather than into the element, as an author does.
    ///
    /// @param play The play.
    /// @param line The line, with a line break spelt `\n` as the field takes it.
    static void enterNext(final Play play, final String line) {
        play.waitFor(() -> play.expect(play.getByLabelText(ENTRY)).toHaveFocus());
        play.keyboard(line + "{Enter}");
        // The line is taken, and another is open after it with the keyboard for the next one
        play.waitFor(() -> play.expect(play.getByLabelText(ENTRY).value()).toEqual(""));
        play.waitFor(() -> play.expect(play.getByLabelText(ENTRY)).toHaveFocus());
    }

    /// A template in a mode, with its regex. The dialog that makes a template is also the only
    /// place a mode can be made — the dispatch card's Mode is a picker over modes that already
    /// exist — so `newMode` is a parameter here rather than a step of its own, and a rung that
    /// needs a new mode has to make its first template before anything can dispatch into it
    /// (design 46 §6.1a).
    ///
    /// @param play The play.
    /// @param screen The screen, for the popups a dialog opens on the document body.
    /// @param name The template's name.
    /// @param mode The mode it goes in.
    /// @param newMode Whether that mode has to be made here.
    /// @param pattern Its regex, with `[` doubled for userEvent.
    static void addTemplate(final Play play, final Play screen, final String name,
                                    final String mode, final boolean newMode,
                                    final String pattern) {
        play.click(play.getByRole("button", "Add template"));
        final Play adding = ShapeshifterEditorStories.dialogMatching(play, screen, "New Template");
        adding.type(adding.getByLabelText("Name"), name);
        if (newMode) {
            play.click(adding.getByRole("button", "New..."));
            final Play naming = ShapeshifterEditorStories.dialogMatching(play, screen, "mode");
            naming.type(naming.getByRole("textbox").first(), mode);
            play.click(naming.getByRole("button", StroomDom.button("OK")));
        } else {
            ShapeshifterEditorStories.pickFromBox(play, screen,
                    adding.getByLabelText("Mode"), mode);
        }
        play.click(adding.getByRole("button", StroomDom.button("OK")));
        play.waitFor(() -> play.expect(play.getByText(name,
                ShapeshifterEditorStories.TEMPLATE_NAME)).toBeInTheDocument());
        play.click(play.querySelector(ShapeshifterEditorStories.MATCH_CHIP));
        play.waitFor(() -> play.expect(play.getByLabelText("Pattern")).toBeInTheDocument());
        play.type(play.getByLabelText("Pattern"), pattern);
        // The workbench commits the pattern on a debounce (RegexController), and closing it does
        // not flush that. Wait for the model to have it — the strip's match chip is where it
        // shows — rather than for the field to show it, which it does immediately either way.
        play.click(play.getByRole("button", TextMatch.regex("close the workbench", "i")));
        play.waitFor(() -> play.expect(play.querySelector(ShapeshifterEditorStories.MATCH_CHIP)
                .textContent()).toMatch(pattern.replace("[[", "[")));
    }

    /// Whether a preview is the pattern workbench's experiment rather than the author's project.
    ///
    /// Opening the workbench runs the sample against **one template alone**, stripped to its
    /// match (`Templates.experiment`, design 44 §2), so that the sample pane can show what that
    /// pattern hits and nothing else. It goes to the same endpoint as a real run, so the wire is
    /// not by itself a record of what the editor is holding — read naively, the last preview
    /// before a run is often a half-built template on its own.
    ///
    /// The discriminator is the root: `Templates.ensureDocument` means a real project always has
    /// a template whose match is the literal `source`, and an experiment never does, because the
    /// root is not the subject and is not carried into it.
    ///
    /// @param project The project a preview carried.
    /// @return Whether it is an experiment.
    private static boolean isExperiment(final String project) {
        return !project.contains("\"match\": \"source\"");
    }

    /// One step of a walkthrough: the name its story and screenshot take, and what the author
    /// does at it.
    static final class Stage {

        private final String name;
        private final BiConsumer<Play, Play> walk;

        Stage(final String name, final BiConsumer<Play, Play> walk) {
            this.name = name;
            this.walk = walk;
        }

        String getName() {
            return name;
        }

        BiConsumer<Play, Play> getWalk() {
            return walk;
        }
    }
}
