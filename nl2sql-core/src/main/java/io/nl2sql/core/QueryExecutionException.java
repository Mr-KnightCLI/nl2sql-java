package io.nl2sql.core;

/** The database rejected or failed to run validated SQL. */
public class QueryExecutionException extends Nl2SqlException {
    private final String sql;

    public QueryExecutionException(String message, String sql, Throwable cause) {
        super(message, cause);
        this.sql = sql;
    }

    public String sql() {
        return sql;
    }
}
