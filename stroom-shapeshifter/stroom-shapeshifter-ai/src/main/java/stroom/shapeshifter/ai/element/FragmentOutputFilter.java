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

package stroom.shapeshifter.ai.element;

import stroom.pipeline.factory.ConfigurableElement;
import stroom.pipeline.filter.AbstractXMLFilter;
import stroom.pipeline.shared.data.PipelineElementType;
import stroom.svg.shared.SvgImage;

import jakarta.inject.Inject;

/**
 * The tail the supervisor adds to a fragment when it runs it: every event the fragment's last element
 * emits goes to the supervisor's downstream. Internal — a fragment has no destination of its own
 * (A20), and this is the one the supervisor lends it.
 */
@ConfigurableElement(
        type = FragmentOutputFilter.TYPE,
        roles = {PipelineElementType.ROLE_TARGET},
        icon = SvgImage.AI)
public class FragmentOutputFilter extends AbstractXMLFilter {

    public static final String TYPE = "ShapeshifterAiOutput";

    private final FragmentOutput output;

    @Inject
    public FragmentOutputFilter(final FragmentOutput output) {
        this.output = output;
    }

    @Override
    public void startProcessing() {
        setContentHandler(output.getHandler());
        super.startProcessing();
    }
}
