package stroom.shapeshifter.engine.fixture;

import stroom.shapeshifter.engine.Shapeshifter;
import stroom.shapeshifter.engine.config.ProjectReader;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The migrated configurations stay migrated: every phase-0 audit-list fixture compiles
 * without the version-bump warning, so a regression to a pre-5 version with a substring
 * fires here by name (design/17 phase 6).
 */
class MigrationWarningCheck {

    @Test
    void migratedConfigurationsDrawNoBumpWarning() {
        for (final String name : new String[] {
                "apache_httpd", "xml_to_json", "xml_to_json_attrs", "xml_to_json_unified"}) {
            final var compiled = Shapeshifter.compile(ProjectReader.read(
                    FixtureLedger.text("projects/" + name + "/project.json")));
            assertThat(compiled.warnings())
                    .as(name)
                    .noneMatch(m -> m.text().contains("0-based"));
        }
    }
}
