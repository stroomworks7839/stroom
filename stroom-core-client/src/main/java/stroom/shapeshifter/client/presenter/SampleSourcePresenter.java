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

import stroom.alert.client.event.ConfirmEvent;
import stroom.data.client.presenter.ExpressionPresenter;
import stroom.data.client.presenter.ExpressionValidator;
import stroom.data.client.presenter.SourcePresenter;
import stroom.data.client.presenter.SteppingMetaListPresenter;
import stroom.editor.client.presenter.EditorPresenter;
import stroom.meta.shared.Meta;
import stroom.meta.shared.MetaExpressionUtil;
import stroom.meta.shared.MetaFields;
import stroom.meta.shared.MetaRow;
import stroom.pipeline.shared.SourceLocation;
import stroom.query.api.ExpressionOperator;
import stroom.shapeshifter.client.presenter.SampleSourcePresenter.SampleSourceView;
import stroom.svg.client.SvgPresets;
import stroom.widget.button.client.ButtonView;
import stroom.widget.popup.client.event.HidePopupRequestEvent;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupType;

import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;
import edu.ycp.cs.dh.acegwt.client.ace.AceEditorMode;

import java.util.Objects;

/**
 * Where the run's data comes from (design 18 Q2, §5.7; design 43's {@code SampleSourcePresenter}):
 * a record of a stream, or text pasted. The document never holds data, so this is the one door,
 * and behind the stream half of it is Stroom's own — the stepping stream list and the source
 * viewer beneath it, the same pair the stepping tab shows, because phase C mounts this navigator
 * inside stepping and the two should name a record the same way.
 *
 * <p>A page of the project rather than a dialog over it (design 44 §5a), reached from the panel,
 * committing as every other surface here does: <b>the record the viewer is showing is the
 * sample</b> — pick a stream, or step its parts and records, and that is the choice — and
 * leaving the paste box chooses what is in it. There is nothing to confirm.
 */
public class SampleSourcePresenter
        extends MyPresenterWidget<SampleSourceView>
        implements SampleSourceUiHandlers {

    /** Where the sample comes from: the two doors design 18 §5.7 describes. */
    public enum Kind {
        STREAM("a stream"),
        PASTED("pasted text");

        private final String label;

        Kind(final String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final SteppingMetaListPresenter metaList;
    private final SourcePresenter source;
    private final Provider<ExpressionPresenter> filterProvider;
    private final ExpressionValidator expressionValidator;
    private final ButtonView filterButton;
    private final EditorPresenter pasted;

    private ProjectHost host;
    private boolean listed;
    private boolean reading;

    @Inject
    public SampleSourcePresenter(final EventBus eventBus,
                                 final SampleSourceView view,
                                 final Provider<SteppingMetaListPresenter> metaListProvider,
                                 final SourcePresenter source,
                                 final Provider<ExpressionPresenter> filterProvider,
                                 final ExpressionValidator expressionValidator,
                                 final Provider<EditorPresenter> editorProvider) {
        super(eventBus, view);
        this.metaList = metaListProvider.get();
        this.source = source;
        this.filterProvider = filterProvider;
        this.expressionValidator = expressionValidator;
        view.setUiHandlers(this);
        // The list sits inside the source view, where stepping puts it: pick above, read below.
        source.getView().setMetaListContainerView(metaList.getWidget());
        source.setSteppingSource(false);
        view.setStreamView(source.getWidget());
        // Text is text: the editor every other pane of this tab pastes into (design 44 §5a).
        pasted = editorProvider.get();
        pasted.setMode(AceEditorMode.TEXT);
        pasted.getFormatAction().setAvailable(false);
        pasted.getLineNumbersOption().setOff();
        view.setPasteView(pasted.getView());
        filterButton = metaList.add(SvgPresets.FILTER);
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(metaList.getSelectionModel().addSelectionHandler(event -> chose()));
        registerHandler(filterButton.addClickHandler(event -> filter()));
        // A blur is the commit, as it is for every field here; the editor has no other.
        registerHandler(pasted.addValueChangeHandler(event -> onPasted()));
        source.setOnLocationChange(this::viewing);
    }

    public void setHost(final ProjectHost host) {
        this.host = host;
    }

    /** Shown: read what the sample is into the page, and list the streams the first time. */
    public void refresh() {
        final SampleSource current = host == null
                ? null
                : host.getSampleSource();
        reading = true;
        try {
            getView().setKind(current != null && current.getText() != null
                    ? Kind.PASTED
                    : Kind.STREAM);
            final String text = current == null || current.getText() == null
                    ? ""
                    : current.getText();
            if (!text.equals(pasted.getText())) {
                pasted.setText(text);
            }
        } finally {
            reading = false;
        }
        if (!listed) {
            listed = true;
            // The unlocked streams, as stepping lists them; the filter narrows them.
            metaList.setExpression(ExpressionValidator.ALL_UNLOCKED_EXPRESSION, metaList::refresh);
        }
    }

    /** A stream was chosen in the list: show its first record, which makes it the sample. */
    private void chose() {
        final MetaRow row = metaList.getSelected();
        if (reading || row == null || row.getMeta() == null) {
            return;
        }
        final Meta meta = row.getMeta();
        source.setSourceLocation(SourceLocation.builder(meta.getId()).build());
    }

    /** The viewer settled on a record: that record is the sample. */
    private void viewing(final SourceLocation location) {
        if (reading || host == null || location == null || getView().getKind() != Kind.STREAM) {
            return;
        }
        // The address alone: paging through a record's characters is reading, not choosing.
        final SourceLocation address = SourceLocation.builder(location.getMetaId())
                .withChildStreamType(location.getChildType())
                .withPartIndex(location.getPartIndex())
                .withRecordIndex(Math.max(0, location.getRecordIndex()))
                .build();
        final MetaRow row = metaList.getSelected();
        final SampleSource chosen = SampleSource.record(address, row == null || row.getMeta() == null
                ? null
                : row.getMeta().getFeedName());
        final SampleSource current = host.getSampleSource();
        if (current == null || !Objects.equals(current.getLabel(), chosen.getLabel())) {
            host.setSampleSource(chosen);
        }
    }

    @Override
    public void onKind(final Kind kind) {
        if (reading) {
            return;
        }
        if (kind == Kind.PASTED) {
            onPasted();
        } else {
            chose();
        }
    }

    @Override
    public void onPasted() {
        final String text = pasted.getText() == null || pasted.getText().trim().isEmpty()
                ? ""
                : pasted.getText();
        if (reading || host == null || text.isEmpty() || getView().getKind() != Kind.PASTED) {
            return;
        }
        final SampleSource current = host.getSampleSource();
        if (current != null && text.equals(current.getText())) {
            return;
        }
        host.setSampleSource(SampleSource.pasted(text));
    }

    /** Narrow the stream list, as stepping narrows it: the same expression over the same fields. */
    private void filter() {
        final ExpressionPresenter presenter = filterProvider.get();
        presenter.read(metaList.getCriteria().getExpression(),
                MetaFields.STREAM_STORE_DOC_REF,
                MetaFields.getAllFields());
        presenter.getWidget().getElement().addClassName("default-min-sizes");
        ShowPopupEvent.builder(presenter)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(1_000, 600))
                .caption("Filter Streams")
                .onShow(e -> presenter.focus())
                .onHideRequest(e -> applyFilter(presenter, e))
                .fire();
    }

    private void applyFilter(final ExpressionPresenter presenter, final HidePopupRequestEvent event) {
        if (!event.isOk()) {
            event.hide();
            return;
        }
        expressionValidator.validateExpression(this, MetaFields.getAllFields(), presenter.write(), expression -> {
            if (Objects.equals(expression, metaList.getCriteria().getExpression())) {
                event.hide();
                return;
            }
            if (MetaExpressionUtil.hasAdvancedCriteria(expression)) {
                ConfirmEvent.fire(this,
                        "You are setting advanced filters! It is recommended you constrain your filter "
                        + "(e.g. by 'Created') to avoid an expensive query. Are you sure you want to apply "
                        + "this advanced filter?",
                        confirm -> {
                            if (confirm) {
                                apply(expression);
                                event.hide();
                            } else {
                                event.reset();
                            }
                        });
            } else {
                apply(expression);
                event.hide();
            }
        }, this);
    }

    private void apply(final ExpressionOperator expression) {
        metaList.getCriteria().setExpression(expression);
        metaList.getCriteria().obtainPageRequest().setOffset(0);
        metaList.getSelectionModel().clear();
        metaList.refresh();
    }

    public interface SampleSourceView extends View, HasUiHandlers<SampleSourceUiHandlers> {

        /** The stream half: the list and the record it is showing. */
        void setStreamView(Widget widget);

        /** The paste half: the editor text is pasted into. */
        void setPasteView(View view);

        Kind getKind();

        void setKind(Kind kind);

    }
}
