package io.nl2sql.server;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.nl2sql.core.QueryResult;
import io.nl2sql.core.prompt.Turn;

import java.util.ArrayList;
import java.util.List;

/** Request and response bodies. Field names match the Python version's REST API. */
final class ApiModels {
    private ApiModels() {
    }

    record TurnDto(String question, String sql) {
    }

    record AskRequest(String question, List<TurnDto> history) {
        List<Turn> turns() {
            List<Turn> out = new ArrayList<>();
            if (history != null) {
                for (TurnDto t : history) {
                    if (t != null && notBlank(t.question()) && notBlank(t.sql())) out.add(new Turn(t.question(), t.sql()));
                }
            }
            return out;
        }
    }

    record GenerateResponse(
            String question,
            String sql,
            String explanation,
            String clarification,
            @JsonProperty("tables_used") List<String> tablesUsed,
            int attempts,
            String error) {
        static GenerateResponse of(QueryResult r) {
            return new GenerateResponse(r.question(), r.sql(), r.explanation(), r.clarification(), r.tablesUsed(),
                    r.attempts(), r.error());
        }
    }

    record AskResponse(
            String question,
            String sql,
            String explanation,
            String clarification,
            @JsonProperty("tables_used") List<String> tablesUsed,
            int attempts,
            String error,
            List<String> columns,
            List<List<Object>> rows,
            @JsonProperty("row_count") int rowCount,
            boolean truncated,
            @JsonProperty("elapsed_ms") double elapsedMs) {
        static AskResponse of(QueryResult r) {
            return new AskResponse(r.question(), r.sql(), r.explanation(), r.clarification(), r.tablesUsed(),
                    r.attempts(), r.error(), r.columns(), r.rows(), r.rowCount(), r.truncated(), r.elapsedMs());
        }
    }

    record ExampleRequest(String question, String sql) {
    }

    record InfoResponse(
            String database,
            @JsonProperty("database_type") String databaseType,
            String schema,
            int tables,
            String version) {
    }

    record ErrorResponse(String error) {
    }

    static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
