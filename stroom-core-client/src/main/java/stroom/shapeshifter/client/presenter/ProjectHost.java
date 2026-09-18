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

package stroom.shapeshifter.client.presenter;

import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.Template;

import java.util.ArrayList;
import java.util.List;

/**
 * What every editing presenter in the Design tab sees of the document: the current project, and
 * one way to change it. Edits are replacements (design 43 §4) — the model is immutable records, so
 * an edit is a new {@link Project} handed back here, and the root keeps the previous one.
 */
public interface ProjectHost {

    Project getProject();

    /** Hand back an edited project; the root re-renders every presenter and re-validates. */
    void replace(Project project);

    boolean isReadOnly();

    /** A template's swatch colour: the author's override, or the palette by position (design 18 §5.6). */
    String colour(String templateId);

    /** Choose a template's colour, or null for the palette's; presentation, so the run is never stale for it. */
    void setColour(String templateId, String colour);

    // ---- the run and the trace (design 18 §5; phase B) ----

    /** The sample the project runs over, or null while none has been supplied (design 18 Q2). */
    String getSample();

    /** Supply a sample and run. */
    void setSample(String sample);

    /** Run the project over the sample now; the trace arrives asynchronously. */
    void run();

    /** The last run's trace, or null before the first run. */
    TraceModel trace();

    /** Whether the project has changed since the trace was made. */
    boolean isStale();

    /** The selected frame — the cursor — as a frame id; {@link TraceModel#ROOT} for the document. */
    long cursor();

    void setCursor(long frameId);

    // ---- where I was (design 18 §5.3): navigation states, walkable ----

    boolean canGoBack();

    boolean canGoForward();

    void goBack();

    void goForward();

    /** Point at something, or at nothing for null: every surface lights what the request names (design 18 §5.5). */
    void hover(Hot hot);

    /** The template with this id in the current project, or null. */
    default Template template(final String id) {
        final Project project = getProject();
        if (project == null || id == null) {
            return null;
        }
        for (final Template template : project.templates()) {
            if (id.equals(template.id())) {
                return template;
            }
        }
        return null;
    }

    /** The current project with one template replaced by id. */
    default Project withTemplate(final Template template) {
        final Project project = getProject();
        final List<Template> templates = new ArrayList<>(project.templates());
        for (int i = 0; i < templates.size(); i++) {
            if (templates.get(i).id().equals(template.id())) {
                templates.set(i, template);
                return new Project(project.name(), project.version(), project.source(), templates);
            }
        }
        templates.add(template);
        return new Project(project.name(), project.version(), project.source(), templates);
    }
}
