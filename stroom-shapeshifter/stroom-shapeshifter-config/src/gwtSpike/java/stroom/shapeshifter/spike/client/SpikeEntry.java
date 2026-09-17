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

package stroom.shapeshifter.spike.client;

import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.Template;
import stroom.shapeshifter.config.json.JsonArray;
import stroom.shapeshifter.config.json.JsonObject;
import stroom.shapeshifter.config.json.JsonValue;
import stroom.shapeshifter.config.json.ProjectJson;

import com.google.gwt.core.client.EntryPoint;
import com.google.gwt.user.client.Window;

/**
 * The GWT half of this module's promise (build.gradle): an entry point that reaches the whole
 * mapping, so that the GWT compiler — not the browser, weeks later — is what finds a JDK API
 * the emulation lacks or a Java construct GWT mishandles. It is never run; compiling it is the
 * test. Reading and then writing a project visits every family's reader and writer.
 */
public class SpikeEntry implements EntryPoint {

    @Override
    public void onModuleLoad() {
        final JsonObject root = new JsonObject();
        root.put("name", "spike").put("version", 5);
        final JsonArray templates = root.putArray("templates");
        final JsonObject template = new JsonObject();
        template.put("id", "00000000-0000-0000-0000-000000000001").put("name", "root").put("match", "source");
        template.putArray("body").add(new JsonObject().put("text", "hi"));
        templates.add(template);
        final Project project = ProjectJson.readProject(root);
        final JsonValue back = ProjectJson.writeProject(project);
        final Template first = project.templates().get(0);
        Window.alert(first.name() + " " + first.match() + " " + back.size());
    }
}
