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
import stroom.gwt.workbench.framework.client.play.Query;
import stroom.gwt.workbench.framework.client.play.Spy;
import stroom.gwt.workbench.framework.client.play.TextMatch;
import stroom.gwt.workbench.framework.client.story.ComponentStories;
import stroom.gwt.workbench.framework.client.story.StoryLayout;
import stroom.gwt.workbench.framework.client.story.StoryRegistry;
import stroom.shapeshifter.shared.ShapeshifterDoc;

import java.util.LinkedHashMap;
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

    // ShapeshifterResource.explode(), for a template's empty regex turned into a pattern tree:
    // the engine's explosion of an empty pattern is an empty regex leaf, which is what the tree
    // then starts from (MatchEditorController.convert).
    private static final String EXPLODED_BLANK = """
            {"text": "{\\"regex\\": \\"\\"}"}""";

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
                .post(PREVIEW_PATH, request -> RestReply.json(withWalkedIds(trace, request)))
                .post("/shapeshifter/v1/print", RestReply.json(ShapeshifterEditorStories.PRINTED))
                .post("/shapeshifter/v1/explode", RestReply.json(EXPLODED_BLANK))
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

    /// Adds a story beside a walkthrough: its stages up to and including one replayed, then a walk
    /// of its own, for another way to the same place the next stage reaches — so that both ways
    /// are driven, and the walkthrough itself still reads as one author's journey.
    ///
    /// @param registry The registry to add to.
    /// @param title The walkthrough's title, under which the story sits beside its stages.
    /// @param source The rung's own class, for the story source link.
    /// @param stages The rung's steps, in the author's order.
    /// @param after The last stage replayed.
    /// @param name The story's name; named after the stage it follows, it sorts beside it.
    /// @param walk What it does then.
    /// @param fixtures Its routes.
    static void registerBeside(final StoryRegistry registry, final String title, final Class<?> source,
                               final List<Stage> stages, final int after, final String name,
                               final BiConsumer<Play, Play> walk, final RestFixtures fixtures) {
        registry.component(title, source).layout(StoryLayout.FULLSCREEN)
                .story(name, context -> ShapeshifterEditorStories.render(context, false, fixtures, BLANK_REF))
                .withPlay(play -> {
                    walkTo(play, stages, after);
                    walk.accept(play, play.screen());
                    ShapeshifterEditorStories.expectNoProblems(play);
                });
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
    /// body's add link opens.
    static final String ENTRY = "Line";

    /// The body's add link pressed, and a line typed and added with Enter (design 47 A):
    /// text is a text card, `$1` a value-of card, and a line that mixes them is split into the
    /// sequence.
    ///
    /// @param play The play.
    /// @param line The line, with a line break spelt `\n` as the field takes it.
    static void enter(final Play play, final String line) {
        play.click(ShapeshifterEditorStories.addLink(play));
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

    /// A template in a mode, with its regex (design 47 B; the owner, 2026-11-18): the link the
    /// mode's list ends with adds one, called `new template` and shown with its name open; the
    /// name is typed over it, and then the pattern in the match line, which Enter in the name
    /// opens. A mode that does not exist yet is added first, with the `+ mode` link the modes
    /// end with: it too arrives with its name open, and Enter in it puts the keyboard on the
    /// mode's own `+ template`. **A new mode is dispatched from the document there and then**
    /// (design 47 C): the mode's pane says nothing dispatches into it and offers the way out,
    /// and its strategy is set beside that, so the two-part edit is finished where it starts.
    ///
    /// It ends with the keyboard in the body, in a new card: Enter in the match of a template
    /// whose body is empty goes on into it. A line for the body is [#enterNext].
    ///
    /// @param play The play.
    /// @param screen The screen. Nothing here opens on the document body any more.
    /// @param name The template's name.
    /// @param mode The mode it goes in.
    /// @param newMode Whether that mode has to be made here.
    /// @param dispatch For a mode made here, the strategy it is dispatched with, as the picker
    ///                 lists it; null for the project's.
    /// @param pattern Its regex, with `[` doubled for userEvent.
    static void addTemplate(final Play play, final Play screen, final String name,
                                    final String mode, final boolean newMode, final String dispatch,
                                    final String pattern) {
        if (newMode) {
            addMode(play, mode);
            dispatchFromDocument(play, screen, dispatch);
            play.click(modeAddLink(play));
        } else {
            play.click(play.getByTitle("Click to add a template to mode " + mode));
        }
        named(play, name);
        matching(play, pattern);
        play.expect(play.querySelector(ShapeshifterEditorStories.MATCH_CHIP).textContent())
                .toMatch(pattern.replace("[[", "["));
    }

    /// The `+ mode` link the modes end with, by its title.
    static final String ADD_MODE = "Click to add a mode, and its first template";

    /// A mode, added and named: it arrives called `new mode`, shown with its name open, and
    /// what is typed replaces that. Ends with the keyboard on the mode's own `+ template`.
    ///
    /// @param play The play.
    /// @param mode The mode's name.
    static void addMode(final Play play, final String mode) {
        play.click(play.getByTitle(ADD_MODE));
        play.waitFor(() -> play.expect(play.getByLabelText("Mode name")).toHaveFocus());
        play.keyboard(mode + "{Enter}");
        play.waitFor(() -> play.expect(play.getByText(mode, ShapeshifterEditorStories.STRIP_NAME))
                .toBeVisible());
        play.waitFor(() -> play.expect(modeAddLink(play)).toHaveFocus());
    }

    /// The mode that is shown, dispatched from the document (design 47 C): the link in its
    /// pane, beside the note that nothing dispatches into it, and then, where the strategy is
    /// not the project's, the strategy in the picker the link stands beside. The document's
    /// card is never opened.
    ///
    /// @param play The play.
    /// @param screen The screen, for the picker's list.
    /// @param dispatch The strategy, as the picker lists it; null for the project's.
    static void dispatchFromDocument(final Play play, final Play screen, final String dispatch) {
        play.waitFor(() -> play.expect(play.getByText("nothing dispatches into this",
                ".template-strip__role-note")).toBeVisible());
        play.click(play.getByRole("button", StroomDom.button("dispatch from the document")));
        // Named, as a template's header names its sites: the document, whatever it is called
        play.waitFor(() -> play.expect(play.getByText(TextMatch.regex("^dispatched from \\S+$"),
                ".template-strip__role-note")).toBeVisible());
        if (dispatch != null) {
            ShapeshifterEditorStories.pickFromBox(play, screen, play.getByLabelText(MODE_DISPATCH), dispatch);
            // The mode says how it is dispatched, in the list as well as in its pane
            play.waitFor(() -> play.expect(play.getByText("dispatched with " + dispatch,
                    ".template-panel__mode-note")).toBeVisible());
        }
    }

    /// Text as userEvent types it: `[` and `{` doubled, since each alone opens a key descriptor.
    ///
    /// @param text The text, as it is to arrive.
    /// @return It, as it is to be typed.
    static String typed(final String text) {
        return text.replace("[", "[[").replace("{", "{{");
    }

    /// The strategy picker of the mode that is shown, by the name it gives a screen reader.
    static final String MODE_DISPATCH = "How the mode is dispatched";

    /// The `+ template` link of the mode that is shown, in its own list of templates.
    ///
    /// @param play The play.
    /// @return The link.
    static Query modeAddLink(final Play play) {
        return play.within(play.querySelector(".template-strip")).getByText("+ template");
    }

    /// The name of a template just added, typed over the one it arrived with: its title is
    /// open, with the keyboard in it and all of it selected.
    ///
    /// @param play The play.
    /// @param name The template's name.
    static void named(final Play play, final String name) {
        play.waitFor(() -> play.expect(play.getByLabelText("Template name")).toHaveFocus());
        play.keyboard(name + "{Enter}");
        play.waitFor(() -> play.expect(play.getByText(name,
                ShapeshifterEditorStories.TEMPLATE_NAME)).toBeInTheDocument());
    }

    /// The pattern of a template just made, typed into its match line, which is open with the
    /// keyboard in it.
    ///
    /// @param play The play.
    /// @param pattern Its regex, with `[` doubled for userEvent.
    static void matching(final Play play, final String pattern) {
        play.waitFor(() -> play.expect(play.getByLabelText("Match pattern")).toHaveFocus());
        play.keyboard(pattern + "{Enter}");
        // and on into the body, which has nothing in it yet but the card that has opened
        play.waitFor(() -> play.expect(play.getByLabelText(ENTRY)).toHaveFocus());
    }

    /// After a run, a template's row in the panel reads what the trace says of it rather than
    /// *not tried*: the trace reached the walked project's own templates (design 47 §7.2,
    /// finding 12).
    ///
    /// @param play The play.
    /// @param template The template's name, as the panel shows it.
    /// @param count What its row should read: the trace's match count for it.
    static void expectTried(final Play play, final String template, final String count) {
        final Query row = play.within(play.getByText(template, ShapeshifterEditorStories.TEMPLATE_NAME)
                .closest(".template-row")).querySelector(".template-row__count");
        play.waitFor(() -> play.expect(row.textContent()).toEqual(count));
    }

    /// The limit a template's row says it has, after its count, or that it says none.
    ///
    /// @param play The play.
    /// @param template The template's name.
    /// @param limit What the row says, or null for nothing.
    static void expectLimit(final Play play, final String template, final String limit) {
        final Query cell = play.within(play.getByText(template, ShapeshifterEditorStories.TEMPLATE_NAME)
                .closest(".template-row")).querySelector(".template-row__limit");
        // The panel is drawn again after a run, so the row may not be the final one yet
        if (limit == null) {
            play.waitFor(() -> play.expect(cell).not().toBeVisible());
        } else {
            play.waitFor(() -> play.expect(cell).toHaveTextContent(limit));
        }
    }

    /// The canned trace, its template ids made the walked project's. A trace names each
    /// template by id — its frames, and the timings the panel's rows read — and the fixture's
    /// ids are not the ones the walk generated, so served as it is the trace described
    /// templates the walked project does not have (design 47 §7.2, finding 12).
    /// Each frame carries the template's name beside its id, and the walk ends with the
    /// fixture's names, so the name is what is matched on.
    ///
    /// @param trace The trace, as the fixture holds it.
    /// @param request The preview request, whose body carries the project being run.
    /// @return The trace, with every fixture id whose name the project has replaced by its id.
    static String withWalkedIds(final String trace, final RecordedRequest request) {
        final Map<String, String> walkedIds = templateIds(request.getBody());
        final Map<String, String> fixtureNames = new LinkedHashMap<>();
        collectTemplateNames(JsonValues.parse(trace), fixtureNames);
        String result = trace;
        for (final Map.Entry<String, String> fixture : fixtureNames.entrySet()) {
            final String walked = walkedIds.get(fixture.getValue());
            if (walked != null) {
                result = result.replace("\"" + fixture.getKey() + "\"", "\"" + walked + "\"");
            }
        }
        return result;
    }

    // Name to id, for the templates of the project a preview request is running.
    private static Map<String, String> templateIds(final String previewBody) {
        final Map<String, String> ids = new LinkedHashMap<>();
        final Object body = JsonValues.parse(previewBody);
        final Object project = body instanceof Map
                ? ((Map<?, ?>) body).get("project")
                : null;
        final Object parsed = project instanceof String
                ? JsonValues.parse((String) project)
                : project;
        final Object templates = parsed instanceof Map
                ? ((Map<?, ?>) parsed).get("templates")
                : null;
        if (templates instanceof List) {
            for (final Object template : (List<?>) templates) {
                if (template instanceof Map) {
                    final Map<?, ?> fields = (Map<?, ?>) template;
                    ids.put(String.valueOf(fields.get("name")), String.valueOf(fields.get("id")));
                }
            }
        }
        return ids;
    }

    // Id to name, from every object in a trace that carries both.
    private static void collectTemplateNames(final Object value, final Map<String, String> names) {
        if (value instanceof Map) {
            final Map<?, ?> fields = (Map<?, ?>) value;
            final Object id = fields.get("templateId");
            final Object name = fields.get("templateName");
            if (id instanceof String && name instanceof String) {
                names.put((String) id, (String) name);
            }
            for (final Object child : fields.values()) {
                collectTemplateNames(child, names);
            }
        } else if (value instanceof List) {
            for (final Object child : (List<?>) value) {
                collectTemplateNames(child, names);
            }
        }
    }

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
