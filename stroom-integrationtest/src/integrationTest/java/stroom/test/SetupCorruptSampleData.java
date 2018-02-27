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

package stroom.test;

import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import stroom.feed.shared.Feed;
import stroom.node.NodeCache;
import stroom.security.SecuritySpringConfig;
import stroom.streamstore.StreamStore;
import stroom.streamstore.StreamTarget;
import stroom.streamstore.shared.Stream;
import stroom.streamstore.shared.StreamType;
import stroom.util.AbstractCommandLineTool;
import stroom.util.io.FileUtil;
import stroom.util.io.StreamUtil;
import stroom.util.spring.StroomSpringProfiles;

/**
 * Script to create some base data for testing.
 */
public final class SetupCorruptSampleData extends AbstractCommandLineTool {
    private ApplicationContext appContext;

    /**
     * Main.
     *
     * @param args NA
     * @throws Exception NA
     */
    public static void main(final String[] args) throws Exception {
        final SetupCorruptSampleData setupSampleData = new SetupCorruptSampleData();
        setupSampleData.doMain(args);
    }

    public ApplicationContext getAppContext() {
        return appContext;
    }

    @Override
    public void run() {
        FileUtil.deleteContents(FileUtil.getTempDir());

        @SuppressWarnings("resource") final AnnotationConfigApplicationContext appContext = new AnnotationConfigApplicationContext();
        appContext.register(SetupSampleDataSpringConfig.class);
        appContext.refresh();

        // Force nodes to be created
        appContext.getBean(NodeCache.class).getDefaultNode();

        final CommonTestControl commonTestControl = appContext.getBean(CommonTestControl.class);
        final CommonTestScenarioCreator commonTestScenarioCreator = appContext.getBean(CommonTestScenarioCreator.class);
        final StreamStore streamStore = appContext.getBean(StreamStore.class);

        final Feed feed = commonTestScenarioCreator.createSimpleFeed();

        final Stream sourceStream = commonTestScenarioCreator.createSample2LineRawFile(feed, StreamType.RAW_EVENTS);

        final StreamTarget target = streamStore.openStreamTarget(
                Stream.createProcessedStream(sourceStream, sourceStream.getFeed(), StreamType.EVENTS, null, null));

        try {
            target.getOutputStream().write(" ".getBytes(StreamUtil.DEFAULT_CHARSET));
        } catch (final Exception ex) {
            throw new RuntimeException(ex);
        }
        streamStore.closeStreamTarget(target);

        commonTestControl.shutdown();
    }
}
