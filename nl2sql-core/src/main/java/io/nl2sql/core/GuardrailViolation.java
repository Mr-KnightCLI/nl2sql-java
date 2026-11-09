package io.nl2sql.core;

/** Generated SQL was rejected by a safety check. */
public class GuardrailViolation extends Nl2SqlException {
    private final String sql;

    public GuardrailViolation(String reason, String sql) {
        super(reason);
        this.sql = sql;
    }

    public String sql() {
        return sql;
    }
}
