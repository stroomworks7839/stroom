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

import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.MatchExpression;
import stroom.shapeshifter.config.PatternNode;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.json.JsonText;
import stroom.shapeshifter.config.json.ProjectJson;

import java.util.List;

/**
 * The client's edge onto the project text: the config module's own parser and printer over its
 * own mapping, so that a document printed here reads exactly as one the engine prints (design 43
 * §5). Every parse throws {@link ConfigException} naming what is wrong, line and column included
 * for a syntax error.
 */
public final class ProjectText {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private ProjectText() {
    }

    /** The format version a project written here is written to: the latest the engine reads. */
    public static final int CURRENT_VERSION = 5;

    public static Project parse(final String text) {
        return ProjectJson.readProject(JsonText.parse(text));
    }

    /**
     * What a new document holds before anything is written to it: a named project with its
     * document template and nothing else. Not quite empty, because a project without a document
     * template cannot open a root element and so cannot emit a well-formed document, and nothing
     * would have said so (design 44 §5m).
     */
    public static Project empty(final String name) {
        return Templates.ensureDocument(new Project(name == null
                ? "project"
                : name, CURRENT_VERSION, null, null, null));
    }

    public static String print(final Project project) {
        return JsonText.printPretty(ProjectJson.writeProject(project));
    }

    public static MatchExpression parseMatch(final String text) {
        return ProjectJson.readMatch(JsonText.parse(text));
    }

    public static String printMatch(final MatchExpression match) {
        return JsonText.printPretty(ProjectJson.writeMatch(match));
    }

    public static PatternNode parsePatternNode(final String text) {
        return ProjectJson.readPatternNode(JsonText.parse(text));
    }

    public static String printPatternNode(final PatternNode node) {
        return JsonText.printPretty(ProjectJson.writePatternNode(node));
    }

    /** The project's pattern library as {@code print} wants it beside a tree, or null for none. */
    public static String printPatterns(final Project project) {
        return project == null || project.patterns().isEmpty()
                ? null
                : JsonText.printPretty(ProjectJson.writePatterns(project.patterns()));
    }

    /**
     * A fresh template id in the eight-four-four-four-twelve spelling the reader insists on. The
     * client has no {@code UUID}; a random version-4 shape is what the engine's own ids are.
     */
    public static String newId() {
        final StringBuilder sb = new StringBuilder(36);
        for (int i = 0; i < 36; i++) {
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                sb.append('-');
            } else if (i == 14) {
                sb.append('4');
            } else if (i == 19) {
                sb.append(HEX[8 + (int) (Math.random() * 4)]);
            } else {
                sb.append(HEX[(int) (Math.random() * 16)]);
            }
        }
        return sb.toString();
    }
}
