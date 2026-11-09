package io.nl2sql.core;

import java.util.List;

/**
 * Outcome of one question. Exactly one of these holds: {@code sql != null && error == null} (success),
 * {@code clarification != null} (the question was ambiguous), or {@code error != null}.
 * {@code columns}/{@code rows} are empty when the SQL was only generated, not run.
 */
public record QueryResult(
        String question,
        String sql,
        String explanation,
        String clarification,
        List<String> tablesUsed,
        int attempts,
        String error,
        List<String> columns,
        List<List<Object>> rows,
        boolean truncated,
        double elapsedMs,
        boolean executed) {

    public boolean ok() {
        return sql != null && error == null;
    }

    public int rowCount() {
        return rows.size();
    }

    static QueryResult failure(String question, String explanation, int attempts, String error) {
        return new QueryResult(question, null, explanation, null, List.of(), attempts, error, List.of(), List.of(), false, 0, false);
    }

    static QueryResult clarification(String question, String explanation, String clarification, int attempts) {
        return new QueryResult(question, null, explanation, clarification, List.of(), attempts, null, List.of(), List.of(), false, 0, false);
    }
}
