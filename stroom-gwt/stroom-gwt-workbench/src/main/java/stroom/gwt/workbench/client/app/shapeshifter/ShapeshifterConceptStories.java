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

import stroom.gwt.workbench.client.app.rest.RestFixtures;
import stroom.gwt.workbench.client.app.rest.RestReply;
import stroom.gwt.workbench.client.app.screen.StroomDom;
import stroom.gwt.workbench.framework.client.play.Play;
import stroom.gwt.workbench.framework.client.play.Query;
import stroom.gwt.workbench.framework.client.play.TextMatch;
import stroom.gwt.workbench.framework.client.story.StoryContext;
import stroom.gwt.workbench.framework.client.story.StoryLayout;
import stroom.gwt.workbench.framework.client.story.StoryRegistry;
import stroom.shapeshifter.shared.ShapeshifterDoc;

import com.google.gwt.user.client.ui.Widget;

/// What the editor leaves out until it is wanted, and what it must not leave out with it
/// (design 48): the role, the default mode's heading, Advanced, and the empty sections.
public final class ShapeshifterConceptStories {

    private static final String IN_MODE_KV = ", \"mode\": \"kv\"";
    private static final String ORDER_NOTE = "tried top to bottom: the first to match takes it";
    private static final String MODE_NOTE = ".template-panel__mode-note";
    private static final String KEY = ".key-label";
    private static final String EXPANDED = "aria-expanded";

    // Both of its templates in the default mode, which is then the only mode
    private static final String ONE_MODE = ShapeshifterEditorStories.PROJECT.replace(IN_MODE_KV, "");

    private static final String ONE_MODE_LEXER = withDispatch(ONE_MODE, "lexer");

    // The pair alone in the default mode, beside the document
    private static final String PAIR_ALONE = ONE_MODE.replace("\"name\": \"junk_line\",",
            "\"name\": \"junk_line\", \"mode\": \"junk\",");

    // kv dispatched into twice, by strategies that disagree
    private static final String MIXED = ShapeshifterEditorStories.PROJECT.replace(
            "{\"text\": \"</records>\\n\"}]}",
            "{\"apply-templates\": {\"select\": {\"parts\": [{\"capture\": {\"group\": 0}}]},"
            + " \"mode\": \"kv\", \"dispatch\": \"lexer\"}},"
            + " {\"text\": \"</records>\\n\"}]}");

    private ShapeshifterConceptStories() {
        // Static utility
    }

    /// Adds the stories to the registry.
    ///
    /// @param registry The registry to add to.
    public static void addTo(final StoryRegistry registry) {
        registry.component("App/Shapeshifter/ShapeshifterConcepts", ShapeshifterConceptStories.class)
                .layout(StoryLayout.FULLSCREEN)

                // What a match is for is asked of a template that shares its mode. The document
                // is in no mode, so it is not another template in the default one
                .story("ATemplateAloneInTheDefaultModeIsNotAskedItsRole", context -> render(context, PAIR_ALONE))
                .withPlay(play -> {
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(play.querySelector(ShapeshifterEditorStories.MATCH_CHIP))
                            .not().toBeNull());
                    play.expect(play.getByText("Match is", KEY)).not().toBeVisible();
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // Two templates do share it
                .story("TemplatesInOneModeAreAskedTheirRole", context -> render(context, ONE_MODE))
                .withPlay(play -> {
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(play.getByText("Match is", KEY)).toBeVisible());
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // The default mode has no heading when it is the only mode, unless the heading
                // has something to say
                .story("ASingleModeSaysTheOrderItTriesItsTemplatesIn", context -> render(context, ONE_MODE))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(play.getByText("mode: default")).toBeVisible());
                    play.expect(play.getByText(ORDER_NOTE, MODE_NOTE)).toBeVisible();
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                .story("ASingleModeSaysHowItIsDispatched", context -> render(context, ONE_MODE_LEXER))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(play.getByText("dispatched with lexer", MODE_NOTE)).toBeVisible());
                    play.expect(play.queryByText(ORDER_NOTE, MODE_NOTE)).toBeNull();
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // Sites that disagree have no one order: at the lexer's the longest match wins
                .story("AModeDispatchedTwoWaysSaysNoOrder", context -> render(context, MIXED))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(play.getByText("mode: kv")).toBeVisible());
                    play.expect(play.queryByText(ORDER_NOTE, MODE_NOTE)).toBeNull();
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // Advanced opens for a setting that changes what the project does; folded by the
                // author it stays folded, though the settings are read again after every edit
                .story("AdvancedFoldedStaysFolded", context -> render(context, ONE_MODE_LEXER))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(play.getByText("Settings",
                            ShapeshifterEditorStories.TEMPLATE_NAME)).toBeInTheDocument());
                    play.click(play.getByText("Settings", ShapeshifterEditorStories.TEMPLATE_NAME));
                    play.waitFor(() -> play.expect(advanced(play)).toHaveAttribute(EXPANDED, "true"));
                    play.click(advanced(play));
                    play.expect(advanced(play)).toHaveAttribute(EXPANDED, "false");
                    play.clear(play.getByLabelText("Project name"));
                    play.type(play.getByLabelText("Project name"), "renamed");
                    play.keyboard("{Tab}");
                    play.waitFor(() -> play.expect(play.getByRole("button", "Save"))
                            .not().toHaveAttribute("aria-disabled"));
                    play.expect(advanced(play)).toHaveAttribute(EXPANDED, "false");
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // `+ guard or limit` opens the guard and limits for the template it was pressed
                // on, not for every template shown after it
                .story("AskingForOneGuardOpensNoOther", context -> render(context, ONE_MODE))
                .withPlay(play -> {
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(addGuard(play)).toBeVisible());
                    play.click(addGuard(play));
                    play.waitFor(() -> play.expect(guardAndLimits(play)).toHaveAttribute(EXPANDED, "true"));
                    ShapeshifterEditorStories.selectTemplate(play, "junk_line");
                    play.waitFor(() -> play.expect(guardAndLimits(play)).toHaveAttribute(EXPANDED, "false"));
                    ShapeshifterEditorStories.expectNoProblems(play);
                })
                // The add link goes as its section comes, and the keyboard goes with it, and
                // back again when the last row is removed
                .story("TheKeyboardFollowsTheAddLink", context -> render(context, ONE_MODE))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    ShapeshifterEditorStories.selectTemplate(play, "pair");
                    play.waitFor(() -> play.expect(addDeclaration(play)).toBeVisible());
                    play.click(addDeclaration(play));
                    final Play dialog = play.within(screen.findByText("New Declaration", StroomDom.DIALOG_TITLE)
                            .closest(StroomDom.DIALOG));
                    dialog.type(dialog.getByRole("textbox").first(), "seen");
                    play.click(screen.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(play.getByText("seen")).toBeVisible());
                    play.waitFor(() -> play.expect(addDeclaration(play)).toHaveFocus());
                    play.click(play.getByRole("button", TextMatch.regex("remove declaration seen$")));
                    final Play question = play.within(screen.findByText(
                            TextMatch.containing("Remove declaration")).closest(StroomDom.DIALOG));
                    play.click(question.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(play.queryByText("seen")).toBeNull());
                    play.waitFor(() -> play.expect(addDeclaration(play)).toHaveFocus());
                    ShapeshifterEditorStories.expectNoProblems(play);
                });
    }

    private static Query advanced(final Play play) {
        return play.getByRole("button", TextMatch.regex("^Advanced"));
    }

    private static Query addGuard(final Play play) {
        return play.getByRole("button", StroomDom.button("+ guard or limit"));
    }

    private static Query addDeclaration(final Play play) {
        return play.getByRole("button", StroomDom.button("+ declaration"));
    }

    private static Query guardAndLimits(final Play play) {
        return play.getByRole("button", TextMatch.regex("^Guard and limits"));
    }

    // The project's own dispatch, which the root level is run with
    private static String withDispatch(final String project, final String dispatch) {
        return project.replace("\"encoding\": \"utf-8\"",
                "\"encoding\": \"utf-8\", \"dispatch\": \"" + dispatch + "\"");
    }

    private static Widget render(final StoryContext context, final String project) {
        final String doc = "{\"type\": \"" + ShapeshifterDoc.TYPE + "\","
                           + " \"uuid\": \"ss-strict-kv\", \"name\": \"Key Value Lines\","
                           + " \"data\": " + ShapeshifterEditorStories.asJsonString(project) + "}";
        return ShapeshifterEditorStories.render(context, RestFixtures.builder()
                .get("/shapeshifter/v1/ss-strict-kv", RestReply.json(doc))
                .build());
    }
}
