package io.nl2sql.core;

import io.nl2sql.core.config.Dialect;
import io.nl2sql.core.config.Nl2SqlSettings;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs against real databases when NL2SQL_TEST_&lt;DB&gt;_URL (plus _USER and _PASSWORD) are set — in CI these point at
 * service containers. Each case is skipped when its URL is missing.
 */
class LiveDatabaseTest {

    @ParameterizedTest
    @ValueSource(strings = {"MYSQL", "MARIADB", "PG", "MSSQL", "ORACLE"})
    void endToEnd(String key) throws Exception {
        String url = System.getenv("NL2SQL_TEST_" + key + "_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "NL2SQL_TEST_" + key + "_URL not set");
        String user = System.getenv("NL2SQL_TEST_" + key + "_USER");
        String password = System.getenv("NL2SQL_TEST_" + key + "_PASSWORD");
        Dialect d = Dialect.fromJdbcUrl(url);
        try (Connection c = TestDatabase.connect(url, user, password)) {
            TestDatabase.load(c, d);
        }

        Nl2SqlSettings s = new Nl2SqlSettings();
        s.getDb().setUrl(url);
        s.getDb().setUsername(user);
        s.getDb().setPassword(password);
        s.getDb().setIncludeTables(TestDatabase.TABLES);
        ScriptedLlm llm = new ScriptedLlm();
        try (Nl2SqlEngine engine = Nl2SqlEngine.create(s, llm)) {
            assertThat(engine.catalog().tables().keySet()).containsExactlyInAnyOrderElementsOf(TestDatabase.TABLES);
            assertThat(engine.catalog().table("shipments").column("stat_cd").sampleValues()).contains("D");

            llm.sql("SELECT c.carrier_name, COUNT(*) AS n FROM shipments s JOIN carriers c ON c.carrier_id = s.carrier_id "
                    + "GROUP BY c.carrier_name ORDER BY n DESC");
            QueryResult r = engine.ask("shipments per carrier", List.of());
            assertThat(r.ok()).as(r.error()).isTrue();
            assertThat(r.rowCount()).isEqualTo(8);
            assertThat(r.columns()).hasSize(2);

            // the correction loop recovers from a database error
            llm.sql("SELECT nope FROM shipments", "SELECT COUNT(*) AS n FROM shipments");
            QueryResult fixed = engine.ask("count shipments", List.of());
            assertThat(fixed.ok()).as(fixed.error()).isTrue();
            assertThat(((Number) fixed.rows().get(0).get(0)).intValue()).isEqualTo(120);

            engine.settings().getGuardrails().setMaxRows(5);
            llm.sql("SELECT shipment_ref FROM shipments");
            QueryResult limited = engine.ask("refs", List.of());
            assertThat(limited.rowCount()).isEqualTo(5);
            assertThat(limited.truncated()).isTrue();
        }
    }
}
