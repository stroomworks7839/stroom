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

package stroom.security.client.view;

import stroom.security.client.presenter.AppPermissionsPresenter.AppPermissionsView;
import stroom.util.shared.UserRef;
import stroom.util.shared.string.CaseType;
import stroom.widget.form.client.FormGroup;

import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.HTML;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.View;
import com.gwtplatform.mvp.client.ViewImpl;

public final class AppPermissionsViewImpl
        extends ViewImpl
        implements AppPermissionsView {

    public static final String APP_PERM_BASE_LABEL = "Application Permissions";
    public static final String APP_PERM_DETAILS_BASE_LABEL = "Application Permission Details";
    private final Widget widget;

    @UiField
    SimplePanel appUserPermissionsList;

    @UiField
    FormGroup appPermissionsFormGroup;
    @UiField
    SimplePanel appPermissionsEdit;

    @UiField
    FormGroup detailsFormGroup;
    @UiField
    HTML details;

    @Inject
    public AppPermissionsViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        setUserRef(null);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void setAppUserPermissionListView(View view) {
        appUserPermissionsList.setWidget(view.asWidget());
    }

    @Override
    public void setAppPermissionsEditView(final View view) {
        appPermissionsEdit.setWidget(view.asWidget());
    }

    @Override
    public void setDetails(final SafeHtml details) {
        this.details.setHTML(details);
    }

    @Override
    public void setUserRef(final UserRef userRef) {
        if (userRef == null) {
            appPermissionsFormGroup.setLabel(APP_PERM_BASE_LABEL + ":");
            detailsFormGroup.setLabel(APP_PERM_DETAILS_BASE_LABEL + ":");
        } else {
            final String suffix = " for " + userRef.getType(CaseType.LOWER)
                                  + " \"" + userRef.getDisplayName() + "\":";
            appPermissionsFormGroup.setLabel(APP_PERM_BASE_LABEL + suffix);
            detailsFormGroup.setLabel(APP_PERM_DETAILS_BASE_LABEL + suffix);
        }
    }


    // --------------------------------------------------------------------------------


    public interface Binder extends UiBinder<Widget, AppPermissionsViewImpl> {

    }
}
