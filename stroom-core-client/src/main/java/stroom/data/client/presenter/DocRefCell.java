package stroom.data.client.presenter;

import stroom.core.client.UrlParameters;
import stroom.data.client.presenter.DocRefCell.DocRefProvider;
import stroom.data.grid.client.EventCell;
import stroom.docref.DocRef;
import stroom.docref.DocRef.DisplayType;
import stroom.docstore.shared.DocumentType;
import stroom.docstore.shared.DocumentTypeRegistry;
import stroom.document.client.event.OpenDocumentEvent;
import stroom.svg.shared.SvgImage;
import stroom.util.client.ClipboardUtil;
import stroom.util.shared.GwtNullSafe;
import stroom.widget.menu.client.presenter.IconMenuItem;
import stroom.widget.menu.client.presenter.IconParentMenuItem;
import stroom.widget.menu.client.presenter.Item;
import stroom.widget.menu.client.presenter.MenuItem;
import stroom.widget.menu.client.presenter.ShowMenuEvent;
import stroom.widget.popup.client.presenter.PopupPosition;
import stroom.widget.util.client.ElementUtil;
import stroom.widget.util.client.MouseUtil;
import stroom.widget.util.client.SvgImageUtil;

import com.google.gwt.cell.client.AbstractCell;
import com.google.gwt.cell.client.ValueUpdater;
import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.event.shared.GwtEvent;
import com.google.gwt.event.shared.HasHandlers;
import com.google.gwt.safehtml.client.SafeHtmlTemplates;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlBuilder;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.user.client.Window;
import com.google.gwt.view.client.CellPreviewEvent;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import static com.google.gwt.dom.client.BrowserEvents.MOUSEDOWN;

public class DocRefCell<T_ROW> extends AbstractCell<DocRefProvider<T_ROW>>
        implements HasHandlers, EventCell {

    private static final String ICON_CLASS_NAME = "svgIcon";
    private static final String COPY_CLASS_NAME = "docRefLinkCopy";
    private static final String OPEN_CLASS_NAME = "docRefLinkOpen";
    private static final String HOVER_ICON_CONTAINER_CLASS_NAME = "hoverIconContainer";
    private static final String HOVER_ICON_CLASS_NAME = "hoverIcon";

    private final EventBus eventBus;
    private final boolean allowLinkByName;
    private final boolean showIcon;
    private final DocRef.DisplayType displayType;
    private final Function<T_ROW, String> cssClassFunction;
    private final Function<DocRefProvider<T_ROW>, SafeHtml> cellTextFunction;

    private static volatile Template template;

    /**
     * @param showIcon         Set to true to show the type icon next to the text
     * @param cssClassFunction Can be null. Function to provide additional css class names.
     * @param cellTextFunction Can be null. Function to provide the cell 'text' in HTML form. If null
     *                         then displayType will be used to derive the text from the {@link DocRef}.
     */
    private DocRefCell(final EventBus eventBus,
                       final boolean allowLinkByName,
                       final boolean showIcon,
                       final DocRef.DisplayType displayType,
                       final Function<T_ROW, String> cssClassFunction,
                       final Function<DocRefProvider<T_ROW>, SafeHtml> cellTextFunction) {
        super(MOUSEDOWN);
        this.eventBus = eventBus;
        this.allowLinkByName = allowLinkByName;
        this.showIcon = showIcon;
        this.displayType = displayType;
        this.cssClassFunction = cssClassFunction;
        this.cellTextFunction = cellTextFunction;

        if (template == null) {
            template = GWT.create(Template.class);
        }
    }

    @Override
    public boolean isConsumed(final CellPreviewEvent<?> event) {
        final NativeEvent nativeEvent = event.getNativeEvent();
        if (MOUSEDOWN.equals(nativeEvent.getType()) && MouseUtil.isPrimary(nativeEvent)) {
            final Element element = nativeEvent.getEventTarget().cast();
            return ElementUtil.hasClassName(element, COPY_CLASS_NAME, 5) ||
                   ElementUtil.hasClassName(element, OPEN_CLASS_NAME, 5);
        }
        return false;
    }

    @Override
    public void onBrowserEvent(final Context context,
                               final Element parent,
                               final DocRefProvider<T_ROW> value,
                               final NativeEvent event,
                               final ValueUpdater<DocRefProvider<T_ROW>> valueUpdater) {
        super.onBrowserEvent(context, parent, value, event, valueUpdater);
        if (value.getDocRef() != null) {
            if (MOUSEDOWN.equals(event.getType())) {
                if (MouseUtil.isPrimary(event)) {
                    onEnterKeyDown(context, parent, value, event, valueUpdater);
                } else {
                    final DocRef docRef = GwtNullSafe.get(value, DocRefProvider::getDocRef);
                    if (docRef != null) {
                        final String type;
                        final DocumentType documentType = DocumentTypeRegistry.get(docRef.getType());
                        type = GwtNullSafe.getOrElse(documentType, DocumentType::getDisplayType,
                                docRef.getType());

                        final List<Item> menuItems = new ArrayList<>();
                        int priority = 1;
                        menuItems.add(new IconMenuItem.Builder()
                                .priority(priority++)
                                .icon(SvgImage.OPEN)
                                .text("Open " + type)
                                .command(() -> OpenDocumentEvent.fire(this, docRef, true))
                                .build());
                        menuItems.add(createCopyAsMenuItem(docRef, priority++));

                        ShowMenuEvent
                                .builder()
                                .items(menuItems)
                                .popupPosition(new PopupPosition(event.getClientX(), event.getClientY()))
                                .fire(this);
                    }
                }
            }
        }
    }

    @Override
    public void fireEvent(final GwtEvent<?> gwtEvent) {
        eventBus.fireEvent(gwtEvent);
    }

    @Override
    protected void onEnterKeyDown(final Context context,
                                  final Element parent,
                                  final DocRefProvider<T_ROW> value,
                                  final NativeEvent event,
                                  final ValueUpdater<DocRefProvider<T_ROW>> valueUpdater) {
        final Element element = event.getEventTarget().cast();
        final DocRef docRef = GwtNullSafe.get(value, DocRefProvider::getDocRef);
        if (docRef != null) {
            if (ElementUtil.hasClassName(element, COPY_CLASS_NAME, 5)) {
                final String text = getTextFromDocRef(docRef);
                if (text != null) {
                    ClipboardUtil.copy(text);
                }
            } else if (ElementUtil.hasClassName(element, OPEN_CLASS_NAME, 5)) {
                OpenDocumentEvent.fire(this, docRef, true);
            }
        }
    }

    @Override
    public void render(final Context context, final DocRefProvider<T_ROW> value, final SafeHtmlBuilder sb) {
        if (value == null) {
            sb.append(SafeHtmlUtils.EMPTY_SAFE_HTML);
        } else {
            final DocRef docRef = GwtNullSafe.get(value, DocRefProvider::getDocRef);

            final SafeHtml cellHtmlText;
            if (cellTextFunction != null) {
                cellHtmlText = cellTextFunction.apply(value);
            } else if (docRef != null) {
                cellHtmlText = SafeHtmlUtils.fromString(getTextFromDocRef(docRef, displayType));
            } else {
                cellHtmlText = SafeHtmlUtils.EMPTY_SAFE_HTML;
            }

            String cssClasses = "docRefLinkText";
            if (cssClassFunction != null) {
                final String additionalClasses = cssClassFunction.apply(value.getRow());
                if (additionalClasses != null) {
                    cssClasses += " " + additionalClasses;
                }
            }
            final SafeHtml textDiv = template.div(cssClasses, cellHtmlText);

            final String containerClasses = String.join(
                    " ",
                    HOVER_ICON_CONTAINER_CLASS_NAME,
                    "docRefLinkContainer");

            sb.appendHtmlConstant("<div class=\"" + containerClasses + "\">");
            if (docRef != null && showIcon) {
                final DocumentType documentType = DocumentTypeRegistry.get(docRef.getType());
                if (documentType != null) {
                    final SvgImage svgImage = documentType.getIcon();
                    final SafeHtml iconDiv = SvgImageUtil.toSafeHtml(
                            documentType.getDisplayType(),
                            svgImage,
                            ICON_CLASS_NAME,
                            "docRefLinkIcon");
                    sb.append(iconDiv);
                }
            }

            sb.append(textDiv);

            // Add copy and open links.
            if (docRef != null) {
                // This DocRefCell gets used for pipeline props which sometimes are a docRef
                // and other times just a simple string
                final SafeHtml copy = SvgImageUtil.toSafeHtml(
                        SvgImage.COPY,
                        ICON_CLASS_NAME,
                        COPY_CLASS_NAME,
                        HOVER_ICON_CLASS_NAME);
                sb.append(template.divWithToolTip(
                        "Copy name '" + docRef.getName() + "' to clipboard",
                        copy));

                if (docRef.getUuid() != null || allowLinkByName) {
                    final SafeHtml open = SvgImageUtil.toSafeHtml(
                            SvgImage.OPEN,
                            ICON_CLASS_NAME,
                            OPEN_CLASS_NAME,
                            HOVER_ICON_CLASS_NAME);
                    sb.append(template.divWithToolTip(
                            "Open " + docRef.getType() + " " + docRef.getName() + " in new tab",
                            open));
                }
            }

            sb.appendHtmlConstant("</div>");
        }
    }

    public static String getTextFromDocRef(final DocRef docRef) {
        return getTextFromDocRef(docRef, DisplayType.AUTO);
    }

    public static String getTextFromDocRef(final DocRef docRef, final DisplayType displayType) {
        if (docRef == null) {
            return null;
        } else {
            return docRef.getDisplayValue(GwtNullSafe.requireNonNullElse(displayType, DisplayType.AUTO));
        }
    }

    private MenuItem createCopyAsMenuItem(final DocRef docRef,
                                          final int priority) {
        List<Item> children = createCopyAsChildMenuItems(docRef);
        return new IconParentMenuItem.Builder()
                .priority(priority)
                .icon(SvgImage.COPY)
                .text("Copy As")
                .children(children)
                .enabled(true)
                .build();
    }

    private List<Item> createCopyAsChildMenuItems(final DocRef docRef) {
        // If a user has VIEW on a doc they will also see (but not have VIEW) all ancestor
        // docs, so we need to only allow 'copy name' for these 'see but not view' cases.
        // Thus, totalCount may be bigger than readableCount
        final List<Item> childMenuItems = new ArrayList<>();
        int priority = 1;
        if (GwtNullSafe.isNonBlankString(docRef.getName())) {
            childMenuItems.add(new IconMenuItem.Builder()
                    .priority(priority++)
                    .icon(SvgImage.COPY)
                    .text("Copy Name to Clipboard")
                    .enabled(true)
                    .command(() -> ClipboardUtil.copy(docRef.getName()))
                    .build());
        }
        if (GwtNullSafe.isNonBlankString(docRef.getUuid())) {
            childMenuItems.add(new IconMenuItem.Builder()
                    .priority(priority++)
                    .icon(SvgImage.COPY)
                    .text("Copy UUID to Clipboard")
                    .enabled(true)
                    .command(() -> ClipboardUtil.copy(docRef.getUuid()))
                    .build());
        }
        childMenuItems.add(createCopyLinkMenuItem(docRef, priority++));
        return childMenuItems;
    }

    private MenuItem createCopyLinkMenuItem(final DocRef docRef, final int priority) {
        // Generate a URL that can be used to open a new Stroom window with the target document loaded
        final String docUrl = Window.Location.createUrlBuilder()
                .setPath("/")
                .setParameter(UrlParameters.ACTION, UrlParameters.OPEN_DOC_ACTION)
                .setParameter(UrlParameters.DOC_TYPE_QUERY_PARAM, docRef.getType())
                .setParameter(UrlParameters.DOC_UUID_QUERY_PARAM, docRef.getUuid())
                .buildString();

        return new IconMenuItem.Builder()
                .priority(priority)
                .icon(SvgImage.SHARE)
                .text("Copy Link to Clipboard")
                .command(() -> ClipboardUtil.copy(docUrl))
                .build();
    }


    // --------------------------------------------------------------------------------


    interface Template extends SafeHtmlTemplates {

        @Template("<div class=\"{0}\">{1}</div>")
        SafeHtml div(String cssClass, SafeHtml content);

        @Template("<div title=\"{0}\">{1}</div>")
        SafeHtml divWithToolTip(String title, SafeHtml content);
    }


    // --------------------------------------------------------------------------------


    public static class DocRefProvider<T_ROW> {

        private final T_ROW row;
        private final Function<T_ROW, DocRef> docRefExtractor;

        public DocRefProvider(final T_ROW row,
                              final Function<T_ROW, DocRef> docRefExtractor) {
            this.row = row;
            this.docRefExtractor = Objects.requireNonNull(docRefExtractor);
        }

        /**
         * For uses where the rendering of the cell doesn't need the original row value, so
         * this essentially returns an identity function.
         */
        public static DocRefProvider<DocRef> forDocRef(final DocRef docRefRow) {
            return new DocRefProvider<>(docRefRow, Function.identity());
        }

        public T_ROW getRow() {
            return row;
        }

        public DocRef getDocRef() {
            return GwtNullSafe.get(row, docRefExtractor);
        }
    }

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    public static class Builder<T> {

        private EventBus eventBus;
        private boolean allowLinkByName = false;
        private boolean showIcon = false;
        private DocRef.DisplayType displayType = DisplayType.NAME;
        private Function<T, String> cssClassFunction;
        private Function<DocRefProvider<T>, SafeHtml> cellTextFunction;

        public Builder<T> eventBus(final EventBus eventBus) {
            this.eventBus = eventBus;
            return this;
        }

        public Builder<T> allowLinkByName(final boolean allowLinkByName) {
            this.allowLinkByName = allowLinkByName;
            return this;
        }

        public Builder<T> showIcon(final boolean showIcon) {
            this.showIcon = showIcon;
            return this;
        }

        public Builder<T> displayType(final DisplayType displayType) {
            this.displayType = displayType;
            return this;
        }

        public Builder<T> cssClassFunction(final Function<T, String> cssClassFunction) {
            this.cssClassFunction = cssClassFunction;
            return this;
        }

        public Builder<T> cellTextFunction(final Function<DocRefProvider<T>, SafeHtml> cellTextFunction) {
            this.cellTextFunction = cellTextFunction;
            return this;
        }

        public DocRefCell<T> build() {
            return new DocRefCell<>(
                    eventBus,
                    allowLinkByName,
                    showIcon,
                    displayType,
                    cssClassFunction,
                    cellTextFunction);
        }
    }
}
