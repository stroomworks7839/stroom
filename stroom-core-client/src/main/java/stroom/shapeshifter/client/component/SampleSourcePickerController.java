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

import stroom.alert.client.event.ConfirmEvent;
import stroom.data.client.ExpressionValidator;
import stroom.data.client.component.MetaList;
import stroom.data.client.component.SourceViewerController;
import stroom.data.client.component.SteppingMetaListController;
import stroom.editor.client.component.CodeEditorController;
import stroom.meta.shared.Meta;
import stroom.meta.shared.MetaExpressionUtil;
import stroom.meta.shared.MetaFields;
import stroom.meta.shared.MetaRow;
import stroom.pipeline.shared.SourceLocation;
import stroom.query.api.ExpressionOperator;
import stroom.query.client.component.ExpressionEditor;
import stroom.query.client.component.ExpressionEditorController;
import stroom.query.client.model.SimpleFieldSelectionListModel;
import stroom.shapeshifter.client.model.ProjectHost;
import stroom.shapeshifter.client.model.SampleSource;
import stroom.svg.shared.SvgImage;
import stroom.widget.button.client.ToolbarButton;
import stroom.widget.component.client.Contexts;
import stroom.widget.component.client.Controller;
import stroom.widget.component.client.ControllerDialog;
import stroom.widget.popup.client.PopupSize;
import stroom.widget.popup.client.PopupType;
import stroom.widget.popup.client.event.HidePopupRequestEvent;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import edu.ycp.cs.dh.acegwt.client.ace.AceEditorMode;

import java.util.Objects;

/// Where the run's data comes from (design 18 Q2, §5.7; design 43's `SampleSourcePickerController`):
/// a record of a stream, or text pasted. The document never holds data, so this is the one door,
/// and behind the stream half of it is Stroom's own — the stepping stream list and the source
/// viewer beneath it, the same pair the stepping tab shows, because phase C mounts this navigator
/// inside stepping and the two should name a record the same way.
///
/// A page of the project rather than a dialog over it (design 44 §5a), reached from the panel,
/// committing as every other surface here does: **the record the viewer is showing is the
/// sample** — pick a stream, or step its parts and records, and that is the choice — and
/// leaving the paste box chooses what is in it. There is nothing to confirm.
public class SampleSourcePickerController extends Controller {

    private final SampleSourcePicker view;

    /// Where the sample comes from: the two doors design 18 §5.7 describes.
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

    private final SteppingMetaListController metaList;
    private final SourceViewerController source;
    private final Provider<ExpressionEditorController> filterProvider;
    private final ExpressionValidator expressionValidator;
    private final ToolbarButton filterButton;
    private final CodeEditorController pasted;

    private ProjectHost host;
    private boolean listed;
    private boolean reading;
    // From Contexts.READ_ONLY: true until the document says otherwise, so a
    // controller with no owner fails closed rather than editable.
    private boolean readOnly = true;

    @Inject
    public SampleSourcePickerController(final EventBus eventBus,
                                 final SampleSourcePicker view,
                                 final Provider<SteppingMetaListController> metaListProvider,
                                 final SourceViewerController source,
                                 final Provider<ExpressionEditorController> filterProvider,
                                 final ExpressionValidator expressionValidator,
                                 final Provider<CodeEditorController> editorProvider) {
        super(eventBus);
        this.view = view;
        initWidget(view);
        view.setOnKind(this::onKind);
        this.metaList = make(metaListProvider).attach(new MetaList());
        this.source = own(source);
        this.filterProvider = filterProvider;
        this.expressionValidator = expressionValidator;
        // The list sits inside the source view, where stepping puts it: pick above, read below.
        source.setMetaList(metaList);
        source.setSteppingSource(false);
        view.setStreamView(source.asWidget());
        // Text is text: the editor every other pane of this tab pastes into (design 44 §5a).
        pasted = make(editorProvider).attach(view.getPasted());
        pasted.setMode(AceEditorMode.TEXT);
        pasted.getFormatAction().setAvailable(false);
        pasted.getOptions().getLineNumbersOption().setOn(false);
        filterButton = metaList.getToolbar().button(SvgImage.FILTER, "Filter the streams",
                this::filter);
        own(metaList.getSelectionModel().addSelectionHandler(event -> chose()));
        // A blur is the commit, as it is for every field here; the editor has no other.
        own(pasted.addValueChangeHandler(event -> onPasted()));
        source.setOnLocationChange(this::viewing);
        watch(Contexts.READ_ONLY, readOnly -> {
            this.readOnly = readOnly;
            applyReadOnly();
        });
    }

    // One place that says what read-only means here, called when the context changes and when the
    // page is filled, so a lock change alone reaches the page. The paste box is told through its
    // own controller, which also puts Format and the completion options out of reach.
    private void applyReadOnly() {
        final boolean locked = readOnly || host == null || host.getProject() == null;
        view.setReadOnly(locked);
        pasted.setReadOnly(locked);
    }


    public void setHost(final ProjectHost host) {
        this.host = host;
    }

    /// Shown: read what the sample is into the page, and list the streams the first time.
    public void refresh() {
        final SampleSource current = host == null
                ? null
                : host.getSampleSource();
        reading = true;
        try {
            final boolean pastedKind = current == null || current.getText() != null;
            view.setKind(pastedKind
                    ? Kind.PASTED
                    : Kind.STREAM);
            // The two kinds are kept differently, so the page says which one the author is on
            // rather than leaving them to find out at an export (design 44 §5q).
            view.setKeeping(pastedKind
                    ? "saved with the project, and carried when it is exported"
                    : "the project remembers which stream; the data stays where it is, and the "
                      + "reference is dropped when the project is exported");
            // The kept text, not the sample in force: a look at a stream must leave what the
            // author wrote where they can get back to it (design 44 §5s).
            final String kept = host == null
                    ? null
                    : host.getKeptSampleText();
            final String text = kept == null
                    ? ""
                    : kept;
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
        applyReadOnly();
    }

    /// A stream was chosen in the list: show its first record, which makes it the sample.
    private void chose() {
        final MetaRow row = metaList.getSelected();
        if (reading || row == null || row.getMeta() == null) {
            return;
        }
        final Meta meta = row.getMeta();
        source.setSourceLocation(SourceLocation.builder(meta.getId()).build());
    }

    /// The viewer settled on a record: that record is the sample.
    private void viewing(final SourceLocation location) {
        if (reading || host == null || location == null || view.getKind() != Kind.STREAM) {
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
        // The location, not the label: the label carries feed, id, part and record but not the
        // child stream type, so switching to a record's Context would read as no change at all
        // and the run would stay on the stream the viewer is no longer showing.
        if (current == null || !Objects.equals(current.getLocation(), address)) {
            host.setSampleSource(chosen);
        }
    }

    public void onKind(final Kind kind) {
        if (reading) {
            return;
        }
        if (kind == Kind.PASTED) {
            onPasted();
        } else {
            // Returning to the stream returns to the one the project kept, rather than making the
            // author find it again — both are kept and the document remembers which is in force
            // (design 44 §5t). The viewer catches up as they browse.
            final SourceLocation kept = host == null
                    ? null
                    : host.getKeptSampleLocation();
            if (kept != null) {
                host.setSampleSource(SampleSource.record(kept, null));
            }
            chose();
        }
    }

    public void onPasted() {
        final String text = pasted.getText() == null || pasted.getText().trim().isEmpty()
                ? ""
                : pasted.getText();
        if (reading || host == null || text.isEmpty() || view.getKind() != Kind.PASTED) {
            return;
        }
        final SampleSource current = host.getSampleSource();
        if (current != null && text.equals(current.getText())) {
            return;
        }
        host.setSampleSource(SampleSource.pasted(text));
    }

    /// Narrow the stream list, as stepping narrows it: the same expression over the same fields.
    private void filter() {
        final ExpressionEditorController presenter = filterProvider.get()
                .attach(new ExpressionEditor());
        presenter.read(metaList.getCriteria().getExpression());
        final SimpleFieldSelectionListModel fields = new SimpleFieldSelectionListModel();
        fields.addItems(MetaFields.getAllFields());
        presenter.init(MetaFields.STREAM_STORE_DOC_REF, fields);
        presenter.asWidget().getElement().addClassName("default-min-sizes");
        ControllerDialog.showOnce(this, presenter)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(1_000, 600))
                .caption("Filter Streams")
                .onShow(e -> presenter.focus())
                .onHideRequest(e -> applyFilter(presenter, e))
                .fire();
    }

    private void applyFilter(final ExpressionEditorController presenter, final HidePopupRequestEvent event) {
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
        }, taskMonitor());
    }

    private void apply(final ExpressionOperator expression) {
        metaList.getCriteria().setExpression(expression);
        metaList.getCriteria().obtainPageRequest().setOffset(0);
        metaList.getSelectionModel().clear();
        metaList.refresh();
    }

}
