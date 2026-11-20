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

package stroom.shapeshifter.client.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestHot {

    @Test
    void aCapturesVariableRidesAlongWithoutChangingWhichCaptureItIs() {
        // Pointed at in the text the request knows its variable; from the variables pane's row it
        // is built the same way. Either names the same capture, so the row lights for both.
        final Hot fromText = Hot.capture(4, 0, "pair/key");
        final Hot fromRow = Hot.capture(4, 0, null);

        assertThat(fromText).isEqualTo(fromRow);
        assertThat(fromText.hashCode()).isEqualTo(fromRow.hashCode());
        assertThat(fromText.key()).isEqualTo("4:0");
        assertThat(fromText.getVariable()).isEqualTo("pair/key");
        assertThat(Hot.capture(4, 1, "pair/key")).isNotEqualTo(fromText);
    }
}
