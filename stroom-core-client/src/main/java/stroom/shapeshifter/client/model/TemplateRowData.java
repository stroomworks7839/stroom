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

/// One row of the nav panel: the project itself (id null), a template, or a part of the pattern
/// library (design 44 §3), under a section heading — the root, a mode, the patterns.
public final class TemplateRowData {

    private final String id;
    private final String name;
    private final String section;
    private final String colour;
    private final String count;
    private final boolean zero;
    private final double share;
    private final int cost;
    private final String profile;
    private final int severity;
    /// A section of the panel rather than a row in one — Settings, Data, Templates, Patterns
    /// (design 44 §5m, ruled 2026-11-20). Two of the four are a destination, so a section is a
    /// row that renders as a heading rather than a heading that happens to be clickable:
    /// selection, hover and the keyboard then work on it exactly as they do on everything else
    /// in the list. The other two hold what follows them, and fold.
    private final boolean panelSection;
    /// What is worth saying about the mode this row heads, or null. Carried by the first row of
    /// a mode because the heading is drawn from that row rather than being a row of its own.
    private String sectionNote;
    /// The id selecting this row's heading selects, or null for a heading that is only a
    /// heading. A mode's heading is the way to the mode's own pane; the root's is not, since
    /// the root has no name to edit. Carried by the first row of the mode, as the note is.
    private String sectionId;
    /// Not a row but the way to add one: the link a mode's list ends with, or the one the
    /// modes end with.
    private boolean fresh;
    /// What the row says of itself on its tooltip, where that is not its name and its count.
    private String title;
    /// What the template's limit lets it match — `first only`, `at most 3` — or null for no
    /// limit on how many.
    private String limit;

    public TemplateRowData(final String id, final String name, final String section, final String colour,
                           final String count, final boolean zero) {
        this(id, name, section, colour, count, zero, 0, 0, null, 0);
    }

    /// @return What is worth saying about the mode this row heads, or null.
    public String getSectionNote() {
        return sectionNote;
    }

    /// Says something about the mode this row heads. Named for what it does: it sets the note
    /// on this row rather than returning a copy, which a `with` would promise.
    ///
    /// @param sectionNote The note.
    /// @return This, so it can be set where the row is made.
    public TemplateRowData setSectionNote(final String sectionNote) {
        this.sectionNote = sectionNote;
        return this;
    }

    /// @return The id selecting this row's heading selects, or null.
    public String getSectionId() {
        return sectionId;
    }

    /// Says what selecting the heading of the mode this row heads selects.
    ///
    /// @param sectionId The id.
    /// @return This, so it can be set where the row is made.
    public TemplateRowData setSectionId(final String sectionId) {
        this.sectionId = sectionId;
        return this;
    }

    /// An add link: where a template is added to a mode, or a mode to the project.
    ///
    /// @param id The link's id, which is no template's.
    /// @param name What the link reads.
    /// @param section The heading it sits under, or null.
    /// @param title What it says of itself.
    /// @return The row.
    public static TemplateRowData fresh(final String id, final String name, final String section,
                                        final String title) {
        final TemplateRowData row = new TemplateRowData(id, name, section, null, "", false);
        row.fresh = true;
        row.title = title;
        return row;
    }

    /// @return What the template's limit lets it match, or null for no limit on how many.
    public String getLimit() {
        return limit;
    }

    /// Says what the template's limit lets it match, after its count: two templates with one
    /// pattern, one matching the first and the other the rest, otherwise read the same
    /// (design 47 §7.3, ruled 2026-11-19).
    ///
    /// @param limit What it lets it match, or null for no limit on how many.
    /// @return This, so it can be set where the row is made.
    public TemplateRowData setLimit(final String limit) {
        this.limit = limit;
        return this;
    }

    /// @return Whether this is an add link rather than a template, a part or a section.
    public boolean isFresh() {
        return fresh;
    }

    /// @return What the row says of itself on its tooltip, or null for its name and count.
    public String getTitle() {
        return title;
    }

    /// A panel section: a destination when it carries an id, and otherwise a heading over the
    /// rows that follow it, which folds them away.
    ///
    /// @param id What selecting it selects, or null for a section that folds.
    /// @param name What it is called.
    /// @param count What it says of what it holds, or null.
    /// @return The row.
    public static TemplateRowData section(final String id, final String name, final String count) {
        return new TemplateRowData(id, name, null, null, count, false, 0, 0, null, 0, true);
    }

    public TemplateRowData(final String id, final String name, final String section, final String colour,
                           final String count, final boolean zero, final double share, final int cost,
                           final String profile, final int severity) {
        this(id, name, section, colour, count, zero, share, cost, profile, severity, false);
    }

    private TemplateRowData(final String id, final String name, final String section, final String colour,
                            final String count, final boolean zero, final double share, final int cost,
                            final String profile, final int severity, final boolean isSection) {
        this.panelSection = isSection;
        this.id = id;
        this.name = name;
        this.section = section;
        this.colour = colour;
        this.count = count;
        this.zero = zero;
        this.share = share;
        this.cost = cost;
        this.profile = profile;
        this.severity = severity;
    }

    /// Whether this is a panel section — a heading, and a destination when it has an id, or
    /// one that folds what follows it when it has none.
    public boolean isSection() {
        return panelSection;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /// The heading the row sits under; null for the project's own row.
    public String getSection() {
        return section;
    }

    public String getColour() {
        return colour;
    }

    /// What the row says at its right: a match count once there is a trace, the kind until then.
    public String getCount() {
        return count;
    }

    /// Dimmed: no matches, or nothing to show yet.
    public boolean isZero() {
        return zero;
    }

    /// The heat bar's length: this template's share of the run's time, 0..1 (design 18 §5.8).
    public double getShare() {
        return share;
    }

    /// The heat bar's colour: per-attempt cost against the run's, 0 cool, 1 warm, 2 hot.
    public int getCost() {
        return cost;
    }

    /// The worst thing said in any frame of the template or beneath one, as [TraceModel#rank].
    public int getSeverity() {
        return severity;
    }

    /// The numbers behind the bar, for its title; null before a run.
    public String getProfile() {
        return profile;
    }
}
