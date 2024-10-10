/*
 * Copyright 2018-2024 Crown Copyright
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

package stroom.contentindex;

import stroom.docstore.api.ContentIndex;
import stroom.job.api.ScheduledJobsBinder;
import stroom.util.RunnableWrapper;
import stroom.util.entityevent.EntityEvent;
import stroom.util.guice.GuiceUtil;

import com.google.inject.AbstractModule;
import jakarta.inject.Inject;

public class ContentIndexModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(ContentIndex.class).to(ContentIndexImpl.class);

        GuiceUtil.buildMultiBinder(binder(), EntityEvent.Handler.class)
                .addBinding(ContentIndexImpl.class);

        ScheduledJobsBinder.create(binder())
                .bindJobTo(IndexContent.class, builder -> builder
                        .name("Reindex Content")
                        .description("Reindex Stroom content to improve content find results.")
                        .managed(true)
                        .enabled(false)
                        .advanced(true)
                        .frequencySchedule("1h"));
    }


    // --------------------------------------------------------------------------------


    private static class IndexContent extends RunnableWrapper {

        @Inject
        IndexContent(final ContentIndexImpl contentIndex) {
            super(contentIndex::reindex);
        }
    }
}
