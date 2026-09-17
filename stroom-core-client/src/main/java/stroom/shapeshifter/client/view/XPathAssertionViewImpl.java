/*
 * Copyright 2016-2026 Crown Copyright
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

import stroom.shapeshifter.client.presenter.XPathAssertionPresenter.XPathAssertionView;

import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewImpl;

public class XPathAssertionViewImpl extends ViewImpl implements XPathAssertionView {

    private final Widget widget;

    @UiField
    TextBox name;
    @UiField
    TextBox xpath;

    @Inject
    public XPathAssertionViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public String getName() {
        return name.getValue();
    }

    @Override
    public void setName(final String name) {
        this.name.setValue(name);
    }

    @Override
    public String getXpath() {
        return xpath.getValue();
    }

    @Override
    public void setXpath(final String xpath) {
        this.xpath.setValue(xpath);
    }


    // --------------------------------------------------------------------------------


    public interface Binder extends UiBinder<Widget, XPathAssertionViewImpl> {

    }
}
