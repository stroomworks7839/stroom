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

import stroom.alert.client.event.AlertEvent;
import stroom.shapeshifter.client.presenter.CaptureEditPresenter.CaptureEditView;
import stroom.shapeshifter.config.CaptureBinding;
import stroom.shapeshifter.config.CaptureBinding.CaptureSource;
import stroom.shapeshifter.config.Cast;
import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.Declaration;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.config.json.JsonNumber;
import stroom.shapeshifter.config.json.JsonObject;
import stroom.shapeshifter.config.json.JsonString;
import stroom.shapeshifter.config.json.JsonText;
import stroom.shapeshifter.config.json.JsonValue;
import stroom.shapeshifter.config.json.ProjectJson;
import stroom.widget.popup.client.event.HidePopupRequestEvent;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupType;

import com.google.gwt.user.client.ui.Focus;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One capture: the declared name it binds into, where the value comes from, and the cast. A
 * group or a label is a field each; a {@code select} or {@code key-value} source is a reference
 * expression, edited here as its wire form and read by the one reader the project has.
 */
public class CaptureEditPresenter extends MyPresenterWidget<CaptureEditView> {

    public enum SourceKind {
        GROUP("group"),
        LABEL("label"),
        SELECT("select"),
        KEY_VALUE("key-value");

        private final String tag;

        SourceKind(final String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    @Inject
    public CaptureEditPresenter(final EventBus eventBus, final CaptureEditView view) {
        super(eventBus, view);
    }

    public void read(final Template template, final CaptureBinding capture) {
        final List<String> declared = new ArrayList<>();
        for (final Declaration declaration : template.declarations()) {
            declared.add(declaration.name());
        }
        getView().setDeclared(declared);
        getView().setName(capture.name());
        getView().setCast(capture.as());
        final CaptureSource source = capture.select();
        if (source instanceof CaptureSource.Group group) {
            getView().setKind(SourceKind.GROUP);
            getView().setGroup(String.valueOf(group.group()));
            getView().setLabel("");
            getView().setSelect("");
        } else if (source instanceof CaptureSource.Label label) {
            getView().setKind(SourceKind.LABEL);
            getView().setGroup("1");
            getView().setLabel(label.label());
            getView().setSelect("");
        } else {
            getView().setKind(source instanceof CaptureSource.KeyValue
                    ? SourceKind.KEY_VALUE
                    : SourceKind.SELECT);
            getView().setGroup("1");
            getView().setLabel("");
            final JsonValue wire = ProjectJson.writeCapture(capture).get("select");
            getView().setSelect(JsonText.printPretty(body(wire)));
        }
    }

    /** The payload inside a tagged {@code {"select": {...}}} or {@code {"key-value": {...}}}. */
    private static JsonValue body(final JsonValue tagged) {
        if (tagged instanceof JsonObject object) {
            for (final Map.Entry<String, JsonValue> entry : object.entries()) {
                return entry.getValue();
            }
        }
        return tagged;
    }

    /** The edited capture, or null after telling the user what is wrong. */
    public CaptureBinding write() {
        try {
            final SourceKind kind = getView().getKind();
            final JsonValue payload;
            switch (kind) {
                case GROUP:
                    payload = JsonNumber.of(Integer.parseInt(getView().getGroup().trim()));
                    break;
                case LABEL:
                    payload = new JsonString(getView().getLabel().trim());
                    break;
                default:
                    payload = JsonText.parse(getView().getSelect());
                    break;
            }
            final JsonObject node = new JsonObject();
            node.put("name", getView().getName().trim());
            node.put("select", new JsonObject().put(kind.tag(), payload));
            final Cast cast = getView().getCast();
            if (cast != null) {
                node.put("as", cast.name().toLowerCase(java.util.Locale.ROOT));
            }
            final CaptureBinding capture = ProjectJson.readCapture(node);
            if (capture.name().isEmpty()) {
                throw new ConfigException("A capture needs the name of the declaration it binds into");
            }
            return capture;
        } catch (final ConfigException e) {
            AlertEvent.fireWarn(this, e.getMessage(), null);
            return null;
        } catch (final NumberFormatException e) {
            AlertEvent.fireWarn(this, "A group is a number: " + getView().getGroup(), null);
            return null;
        }
    }

    public void show(final String caption, final HidePopupRequestEvent.Handler handler) {
        ShowPopupEvent.builder(this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(480, 420))
                .caption(caption)
                .onShow(e -> getView().focus())
                .onHideRequest(handler)
                .fire();
    }

    public interface CaptureEditView extends View, Focus {

        String getName();

        void setName(String name);

        /** The template's declared names, offered beside the free text. */
        void setDeclared(List<String> names);

        SourceKind getKind();

        void setKind(SourceKind kind);

        String getGroup();

        void setGroup(String group);

        String getLabel();

        void setLabel(String label);

        String getSelect();

        void setSelect(String json);

        Cast getCast();

        void setCast(Cast cast);
    }
}
