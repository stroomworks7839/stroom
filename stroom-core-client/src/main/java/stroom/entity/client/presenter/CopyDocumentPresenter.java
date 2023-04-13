/*
 * Copyright 2017 Crown Copyright
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
 *
 */

package stroom.entity.client.presenter;

import stroom.docref.DocRef;
import stroom.document.client.event.CopyDocumentEvent;
import stroom.document.client.event.ShowCopyDocumentDialogEvent;
import stroom.entity.client.presenter.CopyDocumentPresenter.CopyDocumentProxy;
import stroom.entity.client.presenter.CopyDocumentPresenter.CopyDocumentView;
import stroom.explorer.client.presenter.EntityTreePresenter;
import stroom.explorer.shared.DocumentTypes;
import stroom.explorer.shared.ExplorerNode;
import stroom.explorer.shared.PermissionInheritance;
import stroom.security.shared.DocumentPermissionNames;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupType;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenter;
import com.gwtplatform.mvp.client.View;
import com.gwtplatform.mvp.client.annotations.ProxyCodeSplit;
import com.gwtplatform.mvp.client.annotations.ProxyEvent;
import com.gwtplatform.mvp.client.proxy.Proxy;

import java.util.List;
import java.util.stream.Collectors;

public class CopyDocumentPresenter
        extends MyPresenter<CopyDocumentView, CopyDocumentProxy>
        implements ShowCopyDocumentDialogEvent.Handler {

    private final EntityTreePresenter entityTreePresenter;
    private List<ExplorerNode> explorerNodeList;

    @Inject
    public CopyDocumentPresenter(final EventBus eventBus,
                                 final CopyDocumentView view,
                                 final CopyDocumentProxy proxy,
                                 final EntityTreePresenter entityTreePresenter) {
        super(eventBus, view, proxy);
        this.entityTreePresenter = entityTreePresenter;
        view.setFolderView(entityTreePresenter.getView());

        entityTreePresenter.setIncludedTypes(DocumentTypes.FOLDER_TYPES);
        entityTreePresenter.setRequiredPermissions(DocumentPermissionNames.USE, DocumentPermissionNames.READ);
    }

    @ProxyEvent
    @Override
    public void onCopy(final ShowCopyDocumentDialogEvent event) {
        this.explorerNodeList = event.getExplorerNodeList();

        entityTreePresenter.setSelectedItem(null);

        final ExplorerNode firstChild = event.getExplorerNodeList().get(0);
        entityTreePresenter.setSelectedItem(firstChild);
        entityTreePresenter.getModel().reset();
        entityTreePresenter.getModel().setEnsureVisible(firstChild);
        entityTreePresenter.getModel().refresh();

        forceReveal();
    }

    @Override
    protected void revealInParent() {
        String caption = "Copy Multiple Items";
        if (explorerNodeList.size() == 1) {
            caption = "Copy " + explorerNodeList.get(0).getDisplayValue();
        }
        getView().setPermissionInheritance(PermissionInheritance.DESTINATION);

        final PopupSize popupSize = PopupSize.resizable(400, 550);
        ShowPopupEvent.builder(this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(popupSize)
                .caption(caption)
                .onShow(e -> entityTreePresenter.focus())
                .onHideRequest(e -> {
                    if (e.isOk()) {
                        final ExplorerNode folder = entityTreePresenter.getSelectedItem();
                        CopyDocumentEvent.fire(
                                CopyDocumentPresenter.this,
                                CopyDocumentPresenter.this,
                                explorerNodeList,
                                folder,
                                getView().getPermissionInheritance());
                    } else {
                        e.hide();
                    }
                })
                .fire();
    }

    public interface CopyDocumentView extends View {

        void setFolderView(View view);

        PermissionInheritance getPermissionInheritance();

        void setPermissionInheritance(PermissionInheritance permissionInheritance);
    }

    @ProxyCodeSplit
    public interface CopyDocumentProxy extends Proxy<CopyDocumentPresenter> {

    }
}
