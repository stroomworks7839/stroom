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

package stroom.shapeshifter.ai.fragment;

import stroom.docref.DocRef;

/**
 * Whether a pipeline document is a fragment in the sense of proposed ruling A20: a chain from
 * {@code Source} that ends at a filter, with no writer or destination, so that the supervisor can attach
 * its own capture. Applied when a Shapeshifter AI document is saved, so that a routing rule can never point at a
 * pipeline
 * the supervisor would have to refuse at run time.
 */
public interface FragmentCheck {

    /**
     * @throws stroom.util.shared.EntityServiceException with a message naming the offending element, if
     *                                                   the pipeline is not a fragment.
     */
    void check(DocRef pipeline);
}
