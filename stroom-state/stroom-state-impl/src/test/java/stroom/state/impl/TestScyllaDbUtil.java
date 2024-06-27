package stroom.state.impl;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

public class TestScyllaDbUtil {

    @Test
    void testConnection() {
        ScyllaDbUtil.test(ScyllaDbUtil::printMetadata);
    }

    @Test
    void testReplaceKeyspaceNameInCql() {
        final String newKeyspaceName = "this_is_a_test";
        final String cql = ScyllaDbUtil.getDefaultKeyspaceCql();
        final String actual = ScyllaDbUtil.replaceKeyspaceNameInCql(cql, newKeyspaceName);
        final String expected = """
                CREATE KEYSPACE IF NOT EXISTS this_is_a_test
                WITH replication = { 'class': 'NetworkTopologyStrategy', 'replication_factor': '1' }
                AND durable_writes = TRUE;""";
        assertThat(actual).isEqualTo(expected);

        final Optional<String> extracted = ScyllaDbUtil.extractKeyspaceNameFromCql(actual);
        assertThat(extracted).isNotEmpty();
        assertThat(extracted.get()).isEqualTo(newKeyspaceName);
    }

    /**
     * Disabled as we don't usually want to drop all keyspaces
     **/
    @Disabled
    @Test
    public void dropAllKeyspacesFromDefault() {
        ScyllaDbUtil.dropAllKeyspacesFromDefault();
    }
}
