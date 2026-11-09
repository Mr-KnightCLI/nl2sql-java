package io.nl2sql.core.examples;

/** A verified question and the SQL that answers it. */
public record Example(String question, String sql, String notes) {
    public Example {
        notes = notes == null ? "" : notes;
    }
}
