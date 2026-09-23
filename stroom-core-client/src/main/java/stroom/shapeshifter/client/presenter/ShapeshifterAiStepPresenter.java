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

package stroom.shapeshifter.client.presenter;

import stroom.docref.DocRef;
import stroom.document.client.event.OpenDocumentEvent;
import stroom.pipeline.shared.stepping.ElementStepDetails;
import stroom.pipeline.stepping.client.presenter.ElementStepDetailsPresenter;
import stroom.shapeshifter.client.presenter.ShapeshifterAiStepPresenter.ShapeshifterAiStepView;
import stroom.shapeshifter.shared.ShapeshifterAiStepDetails;
import stroom.shapeshifter.shared.StageScore;
import stroom.shapeshifter.shared.StageVerdict;
import stroom.shapeshifter.shared.SupervisorTurn;
import stroom.util.shared.NullSafe;

import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlBuilder;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.List;
import java.util.Map.Entry;

/**
 * The stage pane (A30, design 01 §11.7): what a supervised stage did with the stream at the cursor,
 * where the code pane would be.
 * <p>
 * A supervisor has no document to show as code, and what a person selecting it needs is the decision —
 * which shape this stream has, what was bound, what the scorers made of it, and what was said to the
 * model where it was learned. Stepping is a dry run (A30), so an unbound shape shows what the stage
 * <em>would</em> do and the pane says so.
 * <p>
 * Read-only for now. The actions A30 puts beside the evidence — Approve, Reject, pin, retract — are the
 * Routing tab's and the Supervisor view's, and arrive with them (design 03 §7 slices 7 and 8).
 */
public class ShapeshifterAiStepPresenter
        extends MyPresenterWidget<ShapeshifterAiStepView>
        implements ElementStepDetailsPresenter {

    @Inject
    public ShapeshifterAiStepPresenter(final EventBus eventBus, final ShapeshifterAiStepView view) {
        super(eventBus, view);
        view.setDocumentOpener(docRef -> OpenDocumentEvent.fire(this, docRef, true));
    }

    @Override
    public void setDetails(final ElementStepDetails details) {
        if (details instanceof final ShapeshifterAiStepDetails stage) {
            getView().setDecision(stage.getReason());
            getView().setSummary(summary(stage));
            getView().setVerdicts(verdicts(stage.getVerdicts()));
            getView().setTranscript(transcript(stage.getTranscript()));
            getView().setFragment(stage.getFragment());
        } else {
            // A step this element had nothing to say about — one it never ran on, one the parser above
            // it refused — clears the pane. Left alone it would show the step before as this one, which
            // is worse than showing nothing: a decision, a rule and a transcript that are not this
            // record's, presented as though they were.
            clear();
        }
    }

    private void clear() {
        final SafeHtml nothing = new SafeHtmlBuilder().toSafeHtml();
        getView().setDecision("");
        getView().setSummary(nothing);
        getView().setVerdicts(nothing);
        getView().setTranscript(nothing);
        getView().setFragment(null);
    }

    private static SafeHtml summary(final ShapeshifterAiStepDetails stage) {
        final SafeHtmlBuilder html = new SafeHtmlBuilder();
        html.appendHtmlConstant("<dl class=\"shapeshifterStep-summary\">");
        for (final Entry<String, String> field : NullSafe.map(stage.getShape()).entrySet()) {
            term(html, field.getKey(), field.getValue());
        }
        if (stage.getDocument() != null) {
            term(html, "Document", stage.getDocument().getName());
        }
        if (stage.getRuleUuid() != null) {
            term(html, "Rule", stage.getRuleUuid());
        }
        if (stage.getScore() != null) {
            term(html, "Score", String.valueOf(stage.getScore()));
        }
        if (stage.isProvisional()) {
            term(html, "Provisional", "yes, until the shape brings enough records to judge it");
        }
        if (stage.getRecordBoundary() != null) {
            term(html, "Record", stage.getRecordBoundary());
        }
        html.appendHtmlConstant("</dl>");
        return html.toSafeHtml();
    }

    /// What the scorers made of each step of the chain (design 01 §8.4), in chain order. A gate that was
    /// missed is what decides a promotion, so it is named as one.
    private static SafeHtml verdicts(final List<StageVerdict> verdicts) {
        final SafeHtmlBuilder html = new SafeHtmlBuilder();
        if (NullSafe.isEmptyCollection(verdicts)) {
            return html.toSafeHtml();
        }
        html.appendHtmlConstant("<table class=\"shapeshifterStep-table\">");
        for (final StageVerdict verdict : verdicts) {
            html.appendHtmlConstant("<tr class=\"shapeshifterStep-step\"><th colspan=\"2\">");
            html.appendEscaped("Step " + verdict.getStep() + " — " + verdict.getWeightedTotal()
                               + (verdict.isPassed()
                                       ? " (passed)"
                                       : " (failed)"));
            html.appendHtmlConstant("</th></tr>");
            for (final StageScore score : NullSafe.list(verdict.getScores())) {
                html.appendHtmlConstant("<tr><td>");
                html.appendEscaped(score.getScorer().getDisplayValue());
                html.appendHtmlConstant("</td><td>");
                html.appendEscaped(score.getValue() + (score.isMet()
                        ? " (met "
                        : " (missed ") + score.getThreshold() + (score.isGate()
                        ? ", a gate)"
                        : ")"));
                html.appendHtmlConstant("</td></tr>");
            }
        }
        html.appendHtmlConstant("</table>");
        return html.toSafeHtml();
    }

    /// Every exchange with the model, where this stream was learned. Empty where it was served, which is
    /// every stream after the first of its shape.
    private static SafeHtml transcript(final List<SupervisorTurn> turns) {
        final SafeHtmlBuilder html = new SafeHtmlBuilder();
        if (NullSafe.isEmptyCollection(turns)) {
            return html.toSafeHtml();
        }
        html.appendHtmlConstant("<table class=\"shapeshifterStep-table\">");
        for (final SupervisorTurn turn : turns) {
            html.appendHtmlConstant("<tr class=\"shapeshifterStep-step\"><th colspan=\"2\">");
            html.appendEscaped(turn.getNumber() + ". " + turn.getKind().getDisplayValue()
                               + (turn.getStepId() == null
                                       ? ""
                                       : " (" + turn.getStepId() + ")"));
            html.appendHtmlConstant("</th></tr><tr><td>Asked</td><td>");
            html.appendEscaped(NullSafe.string(turn.getQuestion()));
            html.appendHtmlConstant("</td></tr><tr><td>Answered</td><td>");
            html.appendEscaped(NullSafe.string(turn.getAnswer()));
            html.appendHtmlConstant("</td></tr>");
        }
        html.appendHtmlConstant("</table>");
        return html.toSafeHtml();
    }

    private static void term(final SafeHtmlBuilder html, final String term, final String value) {
        html.appendHtmlConstant("<dt>");
        html.appendEscaped(term);
        html.appendHtmlConstant("</dt><dd>");
        html.appendEscaped(NullSafe.string(value));
        html.appendHtmlConstant("</dd>");
    }

    // --------------------------------------------------------------------------------


    public interface ShapeshifterAiStepView extends View {

        void setDecision(String decision);

        void setSummary(SafeHtml summary);

        void setVerdicts(SafeHtml verdicts);

        void setTranscript(SafeHtml transcript);

        /// The fragment this stream was processed by, offered as a link; null where nothing was bound.
        void setFragment(DocRef fragment);

        void setDocumentOpener(DocumentOpener opener);
    }


    // --------------------------------------------------------------------------------


    @FunctionalInterface
    public interface DocumentOpener {

        void open(DocRef docRef);
    }
}
