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
 */

package stroom.search.impl;

import stroom.event.logging.rs.api.AutoLogged;
import stroom.index.impl.StroomIndexQueryResource;
import stroom.query.common.v2.AbstractDataSourceResource;
import stroom.query.common.v2.SearchResponseCreatorManager;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

@Singleton
@AutoLogged
public class StroomIndexQueryResourceImpl
        extends AbstractDataSourceResource
        implements StroomIndexQueryResource {

    @Inject
    StroomIndexQueryResourceImpl(
            final Provider<SearchResponseCreatorManager> searchResponseCreatorManagerProvider) {
        super(searchResponseCreatorManagerProvider);
    }
}
