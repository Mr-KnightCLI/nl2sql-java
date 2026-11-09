package io.nl2sql.core;

import io.nl2sql.core.config.Nl2SqlSettings;
import io.nl2sql.core.config.Dialect;
import io.nl2sql.core.prompt.Prompts;
import io.nl2sql.core.prompt.ParsedResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EngineSqliteTest {
    @TempDir
    Path dir;
    Path dbFile;
    ScriptedLlm llm;
    Nl2SqlEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = dir.resolve("tms.db");
        try (Connection c = TestDatabase.connect("jdbc:sqlite:" + dbFile, null, null)) {
            TestDatabase.load(c, Dialect.SQLITE);
        }
        Nl2SqlSettings s = new Nl2SqlSettings();
        s.getDb().setType("sqlite");
        s.getDb().setName(dbFile.toString());
        s.setExamplesPath(dir.resolve("examples.json").toString());
        llm = new ScriptedLlm();
        engine = Nl2SqlEngine.create(s, llm);
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    @Test
    void introspectsSchema() {
        assertThat(engine.catalog().tables().keySet()).containsExactlyInAnyOrderElementsOf(TestDatabase.TABLES);
        assertThat(engine.catalog().render(List.of("shipments"))).contains("stat_cd").contains("REFERENCES");
        // e-mail addresses are personal data: no sample values
        assertThat(engine.catalog().table("carriers").column("contact_email").sampleValues()).isEmpty();
    }

    @Test
    void asksAndRuns() {
        llm.sql("SELECT c.carrier_name, COUNT(*) AS n FROM shipments s JOIN carriers c ON c.carrier_id = s.carrier_id GROUP BY c.carrier_name ORDER BY n DESC");
        QueryResult r = engine.ask("shipments per carrier", List.of());
        assertThat(r.ok()).as(r.error()).isTrue();
        assertThat(r.columns()).containsExactly("carrier_name", "n");
        assertThat(r.rowCount()).isEqualTo(8);
        assertThat(r.tablesUsed()).containsExactlyInAnyOrder("shipments", "carriers");
        assertThat(llm.lastUserPrompt()).contains("shipments per carrier");
    }

    @Test
    void correctsAfterGuardrailAndDatabaseErrors() {
        llm.sql("DELETE FROM shipments", "SELECT no_such_col FROM shipments", "SELECT COUNT(*) AS n FROM shipments");
        QueryResult r = engine.ask("how many shipments", List.of());
        assertThat(r.ok()).as(r.error()).isTrue();
        assertThat(r.attempts()).isEqualTo(3);
        assertThat(r.rows().get(0).get(0)).isEqualTo(120);
    }

    @Test
    void givesUpAfterMaxAttempts() {
        llm.sql("DROP TABLE shipments", "DROP TABLE shipments", "DROP TABLE shipments");
        QueryResult r = engine.ask("break it", List.of());
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("DROP TABLE");
    }

    @Test
    void enforcesRowLimitAndReadOnly() throws Exception {
        engine.settings().getGuardrails().setMaxRows(10);
        llm.sql("SELECT shipment_ref FROM shipments");
        QueryResult r = engine.ask("all refs", List.of());
        assertThat(r.rowCount()).isEqualTo(10);
        assertThat(r.truncated()).isTrue();
        try (Connection c = TestDatabase.connect("jdbc:sqlite:" + dbFile, null, null);
             ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM shipments")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(120);
        }
    }

    @Test
    void clarificationAndGenerate() {
        llm.queue("{\"sql\": null, \"explanation\": \"\", \"clarification\": \"Which period?\"}");
        QueryResult c = engine.ask("revenue", List.of());
        assertThat(c.clarification()).isEqualTo("Which period?");
        llm.sql("SELECT COUNT(*) FROM invoices");
        QueryResult g = engine.generate("invoice count", List.of());
        assertThat(g.executed()).isFalse();
        assertThat(g.sql()).isEqualTo("SELECT COUNT(*) FROM invoices");
    }

    @Test
    void examplesAreStoredAndReused() {
        engine.addExample("late shipments", "SELECT COUNT(*) FROM shipments WHERE delivered_at > promised_delivery_at");
        llm.sql("SELECT 1 AS x FROM shipments LIMIT 1");
        engine.ask("how many late shipments", List.of());
        assertThat(llm.lastUserPrompt() + llm.calls.get(0).toString()).contains("delivered_at > promised_delivery_at");
    }

    @Test
    void parsesSloppyModelOutput() {
        var json = Nl2SqlEngine.defaultJson();
        ParsedResponse a = Prompts.parse("```json\n{\"sql\": \"SELECT 1\", \"explanation\": \"x\"}\n```", json);
        assertThat(a.sql()).isEqualTo("SELECT 1");
        ParsedResponse b = Prompts.parse("```sql\nSELECT id FROM t\n```", json);
        assertThat(b.sql()).isEqualTo("SELECT id FROM t");
    }
}
