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

package stroom.shapeshifter.client.view;

import stroom.shapeshifter.client.presenter.GrantPresenter.GrantView;
import stroom.widget.valuespinner.client.ValueSpinner;

import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewImpl;

public class GrantViewImpl extends ViewImpl implements GrantView {

    // A grant is bounded only so that a slip of the keyboard cannot grant a session the earth: a billion
    // tokens, and a day for each run.
    private static final long MAX_TOKENS = 1_000_000_000L;
    private static final long MAX_MINUTES = 24L * 60L;

    private final Widget widget;

    @UiField
    Label soFar;
    @UiField
    ValueSpinner tokens;
    @UiField
    ValueSpinner minutes;

    @Inject
    public GrantViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        tokens.setMin(0L);
        tokens.setMax(MAX_TOKENS);
        tokens.setDelta(1_000);
        minutes.setMin(0L);
        minutes.setMax(MAX_MINUTES);
        minutes.setDelta(1);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void setSoFar(final String text) {
        soFar.setText(text);
    }

    @Override
    public long getTokens() {
        return value(tokens);
    }

    @Override
    public void setTokens(final long value) {
        tokens.setValue(value);
    }

    @Override
    public long getMinutes() {
        return value(minutes);
    }

    @Override
    public void setMinutes(final long value) {
        minutes.setValue(value);
    }

    @Override
    public void focus() {
        tokens.focus();
    }

    private static long value(final ValueSpinner spinner) {
        final Long value = spinner.getValue();
        return value == null
                ? 0L
                : value;
    }


    // --------------------------------------------------------------------------------


    public interface Binder extends UiBinder<Widget, GrantViewImpl> {

    }
}
