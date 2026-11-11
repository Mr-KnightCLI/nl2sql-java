package io.nl2sql.core;

import io.nl2sql.core.config.Nl2SqlSettings;
import io.nl2sql.core.examples.ExampleStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class SampleExamplesTest {
    @TempDir
    Path dir;

    @Test
    void documentedExamplesRunThroughTheEngineWithExpectedResults() throws Exception {
        Path database = dir.resolve("samples.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            // This fixture contains only simple statements, with no semicolons in strings.
            for (String sql : resource("schema.sql").split(";")) {
                if (!sql.isBlank()) statement.execute(sql);
            }
        }
        Path examplesFile = dir.resolve("examples.json");
        Files.writeString(examplesFile, resource("examples.json"));
        var examples = new ExampleStore(examplesFile, Nl2SqlEngine.defaultJson()).all();
        var expectedColumns = List.of(
                List.of("customer_name", "paid_total_cents"), List.of("customer_name"),
                List.of("carrier_name", "late_count"), List.of("shipment_id"),
                List.of("department_name", "active_count"), List.of("department_name", "salary_total_cents"));
        List<List<List<Object>>> expectedRows = List.of(
                List.of(List.of("Asha", 15000), List.of("Ben", 8000)),
                List.of(List.of("Chen")),
                List.of(List.of("North Freight", 1), List.of("South Freight", 0), List.of("West Freight", 0)),
                List.of(List.of(3)),
                List.of(List.of("Engineering", 2), List.of("Sales", 1), List.of("Support", 0)),
                List.of(List.of("Engineering", 17000000), List.of("Sales", 6000000)));

        Nl2SqlSettings settings = new Nl2SqlSettings();
        settings.getDb().setType("sqlite");
        settings.getDb().setName(database.toString());
        settings.setExamplesPath(examplesFile.toString());
        var llm = new ScriptedLlm();
        try (var engine = Nl2SqlEngine.create(settings, llm)) {
            assertThat(examples).hasSize(expectedRows.size());
            for (int i = 0; i < examples.size(); i++) {
                var example = examples.get(i);
                llm.sql(example.sql());
                QueryResult result = engine.ask(example.question(), List.of());
                assertThat(result.ok()).as("%s: %s", example.question(), result.error()).isTrue();
                assertThat(result.attempts()).isEqualTo(1);
                assertThat(result.executed()).isTrue();
                assertThat(result.truncated()).isFalse();
                assertThat(result.columns()).containsExactlyElementsOf(expectedColumns.get(i));
                assertThat(result.rows()).as(example.question()).isEqualTo(expectedRows.get(i));
                assertThat(llm.calls.get(i).toString()).contains(example.sql());
            }
        }
    }

    private static String resource(String name) throws Exception {
        try (var input = Objects.requireNonNull(SampleExamplesTest.class.getResourceAsStream("/examples/sqlite/" + name))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
